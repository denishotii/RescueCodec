"""Load spec/vocab/sar.yaml and prepare it for matching. Licence: Apache-2.0.

The vocabulary is data. New words in the file need no code change.
"""
from __future__ import annotations

import os
import re
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

import yaml

from rescuecodec.v2.render import spec_dir

_FOLD = str.maketrans({"ä": "ae", "ö": "oe", "ü": "ue", "ß": "ss", "’": "'", "`": "'", "´": "'"})
_TOKEN = re.compile(r"\d+(?:[.,]\d+)?|[a-z]+(?:'[a-z]+)?|%")


def fold(text: str) -> str:
    """Lowercase, umlauts to ae/oe/ue, sharp s to ss. Applied to text and vocabulary alike."""
    return text.lower().translate(_FOLD)


def tokenize(text: str) -> list[str]:
    return _TOKEN.findall(fold(text))


@dataclass(frozen=True)
class Phrase:
    field: str          # "age", "injuries", "msg" (message cue), "negation", ...
    code: str           # code name, or the message type for "msg"
    tokens: tuple[str, ...]
    lang: str


@dataclass(frozen=True)
class Vocab:
    phrases: tuple[Phrase, ...]
    numbers: dict[tuple[str, ...], int]
    helpers: tuple[tuple[str, ...], ...]
    persons: tuple[tuple[str, ...], ...]
    coverage_fixed: dict[tuple[str, ...], int]
    version: str
    person_nouns: tuple[tuple[str, ...], ...] = ()


def vocab_path() -> Path:
    env = os.getenv("RESCUECODEC_VOCAB")
    return Path(env) if env else spec_dir() / "vocab" / "sar.yaml"


def _phrases(words, field, code, lang):
    for word in words or []:
        toks = tuple(tokenize(str(word)))
        if toks:
            yield Phrase(field, code, toks, lang)


@lru_cache(maxsize=4)
def _load(path: str) -> Vocab:
    data = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    out: list[Phrase] = []
    for kind, entry in data["messages"].items():
        for lang in ("en", "de"):
            out += _phrases(entry.get(lang), "msg", kind, lang)
    for fname, fdef in data["fields"].items():
        for value in fdef["values"]:
            for lang in ("en", "de"):
                out += _phrases(value.get(lang), fname, value["code"], lang)
    mods = data["modifiers"]
    for name in ("negation", "uncertainty"):
        for lang in ("en", "de"):
            out += _phrases(mods[name].get(lang), name, name, lang)
    numbers: dict[tuple[str, ...], int] = {}
    for lang in ("en", "de"):
        for word, value in mods["numbers"][lang].items():
            numbers[tuple(tokenize(str(word)))] = int(value)

    def _plain(name):
        res = []
        for lang in ("en", "de"):
            res += [tuple(tokenize(str(w))) for w in mods[name].get(lang, [])]
        return tuple(t for t in res if t)

    fixed = {tuple(tokenize(str(k))): int(v) for k, v in mods["coverage"].get("fixed", {}).items()}
    return Vocab(tuple(out), numbers, _plain("helpers"), _plain("persons"), fixed, str(data.get("spec_version", "")), _plain("person_nouns"))


def load_vocab(path: str | os.PathLike | None = None) -> Vocab:
    return _load(str(path or vocab_path()))
