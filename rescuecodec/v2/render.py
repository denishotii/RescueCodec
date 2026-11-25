"""Rebuild a readable sentence from a canonical JSON message (spec/SPEC.md, "Readable text").

The algorithm is fixed by the spec; the words come from spec/i18n/<lang>.json.
Every library that implements rendering must give the same text for the
`text` entries in spec/vectors/codec.json.
"""
from __future__ import annotations

import json
import os
from collections.abc import Mapping
from functools import lru_cache
from pathlib import Path
from typing import Any

from rescuecodec.v2 import codes as C
from rescuecodec.v2.codec import deg_to_e5, message_type


def spec_dir() -> Path:
    env = os.getenv("RESCUECODEC_SPEC_DIR")
    return Path(env) if env else Path(__file__).resolve().parents[2] / "spec"


@lru_cache(maxsize=None)
def load_language(lang: str) -> dict:
    path = spec_dir() / "i18n" / f"{lang}.json"
    if not path.is_file():
        raise ValueError(f"no language file for {lang!r}")
    return json.loads(path.read_text(encoding="utf-8"))


def languages() -> list[str]:
    return sorted(p.stem for p in (spec_dir() / "i18n").glob("*.json"))


def format_e5(e5: int) -> str:
    """Integer degrees x 1e5 -> "-50.69212" (always 5 decimals, "." as separator)."""
    sign = "-" if e5 < 0 else ""
    e5 = abs(e5)
    return f"{sign}{e5 // 100000}.{e5 % 100000:05d}"


def _fill(template: str, **values: Any) -> str:
    for key, value in values.items():
        template = template.replace("{" + key + "}", str(value))
    return template


class _Words:
    def __init__(self, data: dict):
        self.d = data

    def value(self, group: str, name: str) -> str:
        if name.startswith("unknown_") or name.startswith("bit_"):
            return _fill(self.d["part"]["unknown_value"], n=name.split("_", 1)[1])
        return self.d["value"][f"{group}.{name}"]

    def part(self, key: str, **values: Any) -> str:
        return _fill(self.d["part"][key], **values)

    def head(self, key: str, **values: Any) -> str:
        return _fill(self.d["head"][key], **values)

    def join_list(self, items: list[str]) -> str:
        p = self.d["punct"]
        if len(items) <= 1:
            return "".join(items)
        return p["list_sep"].join(items[:-1]) + p["list_last"] + items[-1]


def _end(sentence: str, end: str) -> str:
    return sentence if sentence[-1:] in (".", "!", "?", end) else sentence + end


def render(msg: Mapping, lang: str = "en") -> str:
    """Canonical JSON message -> one readable line in `lang` (en, de, uk, tr, ar)."""
    data = load_language(lang)
    w = _Words(data)
    p = data["punct"]
    kind = message_type(msg)
    body: Mapping[str, Any] = msg.get(kind) or {}
    parts: list[str] = []

    if kind == "found":
        count = body.get("count", 0)
        head = w.head("found_many", n=count) if count >= 2 else w.head("found")
        for name in ("age", "consciousness", "breathing", "mobility"):
            if name in body:
                parts.append(w.value(name, body[name]))
        parts += [w.value("injuries", i) for i in body.get("injuries", [])]
        if body.get("needs"):
            parts.append(w.part("found.needs", list=w.join_list([w.value("needs", n) for n in body["needs"]])))
        if body.get("responders"):
            parts.append(w.part("found.responders", n=body["responders"]))
    elif kind == "track":
        points = body.get("points", [])
        head = w.head("track")
        parts.append(w.part("track.points_1") if len(points) == 1 else w.part("track.points", n=len(points)))
        if points and "s" in points[0]:
            span = points[-1]["s"] - points[0]["s"]
            if span > 0:
                parts.append(w.part("track.minutes", n=(span + 30) // 60))
        if points:
            last = points[-1]
            parts.append(w.part("track.last", lat=format_e5(deg_to_e5(last["lat"], "lat")),
                                lon=format_e5(deg_to_e5(last["lon"], "lon"))))
    elif kind == "sector":
        head = w.head("sector", sector=body.get("sector", 0))
        parts.append(w.value("sector", body["status"]))
        if body.get("coverage"):
            parts.append(w.part("sector.coverage", n=body["coverage"]))
    elif kind == "request":
        head = w.head("request")
        resource = w.value("resource", body["resource"])
        count = body.get("count", 0)
        parts.append(w.part("request.count", n=count, resource=resource) if count >= 2 else resource)
        if "urgency" in body:
            parts.append(w.value("urgency", body["urgency"]))
    elif kind == "hazard":
        head = w.head("hazard")
        parts.append(w.value("hazard", body["kind"]))
        if "severity" in body:
            parts.append(w.value("severity", body["severity"]))
        if body.get("radius"):
            parts.append(w.part("hazard.radius", n=body["radius"]))
    elif kind == "mayday":
        head = w.head("mayday")
        if "reason" in body:
            parts.append(w.value("mayday", body["reason"]))
        if body.get("persons") == 1:
            parts.append(w.part("mayday.persons_1"))
        elif body.get("persons"):
            parts.append(w.part("mayday.persons", n=body["persons"]))
    elif kind == "ack":
        status = body.get("status", "")
        key = f"ack.{status}" if f"ack.{status}" in data["head"] else "ack.other"
        head = w.head(key, ref=body.get("ref", 0))
        if body.get("node"):
            parts.append(w.part("ack.node", node=body["node"]))
        if body.get("team"):
            parts.append(w.part("ack.team", team=body["team"]))
        if body.get("eta"):
            parts.append(w.part("ack.eta", n=body["eta"]))
    elif kind == "text":
        head = w.head("text")
        parts.append(body.get("text", ""))
    elif "words" in body and "unknown_dict" in data["head"]:
        head = w.head("unknown_dict", n=body["words"])     # compact text with a word dictionary we do not have
    elif "vocab" in body and "unknown_vocab" in data["head"]:
        head = w.head("unknown_vocab", n=body["vocab"])    # domain frame with a team vocabulary we do not have
    else:
        head = w.head("unknown")

    sentence = head + (p["head_sep"] + p["part_sep"].join(parts) if parts else "")
    sentence = _end(sentence, p["end"])
    priority = msg.get("priority")
    if priority and priority != C.DEFAULT_PRIORITY[kind]:
        sentence = "[" + w.value("priority", priority) + "] " + sentence
    pos = msg.get("pos")
    if pos:
        where = w.part("pos", lat=format_e5(deg_to_e5(pos["lat"], "pos.lat")),
                       lon=format_e5(deg_to_e5(pos["lon"], "pos.lon")))
        if pos.get("acc", "gps") != "gps":
            where += " (" + w.value("acc", pos["acc"]) + ")"
        sentence += " " + where + p["end"]
    if "time" in msg:
        sentence += " " + w.part("time", time=msg["time"]) + p["end"]
    return sentence
