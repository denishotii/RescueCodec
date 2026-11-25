#!/usr/bin/env python3
"""Build, choose and measure word dictionary 2 (61,440 entries) and the compact frame (spec/SPEC.md 3.6, 3.7).

Dictionary 2 uses the whole token space below the controls (0x0000..0xEFFF), so almost every English word a person
types is one 2-byte token. docs/COMPACT_TEXT.md has the sources, licences and the measured results.

Inputs (NOT in the repo; download into a folder outside it and run with `python -I`):
  disaster: https://github.com/rmunro/disaster_response_messages  (CC BY 4.0)  -> DIR/disaster_response_*.csv
  tatoeba:  https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2  (CC BY 2.0 FR) -> unpack
  scowl:    https://downloads.sourceforge.net/project/wordlist/SCOWL/2020.12.07/scowl-2020.12.07.tar.gz
            (SCOWL, Kevin Atkinson, permissive licence: use, copy, modify, distribute with the copyright notice)
            -> unpack, pass the `final` folder

    python -I spec/tools/words_dict2.py select  --disaster D --tatoeba T --scowl S   # variants on validation sets
    python -I spec/tools/words_dict2.py build   --disaster D --tatoeba T --scowl S --check   # rebuild, compare
    python -I spec/tools/words_dict2.py measure --disaster D --tatoeba T --scowl S   # test-set tables

Steps (deterministic, no randomness). The train / validation / test split is the one of dictionary 1
(words_dict.load), so the held-out sets are the same and the two dictionaries are compared fairly.
 1. Pieces are counted on the training sets exactly as the codec splits text (case-sensitive).
 2. A piece may become an entry when it passes the filter of dictionary 1 (printable ASCII, in at least 3 different
    training messages of one corpus, no run of 5 or more digits, no '@', 'http', 'www') and, privacy: a piece seen in
    fewer than RARE_DOCS (10) training messages must also be an ordinary English word of SCOWL (words, contractions,
    abbreviations; not the proper-name lists), so rare names, nicknames and typos stay out.
 3. Score = bytes saved per corpus byte, both corpora weighted the same:
      a word in lower case: its own occurrences x (bytes + 1 space + 2 literal header - 2 token) plus the
        occurrences of its Capitalised / UPPER forms x (the same - 2 for the CAP or UPPER control);
      a Capitalised / UPPER form of a lower-case candidate ("The", "Please", "I"): occurrences x 2
        (one token instead of CAP + token);
      any other piece ("Tom", "iPhone"): its occurrences x (bytes + 1 + 2 - 2);
      a two-word phrase (single space, both words entries): occurrences x 2.
 4. One ranking of words and phrases by score (ties: by text); a phrase is taken only when both its words are
    already in. The words of spec/vocab/sar.yaml are always in. FILL_SCOWL slots at the end are ordinary SCOWL
    words (by SCOWL size level, then text) that the training sets do not have, for vocabulary beyond them.
 5. Entries: the punctuation pieces first, then by score. IDs are frozen once the file is in the repo.
Licence of this script: Apache-2.0.
"""
from __future__ import annotations

import argparse
import hashlib
import re
import statistics
import sys
import zlib
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "spec" / "tools"))

import words_dict as w1  # noqa: E402
from rescuecodec.v2 import textwords  # noqa: E402
from rescuecodec.v2.textwords import SPLIT, WordDict, decode_words, encode_words, match, split_pieces  # noqa: E402

SIZE = textwords.FIRST_CONTROL          # 61440: every code below the controls
HEADER = 2
MIN_DOCS = 3
RARE_DOCS = 10
FILL_SCOWL = 0                          # chosen with `select` (docs/COMPACT_TEXT.md)
SCOWL_LEVELS = (10, 20, 35, 40, 50, 55, 60, 70)
SCOWL_FILL_MAX_LEVEL = 50
DICT_FILE = ROOT / "spec" / "vocab" / "words-dict-v2.txt"
EVAL_ID = w1.EVAL_ID
PACKET = 200
MAX_INPUT = w1.MAX_INPUT
WORD_RE = re.compile(r"[A-Za-z]")


def ascii_lower(s: str) -> str:
    return "".join(chr(ord(c) + 32) if "A" <= c <= "Z" else c for c in s)


def ascii_upper(s: str) -> str:
    return "".join(chr(ord(c) - 32) if "a" <= c <= "z" else c for c in s)


def cap(s: str) -> str:
    return chr(ord(s[0]) - 32) + s[1:] if "a" <= s[0] <= "z" else s


# ---------------------------------------------------------------- SCOWL

def load_scowl(final: Path) -> tuple[set[str], list[str]]:
    """(ordinary words in lower case for the privacy filter, words for the fill in SCOWL order)."""
    known: set[str] = set()
    fill: list[tuple[int, str]] = []
    for level in SCOWL_LEVELS:
        for variant in ("english", "american", "british"):
            for cat in ("words", "contractions", "abbreviations"):
                f = final / f"{variant}-{cat}.{level}"
                if not f.exists():
                    continue
                for line in f.read_bytes().decode("latin-1").splitlines():
                    word = line.strip()
                    if not word or not word.isascii() or not word.isprintable():
                        continue
                    known.add(ascii_lower(word))
                    if cat == "words" and level <= SCOWL_FILL_MAX_LEVEL:
                        fill.append((level, word))
    order = list(dict.fromkeys(w for _, w in sorted(fill)))
    return known, order


# ---------------------------------------------------------------- candidates

def candidates(sets: dict[str, list[str]], known: set[str]) -> tuple[dict[str, float], dict[str, float]]:
    word_score: Counter = Counter()
    phrase_score: Counter = Counter()
    for corpus in ("disaster", "tatoeba"):
        occ, docs, pocc, pdocs, total = w1.count(sets[f"{corpus}_train"])
        ok = {w for w, n in occ.items()
              if docs[w] >= MIN_DOCS and w1.eligible(w) and w not in SPLIT
              and (docs[w] >= RARE_DOCS or ascii_lower(w) in known)}
        lower_ok = {w for w in ok if w == ascii_lower(w)}
        for w in ok:
            n = occ[w]
            full = n * (len(w.encode("utf-8")) + 1 + HEADER - 2) / total
            low = ascii_lower(w)
            if w == low:
                word_score[w] += full
                for form in sorted({cap(w), ascii_upper(w)} - {w}):   # fixed order: float sums are reproducible
                    if occ.get(form):
                        word_score[w] += occ[form] * (len(form) + 1 + HEADER - 4) / total
            elif low in lower_ok and w in (cap(low), ascii_upper(low)):
                word_score[w] += n * 2 / total
            else:
                word_score[w] += full
        for p, n in pocc.items():
            if pdocs[p] >= MIN_DOCS and w1.eligible(p) and all(x in ok for x in p.split(" ")):
                phrase_score[p] += n * 2 / total
    return dict(word_score), dict(phrase_score)


def make_entries(word_score: dict[str, float], phrase_score: dict[str, float], fill: list[str], size: int = SIZE,
                 fill_scowl: int = FILL_SCOWL) -> list[str]:
    entries = list(SPLIT)
    sar = [w for w in w1.sar_words() if w not in entries]
    taken = set(entries) | set(sar)
    room = size - len(entries) - len(sar) - fill_scowl
    ranked = sorted([(s, w, False) for w, s in word_score.items() if s > 0]
                    + [(s, p, True) for p, s in phrase_score.items() if s > 0], key=lambda x: (-x[0], x[1]))
    chosen: list[tuple[float, str]] = []
    for score, text, phrase in ranked:
        if len(chosen) >= room:
            break
        if text in taken:
            continue
        if phrase and not all(x in taken for x in text.split(" ")):
            continue
        taken.add(text)
        chosen.append((score, text))
    extra = []
    for w in fill:
        if len(extra) >= fill_scowl:
            break
        if w not in taken and w1.eligible(w) and not any(c in SPLIT or c in " \t" for c in w):
            taken.add(w)
            extra.append(w)
    score = {**{w: word_score.get(w, 0.0) for w in sar}, **{t: s for s, t in chosen}}
    body = [t for _, t in chosen] + sar
    body.sort(key=lambda t: (-score.get(t, 0.0), t))
    out = entries + body + extra
    if len(out) > size:
        raise ValueError("too many entries")
    return out


def to_bytes(entries: list[str]) -> bytes:
    WordDict.from_entries(entries)
    return ("\n".join(entries) + "\n").encode("utf-8")


# ---------------------------------------------------------------- measuring

def lean_size(text: str, d: WordDict) -> int:
    data = encode_words(text, d)
    assert decode_words(data, d) == text, text
    return 2 + len(data)


def envelope_words(text: str, d: WordDict, version: int) -> int:
    return w1.envelope({"words": encode_words(text, d), "words_dict": version})


def n_words(text: str) -> int:
    return sum(1 for p in split_pieces(text)[1] if WORD_RE.search(p))


def evaluate(messages: list[str], d1: WordDict, d2: WordDict) -> dict:
    """Sizes of every form and of the two send rules: 2.1 (DEFLATE >= 3 B saved, else words v1 in the Envelope when
    smaller) and 2.2 (strictly smallest of plain, DEFLATE, words v2 Envelope, compact frame v2; plain on a tie)."""
    msgs = [m for m in messages if len(m.encode("utf-8")) <= MAX_INPUT]
    r = {"n": len(msgs), "left_out": len(messages) - len(msgs), "utf8": 0, "words": 0, "hits1": 0, "hits2": 0,
         "env1": 0, "lean2": 0, "old": 0, "new": 0, "old_chosen": Counter(), "new_chosen": Counter(),
         "fit": Counter(), "new_saving": [], "old_saving": [], "lean_le_plain": 0}
    for text in msgs:
        u = len(text.encode("utf-8"))
        de = w1.deflate_size(text)
        e1 = envelope_words(text, d1, 1)
        e2 = envelope_words(text, d2, 2)
        ln = lean_size(text, d2)
        r["utf8"] += u
        r["env1"] += e1
        r["lean2"] += ln
        r["lean_le_plain"] += ln < u
        r["words"] += n_words(text)
        for p in split_pieces(text)[1]:
            if WORD_RE.search(p):
                r["hits1"] += match(p, d1) is not None
                r["hits2"] += match(p, d2) is not None
        # 2.1 rule (textmode before this change)
        old = ("deflate", de) if de <= PACKET and de <= u - 3 else ("plain", u)
        lossless = old[0] == "deflate" or u <= PACKET
        if e1 <= PACKET and (e1 < old[1] or not lossless):
            old = ("words", e1)
        # 2.2 rule
        packed = [(s, i, f) for i, (f, s) in enumerate((("lean", ln), ("words", e2), ("deflate", de))) if s <= PACKET]
        best = min(packed) if packed else None
        new = (best[2], best[0]) if best and (best[0] < u or u > PACKET) else ("plain", u)
        r["old"] += old[1]
        r["new"] += new[1]
        r["old_chosen"][old[0]] += 1
        r["new_chosen"][new[0]] += 1
        r["fit"]["plain"] += u <= PACKET
        r["fit"]["old"] += old[1] <= PACKET
        r["fit"]["new"] += new[1] <= PACKET
        r["old_saving"].append(1 - old[1] / u)
        r["new_saving"].append(1 - new[1] / u)
    return r


def pct(a: float) -> str:
    return f"{100 * a:.1f} %"


# ---------------------------------------------------------------- commands

def cmd_select(sets, known, fill) -> None:
    ws, ps = candidates(sets, known)
    print(f"candidates: {sum(1 for s in ws.values() if s > 0)} words, {len(ps)} phrases")
    d1 = textwords.dictionary(1)
    print("| variant | entries | disaster val: lean bytes / UTF-8 | word hits | tatoeba val: lean / UTF-8 | word hits |"
          " file | deflated |")
    print("|---|---|---|---|---|---|---|---|")
    for label, fill_n in (("ranked only", 0), ("ranked + 5k SCOWL", 5000), ("ranked + 15k SCOWL", 15000)):
        entries = make_entries(ws, ps, fill, SIZE, fill_n)
        d2 = WordDict.from_entries(entries)
        raw = to_bytes(entries)
        cells = []
        for corpus in ("disaster", "tatoeba"):
            r = evaluate(sets[f"{corpus}_validation"], d1, d2)
            cells += [pct(r["lean2"] / r["utf8"]), pct(r["hits2"] / r["words"])]
        print(f"| {label} | {len(entries)} | {' | '.join(cells)} | {len(raw)} B | {len(zlib.compress(raw, 9))} B |",
              flush=True)


def build(sets, known, fill) -> bytes:
    ws, ps = candidates(sets, known)
    return to_bytes(make_entries(ws, ps, fill))


def cmd_build(sets, known, fill, write: bool, check: bool) -> int:
    data = build(sets, known, fill)
    entries = data.decode().splitlines()
    sha = hashlib.sha256(data).hexdigest()
    phrases = sum(1 for e in entries if " " in e)
    print(f"{len(entries)} entries ({phrases} two-word phrases), {len(data)} bytes, "
          f"{len(zlib.compress(data, 9))} bytes deflated, SHA-256 {sha}")
    if write:
        DICT_FILE.write_bytes(data)
        print(f"wrote {DICT_FILE}")
    if check:
        same = DICT_FILE.read_bytes() == data
        print("matches the frozen file" if same else "DIFFERS from the frozen file")
        return 0 if same else 1
    return 0


def cmd_measure(sets) -> None:
    d1, d2 = textwords.dictionary(1), textwords.dictionary(2)
    raw1, raw2 = w1.DICT_FILE.read_bytes(), DICT_FILE.read_bytes()
    print(f"dictionary 1: {len(d1.entries)} entries, {len(raw1)} B, {len(zlib.compress(raw1, 9))} B deflated")
    print(f"dictionary 2: {len(d2.entries)} entries, {len(raw2)} B, {len(zlib.compress(raw2, 9))} B deflated\n")
    rows = (("Conversational (Tatoeba test)", "tatoeba_test"), ("Disaster (test split)", "disaster_test"))
    res = {label: evaluate(sets[key], d1, d2) for label, key in rows}
    print("| | " + " | ".join(label for label, _ in rows) + " |")
    print("|---|" + "---|" * len(rows))

    def row(name, fn):
        print(f"| {name} | " + " | ".join(fn(res[label]) for label, _ in rows) + " |")

    row("Messages (up to 400 B)", lambda r: str(r["n"]))
    row("Words in them", lambda r: str(r["words"]))
    row("Typed text (UTF-8)", lambda r: f"{r['utf8']} B")
    row("Words found in the dictionary: v1 / v2", lambda r: f"{pct(r['hits1'] / r['words'])} / {pct(r['hits2'] / r['words'])}")
    row("Words v1 in the Envelope (2.1, always)", lambda r: f"{r['env1']} B ({r['env1'] / r['words']:.2f} B/word)")
    row("Compact frame v2 (2.2, always)", lambda r: f"{r['lean2']} B ({r['lean2'] / r['words']:.2f} B/word)")
    row("Typed text per word", lambda r: f"{r['utf8'] / r['words']:.2f} B/word")
    row("Sent, 2.1 rule (DEFLATE / words v1 / plain)", lambda r: f"{r['old']} B, saved {pct(1 - r['old'] / r['utf8'])}")
    row("Sent, 2.2 rule (smallest of all)", lambda r: f"{r['new']} B, saved {pct(1 - r['new'] / r['utf8'])}")
    row("Median saving per message: 2.1 / 2.2",
        lambda r: f"{pct(statistics.median(r['old_saving']))} / {pct(statistics.median(r['new_saving']))}")
    row("Sent compact (not plain): 2.1 / 2.2",
        lambda r: f"{pct(1 - r['old_chosen']['plain'] / r['n'])} / {pct(1 - r['new_chosen']['plain'] / r['n'])}")
    row("2.2 forms: compact frame / words Envelope / DEFLATE / plain",
        lambda r: " / ".join(pct(r["new_chosen"][f] / r["n"]) for f in ("lean", "words", "deflate", "plain")))
    row("Fits one packet (200 B): typed / 2.1 / 2.2",
        lambda r: f"{pct(r['fit']['plain'] / r['n'])} / {pct(r['fit']['old'] / r['n'])} / {pct(r['fit']['new'] / r['n'])}")
    for label, _ in rows:
        print(f"  ({label}: {res[label]['left_out']} messages over {MAX_INPUT} B left out)", file=sys.stderr)
    for key in ("tatoeba_validation", "tatoeba_test", "disaster_validation", "disaster_test"):
        for text in sets[key]:
            if len(text.encode("utf-8")) <= textwords.MAX_TEXT_BYTES:
                assert decode_words(encode_words(text, d2), d2) == text
    print("\nround trip exact on every held-out message (dictionary 2)", file=sys.stderr)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=("select", "build", "measure"))
    ap.add_argument("--disaster", type=Path, required=True)
    ap.add_argument("--tatoeba", type=Path, required=True)
    ap.add_argument("--scowl", type=Path, required=True, help="the `final` folder of SCOWL 2020.12.07")
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    sets = w1.load(args.disaster, args.tatoeba)
    print("sets: " + ", ".join(f"{k} {len(v)}" for k, v in sets.items()), file=sys.stderr)
    known, fill = load_scowl(args.scowl)
    if args.command == "select":
        cmd_select(sets, known, fill)
    elif args.command == "build":
        return cmd_build(sets, known, fill, args.write, args.check)
    else:
        cmd_measure(sets)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
