"""POST /v2/extract: one typed sentence -> the message it would send. A live preview for the dashboard composer.

Nothing is sent and nothing is stored. The rule-based extractor (rescuecodec.engine.extract) reads the sentence; the
result is the report (or a text when no report fits), its fields as short labels, and two sizes:

    coded_bytes  bytes on air when you send the result (a report gets an id from the gateway: counted)
    text_bytes   bytes on air when you send the same words as a plain text (POST /v2/text)

Example: "Kind gefunden, ansprechbar, unterkühlt. Wir brauchen eine Trage." -> found, coded_bytes 11, text_bytes 64.
"""
from __future__ import annotations

from collections.abc import Callable, Mapping
from typing import Any

from rescuecodec.engine.extract import extract, fit_plain
from rescuecodec.v2.codec import CodecError, encode
from rescuecodec.v2.render import load_language, render

LANGS = ("en", "de")
MAX_INPUT_CHARS = 1000          # a preview is for one sentence; longer input is refused, so typing stays cheap
#: body field -> the word group in spec/i18n/<lang>.json ("value" table)
GROUP = {"found.age": "age", "found.consciousness": "consciousness", "found.breathing": "breathing",
         "found.mobility": "mobility", "found.injuries": "injuries", "found.needs": "needs",
         "request.resource": "resource", "request.urgency": "urgency", "hazard.kind": "hazard",
         "hazard.severity": "severity", "sector.status": "sector", "mayday.reason": "mayday"}
#: number field -> (table, key) of its words in spec/i18n; {n} is the number
NUMBER = {"found.count": ("head", "found_many"), "found.responders": ("part", "found.responders"),
          "sector.sector": ("head", "sector"), "sector.coverage": ("part", "sector.coverage"),
          "hazard.radius": ("part", "hazard.radius"), "mayday.persons": ("part", "mayday.persons")}


class ExtractError(ValueError):
    def __init__(self, message: str, field: str | None = None):
        super().__init__(message)
        self.message, self.field = message, field


def _word(lang: str, field: str, code: Any) -> str:
    """One field value in the words of spec/i18n/<lang>.json, for example ("de", "found.age", "child") -> "Kind"."""
    words = load_language(lang)
    if isinstance(code, int) and not isinstance(code, bool) and field in NUMBER:
        table, key = NUMBER[field]
        template = words[table].get(f"{key}_1" if code == 1 else key) or words[table][key]
        return template.replace("{n}", str(code)).replace("{sector}", str(code))
    group = GROUP.get(field)
    if group and isinstance(code, str):
        word = words["value"].get(f"{group}.{code}", code.replace("_", " "))
        return words["part"]["found.needs"].replace("{list}", word) if field == "found.needs" else word
    return str(code)


def _chip(field: str, code: Any, label: dict[str, str] | None = None) -> dict[str, Any]:
    return {"field": field, "code": code, "text": label or {lang: _word(lang, field, code) for lang in LANGS}}


def fields(message: Mapping[str, Any]) -> list[dict[str, Any]]:
    """The facts of a report as chips: [{"field": "found.age", "code": "child", "text": {"en": "child", "de": "Kind"}}].
    Priority first (urgent or critical only), then the body in message order, then the position. A text has none.
    A request shows count and resource as one chip ("2 x stretcher")."""
    out: list[dict[str, Any]] = []
    if message.get("priority") in ("urgent", "critical"):
        code = message["priority"]
        out.append(_chip("priority", code, {lang: load_language(lang)["value"][f"priority.{code}"] for lang in LANGS}))
    for kind, body in message.items():
        if kind in ("priority", "pos", "id", "time", "text") or not isinstance(body, Mapping):
            continue
        for name, value in body.items():
            field = f"{kind}.{name}"
            if field == "request.count":
                continue
            if field == "request.resource" and isinstance(body.get("count"), int):
                label = {lang: load_language(lang)["part"]["request.count"].replace("{n}", str(body["count"]))
                         .replace("{resource}", _word(lang, field, value)) for lang in LANGS}
                out.append(_chip(field, value, label))
                continue
            for code in value if isinstance(value, list) else [value]:
                out.append(_chip(field, code))
    pos = message.get("pos")
    if isinstance(pos, Mapping):
        where = f"{pos['lat']:.5f}, {pos['lon']:.5f}"
        out.append(_chip("pos", where, {lang: where for lang in LANGS}))
    return out


def preview(body: Any, *, next_id: Callable[[], int], text_as_meshtastic: bool) -> dict[str, Any]:
    """The JSON of POST /v2/extract. `next_id` gives the id the gateway would add (it costs 1 to 2 bytes);
    `text_as_meshtastic` = a text goes out as a plain Meshtastic text (its UTF-8 bytes). Raises ExtractError."""
    if not isinstance(body, dict):
        raise ExtractError("the body must be a JSON object")
    unknown = sorted(set(body) - {"text", "lang"})
    if unknown:
        raise ExtractError(f"unknown key {unknown[0]!r}", unknown[0])
    text, lang = body.get("text"), body.get("lang")
    if not isinstance(text, str) or not text.strip():
        raise ExtractError("the body needs a text (one sentence)", "text")
    if len(text) > MAX_INPUT_CHARS:
        raise ExtractError(f"text is longer than {MAX_INPUT_CHARS} characters", "text")
    if lang is not None and lang not in LANGS:
        raise ExtractError(f"lang must be {' or '.join(LANGS)} (leave it out to detect it)", "lang")

    words = " ".join(text.split())
    result = extract(words, lang)
    message = dict(result.message or {"text": {"text": words}})
    kind = result.type or "text"

    def size_of_text(line: str) -> int:
        if text_as_meshtastic:
            return len(fit_plain(line).encode("utf-8"))
        try:
            return len(encode({"id": next_id(), "text": {"text": line}}))
        except CodecError:                      # longer than a text may be: what fits is cut at 180 bytes
            return len(fit_plain(line).encode("utf-8"))

    if kind == "text":
        coded = size_of_text(message["text"]["text"])
    else:
        coded = len(encode({"id": next_id(), **message} if kind != "ack" else message))
    return {"type": kind, "message": message, "unsupported": result.unsupported, "reason": result.reason,
            "lang": result.lang, "fields": fields(message),
            "text": {lang_: render(message, lang_) for lang_ in LANGS},
            "coded_bytes": coded, "text_bytes": size_of_text(words)}
