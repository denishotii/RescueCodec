#!/usr/bin/env python3
"""Build and measure the text dictionary for compressed RescueCodec texts (spec/SPEC.md section 3.5).

    python spec/tools/text_dict.py measure          # numbers for spec/vocab/text-dict-v1.txt on text-corpus.json
    python spec/tools/text_dict.py build --check    # rebuild from the sources and check it matches the frozen file
    python spec/tools/text_dict.py build --write    # write a NEW dictionary (only for a new version, never for v1)

Compression: raw DEFLATE (zlib wbits -15, level 9) with the dictionary as preset dictionary (zdict).
Sources, in this order (DEFLATE codes near matches cheaper, so the most useful words come last):
  1. every word of spec/vocab/sar.yaml (en and de),
  2. the strings of spec/i18n/en.json and de.json (placeholders removed),
  3. spec/tools/text-dict-words.txt from top to bottom.
Licence: Apache-2.0.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import zlib
from pathlib import Path

import yaml

SPEC = Path(__file__).resolve().parents[1]
DICT_FILE = SPEC / "vocab" / "text-dict-v1.txt"
WORDS_FILE = Path(__file__).with_name("text-dict-words.txt")
CORPUS_FILE = Path(__file__).with_name("text-corpus.json")
WRAP = 100


def _walk_words(node, out: list[str]) -> None:
    """Every string under an `en` or `de` key of sar.yaml, in file order."""
    if isinstance(node, dict):
        for key, value in node.items():
            if key in ("en", "de"):
                if isinstance(value, list):
                    out.extend(str(v) for v in value)
                elif isinstance(value, dict):
                    out.extend(str(k) for k in value)
            else:
                _walk_words(value, out)
    elif isinstance(node, list):
        for item in node:
            _walk_words(item, out)


def _unique(words: list[str]) -> list[str]:
    seen: set[str] = set()
    out = []
    for word in words:
        word = " ".join(word.split())
        if word and word not in seen:
            seen.add(word)
            out.append(word)
    return out


def _wrap(words: list[str]) -> list[str]:
    lines, line = [], ""
    for word in words:
        if line and len(line) + 1 + len(word) > WRAP:
            lines.append(line)
            line = word
        else:
            line = f"{line} {word}" if line else word
    if line:
        lines.append(line)
    return lines


def build() -> bytes:
    sar: list[str] = []
    _walk_words(yaml.safe_load((SPEC / "vocab" / "sar.yaml").read_text(encoding="utf-8")), sar)
    i18n: list[str] = []
    for lang in ("en", "de"):
        data = json.loads((SPEC / "i18n" / f"{lang}.json").read_text(encoding="utf-8"))
        for group in ("head", "part", "value"):
            for text in data.get(group, {}).values():
                i18n.append(re.sub(r"\s*\{[a-z]+\}\s*", " ", text).strip(" :,#x"))
    words = [" ".join(raw.split()) for raw in WORDS_FILE.read_text(encoding="utf-8").splitlines()
             if raw.strip() and not raw.startswith("#")]
    # A phrase that also comes later is left out: DEFLATE finds the later (nearer) copy anyway.
    later = "\n".join(words)
    i18n = [w for w in _unique(i18n) if w not in later]
    later += "\n".join(i18n)
    sar = [w for w in _unique(sar) if w not in later and (len(w) >= 10 or " " in w) and w not in TYPOS and not _ascii_spelling(w, sar)]
    lines = _wrap(sar) + _wrap(i18n) + words
    return ("\n".join(lines) + "\n").encode("utf-8")


#: Misspellings and spellings without an apostrophe in sar.yaml. They help the extractor, not the dictionary.
TYPOS = {"fond", "foud", "gefunen", "gefundn", "ansprechbr", "unconcious", "bewustlos", "eingeklemt", "unterkült",
         "isnt", "doesnt", "dont", "isnt breathing", "cant walk", "cant tell", "cant find our", "dont know where we are"}

_UMLAUTS = str.maketrans({"ä": "ae", "ö": "oe", "ü": "ue", "ß": "ss"})


def _ascii_spelling(word: str, words: list[str]) -> bool:
    """True for "unterkuehlt" when "unterkühlt" is in the list too: the umlaut spelling is the common one."""
    return word.isascii() and any(w != word and w.translate(_UMLAUTS) == word for w in words)


def deflate(text: str, zdict: bytes | None) -> bytes:
    comp = zlib.compressobj(9, zlib.DEFLATED, -15, 8, zlib.Z_DEFAULT_STRATEGY, **({"zdict": zdict} if zdict else {}))
    return comp.compress(text.encode("utf-8")) + comp.flush()


def measure(zdict: bytes, verbose: bool = True) -> dict:
    corpus = json.loads(CORPUS_FILE.read_text(encoding="utf-8"))
    result = {}
    for part in ("tune", "holdout"):
        for lang in ("de", "en"):
            raw = nodict = packed = wins = wins_pos = 0
            for text in corpus[part][lang]:
                size = len(text.encode("utf-8"))
                plain_d = len(deflate(text, None))
                with_d = len(deflate(text, zdict))
                raw, nodict, packed = raw + size, nodict + plain_d, packed + with_d
                # Packet with an id (2 B), text (2 B), deflate (2 B + data) and dict (2 B); a GPS position adds 11 B.
                # The rule (SPEC 2.3): packed only when the packet is at least 3 B smaller than the normal text.
                wins += size - (with_d + 8) >= 3
                wins_pos += size - (with_d + 19) >= 3
                if verbose:
                    print(f"  {part:7} {lang}  {size:3} B -> {with_d:3} B ({100 * (size - with_d) / size:4.0f} %)"
                          f"  no dict {plain_d:3} B  {text}")
            n = len(corpus[part][lang])
            result[f"{part}.{lang}"] = {"messages": n, "bytes": raw, "deflate_no_dict": nodict, "deflate_dict": packed,
                                        "saved_pct": round(100 * (raw - packed) / raw, 1),
                                        "saved_no_dict_pct": round(100 * (raw - nodict) / raw, 1),
                                        "packed_no_gps": wins, "packed_with_gps": wins_pos}
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build")
    b.add_argument("--write", action="store_true", help="write spec/vocab/text-dict-v1.txt")
    b.add_argument("--check", action="store_true", help="fail when the built bytes differ from the file")
    m = sub.add_parser("measure")
    m.add_argument("--dict", type=Path, default=DICT_FILE)
    m.add_argument("--built", action="store_true", help="measure the freshly built bytes instead of the file")
    m.add_argument("-q", "--quiet", action="store_true")
    args = parser.parse_args()

    if args.cmd == "build":
        data = build()
        print(f"built {len(data)} bytes, sha256 {hashlib.sha256(data).hexdigest()}")
        if args.write:
            DICT_FILE.write_bytes(data)
            print(f"wrote {DICT_FILE}")
        if args.check:
            same = DICT_FILE.is_file() and DICT_FILE.read_bytes() == data
            print("matches the file" if same else "DIFFERS from the file")
            return 0 if same else 1
        return 0
    zdict = build() if args.built else args.dict.read_bytes()
    print(f"dictionary: {len(zdict)} bytes, sha256 {hashlib.sha256(zdict).hexdigest()}")
    result = measure(zdict, verbose=not args.quiet)
    print("Bytes of the words: UTF-8, raw DEFLATE without and with the dictionary. 'packed' = messages that go packed "
          "under the 3-byte rule (with an id; without / with a GPS position).")
    print(f"{'set':12} {'msgs':>4} {'UTF-8':>6} {'no dict':>8} {'dict':>6} {'saved':>7} {'no dict':>8} {'packed':>7} {'+GPS':>5}")
    for key, r in result.items():
        print(f"{key:12} {r['messages']:4} {r['bytes']:6} {r['deflate_no_dict']:8} {r['deflate_dict']:6} "
              f"{r['saved_pct']:6.1f}% {r['saved_no_dict_pct']:7.1f}% {r['packed_no_gps']:7} {r['packed_with_gps']:5}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
