"""Optional local LLM path (Ollama) for the extractor. Licence: Apache-2.0.

Off unless the environment variable RESCUECODEC_OLLAMA_MODEL names a model. The rules always run first.
The model can only help where it is grounded:
- the message type needs a rule cue, or a quote from the text,
- every code it sets needs a rule match for the same code, or a quote that really is in the text
  and is not negated,
- numbers must appear in the text, position and id are never taken from the model,
- reports the rules rejected on purpose (two persons in different states, an object found) are not sent to the model.
Any error, bad JSON, invalid result or a timeout (4 s) falls back to the rule result.

Environment: RESCUECODEC_OLLAMA_MODEL, RESCUECODEC_OLLAMA_URL (default http://127.0.0.1:11434),
RESCUECODEC_OLLAMA_TIMEOUT_S (default 4).
"""
from __future__ import annotations

import json
import os
import re
import urllib.request
from collections.abc import Callable
from typing import Any

import importlib
from rescuecodec.engine.vocab import fold, tokenize
from rescuecodec.v2 import codes as C
from rescuecodec.v2.codec import CodecError, encode

# the package exports the function `extract`, so load the module by name
rules = importlib.import_module("rescuecodec.engine.extract")

DEFAULT_URL = "http://127.0.0.1:11434"
DEFAULT_TIMEOUT_S = 4.0
BODY_KEYS = ("found", "sector", "request", "hazard", "mayday", "text")

# (body, field) -> (code table, kind)
_ENUMS = {
    ("found", "age"): C.AGE, ("found", "consciousness"): C.CONSCIOUSNESS, ("found", "breathing"): C.BREATHING,
    ("found", "mobility"): C.MOBILITY, ("sector", "status"): C.SECTOR_STATUS, ("request", "resource"): C.RESOURCE,
    ("request", "urgency"): C.URGENCY, ("hazard", "kind"): C.HAZARD, ("hazard", "severity"): C.SEVERITY,
    ("mayday", "reason"): C.MAYDAY_REASON,
}
_FLAGS = {("found", "injuries"): C.INJURIES, ("found", "needs"): C.NEEDS}
_NUMBERS = {("found", "count"): 255, ("found", "responders"): 255, ("sector", "sector"): 65535,
            ("sector", "coverage"): 100, ("request", "count"): 255, ("hazard", "radius"): 100000,
            ("mayday", "persons"): 255}
_FIELD_MAP = {"age": "age", "consciousness": "consciousness", "breathing": "breathing", "mobility": "mobility",
              "injuries": "injuries", "needs": "needs", "status": "sector.status", "resource": "request.resource",
              "urgency": "request.urgency", "kind": "hazard.kind", "severity": "hazard.severity", "reason": "mayday.reason"}


def output_schema() -> dict:
    """JSON schema for Ollama's `format` option. One body object, plus quotes from the text as evidence."""
    def enum(table):
        return {"type": "string", "enum": sorted(table)}

    def flags(table):
        return {"type": "array", "items": enum(table), "maxItems": len(table)}

    def num(maximum):
        return {"type": "integer", "minimum": 0, "maximum": maximum}

    bodies: dict[str, dict] = {b: {"type": "object", "additionalProperties": False, "properties": {}} for b in BODY_KEYS}
    for (b, f), table in _ENUMS.items():
        bodies[b]["properties"][f] = enum(table)
    for (b, f), table in _FLAGS.items():
        bodies[b]["properties"][f] = flags(table)
    for (b, f), maximum in _NUMBERS.items():
        bodies[b]["properties"][f] = num(maximum)
    bodies["text"]["properties"]["text"] = {"type": "string", "maxLength": 180}
    return {
        "type": "object", "additionalProperties": False,
        "properties": {
            "priority": enum(C.PRIORITY),
            **bodies,
            "evidence": {"type": "array", "maxItems": 40, "items": {
                "type": "object", "additionalProperties": False, "required": ["field", "value", "quote"],
                "properties": {"field": {"type": "string"}, "value": {"type": "string"}, "quote": {"type": "string"}}}},
        },
    }


SYSTEM = (
    "You extract one search-and-rescue report from the text. Use only facts stated in the text. "
    "Fill exactly one of these objects: found, sector, request, hazard, mayday, text. "
    "Leave out every field the text does not state. Unknown is not the same as no. Keep negation: "
    "'not hypothermic' sets nothing. Never invent positions or ids. "
    "For every code you set, add an evidence entry with the field name, the value and the exact words from the text. "
    "If one message cannot say it safely (two persons in different states, an object that was found, anything else), "
    "fill text with the original sentence. Return only JSON."
)


def _chat(url: str, payload: dict, timeout: float) -> dict:
    req = urllib.request.Request(url.rstrip("/") + "/api/chat", data=json.dumps(payload).encode("utf-8"),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:  # noqa: S310 (local URL from env)
        return json.loads(resp.read())


def configured_model() -> str:
    return os.getenv("RESCUECODEC_OLLAMA_MODEL", "").strip()


def _timeout() -> float:
    try:
        return float(os.getenv("RESCUECODEC_OLLAMA_TIMEOUT_S", DEFAULT_TIMEOUT_S))
    except ValueError:
        return DEFAULT_TIMEOUT_S


def _quote_ok(quote: str, text: str) -> bool:
    """The quote is in the text and no negation word stands right before it."""
    q, t = fold(quote).strip(), fold(text)
    if len(q) < 2 or q not in t:
        return False
    before = tokenize(t[: t.index(q)])[-3:]
    negations = {"not", "no", "never", "without", "nicht", "kein", "keine", "keinen", "ohne", "nie"}
    return not any(w in negations for w in before)


def _ground(llm: dict, text: str, base: rules.Extraction) -> tuple[dict | None, list[str]]:
    """Keep only what the text and the rules support. Returns (message or None, dropped notes)."""
    dropped: list[str] = []
    bodies = [k for k in BODY_KEYS if isinstance(llm.get(k), dict) and llm[k]]
    if len(bodies) != 1:
        return None, ["model did not give exactly one body"]
    kind = bodies[0]
    if kind == "text":
        return None, ["model chose text"]
    evidence = [e for e in llm.get("evidence", []) if isinstance(e, dict)]
    if kind not in base.cues and not any(e.get("field") == "type" and _quote_ok(str(e.get("quote", "")), text) for e in evidence):
        return None, [f"type {kind} has no cue in the text"]
    rule_hits = {(e["field"], e["code"]) for e in base.evidence if not e["negated"] and not e["uncertain"]}
    rule_negated = {(e["field"], e["code"]) for e in base.evidence if e["negated"] or e["uncertain"]}
    quotes: dict[tuple[str, str], bool] = {}
    for e in evidence:
        quotes[(str(e.get("field", "")), str(e.get("value", "")))] = _quote_ok(str(e.get("quote", "")), text)

    def grounded(field_key: str, short: str, code: str) -> bool:
        if (field_key, code) in rule_negated:
            return False
        return (field_key, code) in rule_hits or quotes.get((short, code), False)

    body_in = llm[kind]
    out: dict[str, Any] = {}
    for (b, f), table in _ENUMS.items():
        if b == kind and f in body_in:
            code = body_in[f]
            if code in table and grounded(_FIELD_MAP[f], f, code):
                out[f] = code
            else:
                dropped.append(f"{f}={code}")
    for (b, f), table in _FLAGS.items():
        if b == kind and f in body_in:
            keep = sorted({c for c in body_in[f] if c in table and grounded(_FIELD_MAP[f], f, c)}, key=lambda c: table[c])
            if keep:
                out[f] = keep
            dropped += [f"{f}={c}" for c in body_in[f] if c not in keep]
    numbers_in_text = {int(tok) for tok in tokenize(text) if tok.isdigit()}
    words = rules.load_vocab().numbers
    numbers_in_text |= {v for k, v in words.items() if all(w in tokenize(text) for w in k)}
    for (b, f), maximum in _NUMBERS.items():
        if b == kind and f in body_in:
            v = body_in[f]
            if isinstance(v, int) and 0 < v <= maximum and v in numbers_in_text:
                out[f] = v
            else:
                dropped.append(f"{f}={v}")
    msg: dict[str, Any] = {kind: out}
    if llm.get("priority") in C.PRIORITY and (("priority", llm["priority"]) in rule_hits):
        if llm["priority"] != C.DEFAULT_PRIORITY[kind]:
            msg["priority"] = llm["priority"]
    return msg, dropped


def extract_with_llm(text: str, lang: str | None = None, *, model: str | None = None, base: rules.Extraction | None = None,
                     chat: Callable[[str, dict, float], dict] | None = None) -> rules.Extraction:
    """Rules first, then the model if one is configured. Always returns the rule result on any problem."""
    base = base or rules.extract(text, lang)
    model = model if model is not None else configured_model()
    if not model:
        return base
    if base.unsupported and base.reason in (rules.R_CONFLICT, rules.R_OBJECT, rules.R_AMBIGUOUS, rules.R_EMPTY):
        base.notes.append("not sent to the model: rules rejected it on purpose")
        return base
    hint = json.dumps(base.message, ensure_ascii=False) if base.message and not base.unsupported else "none"
    payload = {"model": model, "stream": False, "format": output_schema(),
               "options": {"temperature": 0, "num_predict": 300},
               "messages": [{"role": "system", "content": SYSTEM},
                            {"role": "user", "content": f"Report: {text}\nRule-based guess (may be wrong or empty): {hint}"}]}
    try:
        answer = (chat or _chat)(os.getenv("RESCUECODEC_OLLAMA_URL", DEFAULT_URL), payload, _timeout())
        llm = json.loads(answer["message"]["content"])
        if not isinstance(llm, dict):
            raise ValueError("model output is not an object")
        msg, dropped = _ground(llm, text, base)
        if msg is None:
            base.notes.append("model not used: " + "; ".join(dropped))
            return base
        kind = next(k for k in BODY_KEYS if k in msg)
        if not base.unsupported and base.message:
            # the rules found a message already: the model may only add grounded fields of the same type
            if base.type != kind:
                base.notes.append(f"model not used: it chose {kind}, the rules chose {base.type}")
                return base
            merged = {k: (list(v) if isinstance(v, list) else v) for k, v in base.message[kind].items()}
            for f, v in msg[kind].items():
                if isinstance(v, list):
                    table = _FLAGS[(kind, f)]
                    merged[f] = sorted(set(merged.get(f, [])) | set(v), key=lambda c: table[c])
                else:
                    merged.setdefault(f, v)
            msg = {**{k: v for k, v in base.message.items() if k != kind}, kind: merged}
        if base.pos:
            msg["pos"] = dict(base.pos)
        probe = dict(msg)
        if kind in C.ID_REQUIRED:
            probe["id"] = 1
        encode(probe)  # raises CodecError when the grounded message is not valid
    except (OSError, ValueError, KeyError, TypeError, CodecError, StopIteration) as exc:
        base.notes.append(f"model not used: {type(exc).__name__}: {exc}")
        return base
    result = rules.Extraction(text, msg, False, None, "ollama+rules", base.lang, base.evidence, base.cues,
                              base.notes + ([f"dropped ungrounded: {', '.join(dropped)}"] if dropped else []))
    return result


def understand(text: str, lang: str | None = None, *, use_llm: bool | None = None, **kwargs) -> rules.Extraction:
    """The one call apps use: rules, plus the local model when RESCUECODEC_OLLAMA_MODEL is set."""
    base = rules.extract(text, lang)
    if use_llm is False:
        return base
    return extract_with_llm(text, lang, base=base, **kwargs)
