#!/usr/bin/env python3
"""Build, choose and measure the word dictionary for compact texts (spec/SPEC.md section 3.6, docs/COMPACT_TEXT.md).

The corpora are NOT in the repo (real people's messages). Download them into a folder outside the repo first:
  disaster: https://github.com/rmunro/disaster_response_messages  (CC BY 4.0)  -> disaster_response_{training,validation,test}.csv
  tatoeba:  https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2  (CC BY 2.0 FR) -> unpack

    python -I spec/tools/words_dict.py select  --disaster DIR --tatoeba eng_sentences.tsv   # sizes/variants on validation
    python -I spec/tools/words_dict.py build   --disaster DIR --tatoeba eng_sentences.tsv --check   # rebuild, compare
    python -I spec/tools/words_dict.py build   ... --write                                    # only for a NEW version
    python -I spec/tools/words_dict.py measure --disaster DIR --tatoeba eng_sentences.tsv   # test-set table

Steps (deterministic, no randomness; the "seed" is the salt of the split hash):
 1. Disaster set: the English `message` column. Its own split column: train builds, validation chooses, test reports.
    Tatoeba: English sentences, split by SHA-256(SALT + normalised text): 90 % train, 5 % validation, 5 % test.
    Normalised = lower case, whitespace collapsed. Duplicates are dropped inside a set, and a validation or test
    message whose normalised text is in any training set (or validation, for test) is dropped.
 2. Pieces are counted on the training sets exactly as the codec splits text (textwords.split_pieces).
 3. A piece may become an entry only when it is printable ASCII, seen in at least MIN_DOCS distinct training
    messages of one corpus, has no run of 5 or more digits and no '@', 'http' or 'www' (no phone numbers, e-mail or
    web addresses, no rare names).
 4. Score = expected bytes saved per corpus byte, both corpora weighted the same:
    occurrences x (UTF-8 bytes of the piece + 1 space + HEADER - 2 token bytes) / corpus bytes.
    HEADER = 2: a word missing from the dictionary between two tokens opens a literal run, whose 2-byte header it
    also costs. Without it ("+ 1 space - 2" alone) one-letter words such as "I" and "a" score 0 and are left out;
    `select` shows the held-out difference.
    Two-word phrases (both pieces entries, single space) save 2 more bytes per occurrence.
 5. Entries: the punctuation pieces first (they keep token runs going), then by score (ties: by text).
Licence of this script: Apache-2.0. The dictionary is derived from CC BY 4.0 and CC BY 2.0 FR data (attribution in
docs/COMPACT_TEXT.md).
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import re
import statistics
import sys
import zlib
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from rescuecodec.v2 import rescuecodec_pb2 as pb  # noqa: E402
from rescuecodec.v2 import textpack, textwords  # noqa: E402
from rescuecodec.v2.textwords import SPLIT, WordDict, encode_words, decode_words, match, split_pieces  # noqa: E402

SALT = "rescuecodec-words-v1"
HEADER = 2                       # literal-run header a dictionary word avoids (step 4)
MIN_DOCS = 3
SIZE = 8192                      # chosen with `select` (docs/COMPACT_TEXT.md)
PHRASE_SHARE = 0.1              # share of entries that may be two-word phrases (chosen with `select`)
DICT_FILE = ROOT / "spec" / "vocab" / "words-dict-v1.txt"
EVAL_ID = 1000                   # message id used for the measured packets (2-byte varint, like most real ids)
TATOEBA_VAL, TATOEBA_TEST = 3000, 5000
MAX_INPUT = 400                  # MAX_PACKED_TEXT_BYTES: longer messages cannot be typed, so they are not measured
PACKET = 200

PIECE_RE = re.compile(r'[ \t\n\r]+|[' + re.escape(SPLIT) + r']|[^ \t\n\r' + re.escape(SPLIT) + r']+')
BAD = re.compile(r"\d{5}|@|http|www", re.IGNORECASE)


def norm(text: str) -> str:
    return " ".join(text.lower().split())


# ---------------------------------------------------------------- corpora

def load(disaster: Path, tatoeba: Path) -> dict[str, list[str]]:
    sets: dict[str, list[str]] = {}
    for split, name in (("train", "training"), ("validation", "validation"), ("test", "test")):
        with open(disaster / f"disaster_response_{name}.csv", encoding="utf-8", newline="") as fh:
            sets[f"disaster_{split}"] = [row["message"].strip() for row in csv.DictReader(fh) if row["message"].strip()]
    tat: dict[str, list[tuple[str, str]]] = {"train": [], "validation": [], "test": []}
    with open(tatoeba, encoding="utf-8") as fh:
        for line in fh:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3 or parts[1] != "eng" or not parts[2].strip():
                continue
            text = parts[2].strip()
            h = hashlib.sha256((SALT + norm(text)).encode("utf-8")).hexdigest()
            bucket = int(h[:8], 16) % 100
            tat["train" if bucket < 90 else "validation" if bucket < 95 else "test"].append((h, text))
    sets["tatoeba_train"] = [t for _, t in tat["train"]]
    sets["tatoeba_validation"] = [t for _, t in sorted(tat["validation"])]
    sets["tatoeba_test"] = [t for _, t in sorted(tat["test"])]
    # dedupe inside sets and across: train keys win, then validation
    seen_train = {norm(t) for k in ("disaster_train", "tatoeba_train") for t in sets[k]}
    seen_val: set[str] = set()
    for corpus in ("disaster", "tatoeba"):
        for split in ("train", "validation", "test"):
            key = f"{corpus}_{split}"
            out, own = [], set()
            for t in sets[key]:
                n = norm(t)
                if n in own or (split != "train" and n in seen_train) or (split == "test" and n in seen_val):
                    continue
                own.add(n)
                out.append(t)
            if split == "validation":
                seen_val |= own
            sets[key] = out
    sets["tatoeba_validation"] = sets["tatoeba_validation"][:TATOEBA_VAL]
    sets["tatoeba_test"] = sets["tatoeba_test"][:TATOEBA_TEST]
    return sets


def sar_words() -> list[str]:
    import yaml

    out: list[str] = []

    def walk(node):
        if isinstance(node, dict):
            for key, value in node.items():
                if key in ("en", "de"):
                    for v in (value if isinstance(value, list) else list(value or {})):
                        out.extend(p for p in split_pieces(str(v))[1] if any(c.isalpha() for c in p))
                else:
                    walk(value)
        elif isinstance(node, list):
            for item in node:
                walk(item)

    walk(yaml.safe_load((ROOT / "spec" / "vocab" / "sar.yaml").read_text(encoding="utf-8")))
    return list(dict.fromkeys(out))


# ---------------------------------------------------------------- candidates

def count(messages: list[str]) -> tuple[Counter, Counter, Counter, Counter, int]:
    occ, docs, pocc, pdocs = Counter(), Counter(), Counter(), Counter()
    total = 0
    for text in messages:
        total += len(text.encode("utf-8"))
        toks = PIECE_RE.findall(text)
        pieces = [t for t in toks if not t[0] in " \t\n\r"]
        occ.update(pieces)
        docs.update(set(pieces))
        pairs = []
        for a, gap, b in zip(toks, toks[1:], toks[2:]):
            if gap == " " and a[0] not in " \t\n\r" and b[0] not in " \t\n\r" and a not in SPLIT and b not in SPLIT:
                pairs.append(a + " " + b)
        pocc.update(pairs)
        pdocs.update(set(pairs))
    return occ, docs, pocc, pdocs, total


def eligible(piece: str) -> bool:
    return piece.isascii() and piece.isprintable() and not BAD.search(piece)


def candidates(sets: dict[str, list[str]], header: int = HEADER) -> tuple[list[tuple[float, str]], list[tuple[float, str]]]:
    words: Counter = Counter()
    phrases: Counter = Counter()
    for corpus in ("disaster", "tatoeba"):
        occ, docs, pocc, pdocs, total = count(sets[f"{corpus}_train"])
        for w, n in occ.items():
            if docs[w] >= MIN_DOCS and eligible(w) and w not in SPLIT:
                words[w] += n * (len(w.encode("utf-8")) + 1 + header - 2) / total
        for p, n in pocc.items():
            if pdocs[p] >= MIN_DOCS and eligible(p):
                phrases[p] += n * 2 / total
    rank_w = sorted(((s, w) for w, s in words.items() if s > 0), key=lambda x: (-x[0], x[1]))
    rank_p = sorted(((s, p) for p, s in phrases.items()), key=lambda x: (-x[0], x[1]))
    return rank_w, rank_p


def make_entries(rank_w, rank_p, size: int, phrase_share: float, sar: bool) -> list[str]:
    entries = list(SPLIT)
    extra = [w for w in sar_words() if w not in entries] if sar else []
    extra_set = set(extra)
    room = size - len(entries) - len(extra)
    n_phr = int(room * phrase_share)
    words: list[str] = []
    for _, w in rank_w:
        if len(words) >= room - n_phr:
            break
        if w not in extra_set:
            words.append(w)
    known = set(words) | extra_set
    phrases = [p for _, p in rank_p if all(x in known for x in p.split(" "))][:n_phr]
    score = {w: sc for sc, w in rank_w}
    score.update({p: sc for sc, p in rank_p})
    chosen = words + phrases + extra
    chosen.sort(key=lambda w: (-score.get(w, 0.0), w))
    return entries + chosen


# ---------------------------------------------------------------- measuring

def envelope(text_fields: dict) -> int:
    env = pb.Envelope(id=EVAL_ID)
    for k, v in text_fields.items():
        setattr(env.text, k, v)
    return len(env.SerializeToString(deterministic=True))


def deflate_size(text: str) -> int:
    return envelope({"deflate": textpack.pack(text, 1), "dict": 1})


def words_size(text: str, d: WordDict) -> int:
    data = encode_words(text, d)
    assert decode_words(data, d) == text, text
    return envelope({"words": data, "words_dict": 1})


def coverage(messages: list[str], d: WordDict) -> float:
    hit = total = 0
    for text in messages:
        for p in split_pieces(text)[1]:
            if p in SPLIT:
                continue
            total += 1
            hit += match(p, d) is not None
    return hit / total if total else 0.0


def evaluate(messages: list[str], d: WordDict, full: bool = False) -> dict:
    msgs = [m for m in messages if len(m.encode("utf-8")) <= MAX_INPUT]
    res = {"n": len(msgs), "excluded_over_400": len(messages) - len(msgs), "utf8": 0, "plain": 0, "deflate": 0,
           "words": 0, "best": 0, "best_nowords": 0, "fit": Counter(), "chosen": Counter(), "savings": []}
    for text in msgs:
        u = len(text.encode("utf-8"))
        de = deflate_size(text)
        wo = words_size(text, d)
        res["utf8"] += u
        res["plain"] += u
        res["deflate"] += de
        res["words"] += wo
        for name, size in (("plain", u), ("deflate", de), ("words", wo)):
            res["fit"][name] += size <= PACKET
        # existing path on a RescueCodec-only chat: DEFLATE when it fits and saves >= 3 B, else plain
        existing = ("deflate", de) if de <= PACKET and de <= u - 3 else ("plain", u)
        existing_lossless = existing[0] == "deflate" or u <= PACKET
        best = existing
        if wo <= PACKET and (wo < existing[1] or not existing_lossless):
            best = ("words", wo)
        res["best"] += best[1]
        res["best_nowords"] += existing[1]
        res["chosen"][best[0]] += 1
        res["fit"]["best"] += best[1] <= PACKET and (best[0] != "plain" or u <= PACKET)
        res["savings"].append(1 - best[1] / u)
    res["coverage"] = coverage(msgs, d)
    res["median_saving"] = statistics.median(res["savings"]) if res["savings"] else 0.0
    if not full:
        res.pop("savings")
    return res


def pct(a: float) -> str:
    return f"{100 * a:.1f} %"


# ---------------------------------------------------------------- commands

def build_entries(sets, size=SIZE, phrase_share=PHRASE_SHARE, sar=True) -> list[str]:
    rank_w, rank_p = candidates(sets)
    return make_entries(rank_w, rank_p, size, phrase_share, sar)


def to_bytes(entries: list[str]) -> bytes:
    WordDict.from_entries(entries)          # checks the rules
    return ("\n".join(entries) + "\n").encode("utf-8")


def cmd_select(sets) -> None:
    rank_w, rank_p = candidates(sets)
    print(f"candidates: {len(rank_w)} words, {len(rank_p)} phrases")
    old_w, old_p = candidates(sets, header=0)
    print("| size | phrases | sar.yaml | score | disaster val: words / UTF-8 | coverage | tatoeba val: words / UTF-8 |"
          " coverage | file | deflated |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for size, share, sar, header in ((8192, 0.1, True, 0), (2048, 0.1, True, 2), (4096, 0, True, 2),
                                     (4096, 0.1, True, 2), (8192, 0, False, 2), (8192, 0, True, 2),
                                     (8192, 0.1, True, 2), (8192, 0.25, True, 2), (16384, 0.1, True, 2)):
        entries = make_entries(*((rank_w, rank_p) if header else (old_w, old_p)), size, share, sar)
        d = WordDict.from_entries(entries)
        raw = to_bytes(entries)
        cells = []
        for corpus in ("disaster", "tatoeba"):
            r = evaluate(sets[f"{corpus}_validation"], d)
            cells += [pct(r["words"] / r["utf8"]), pct(r["coverage"])]
        print(f"| {size} | {share} | {sar} | +{header} | {' | '.join(cells)} | {len(raw)} B |"
              f" {len(zlib.compress(raw, 9))} B |", flush=True)


def cmd_build(sets, write: bool, check: bool) -> int:
    data = to_bytes(build_entries(sets))
    sha = hashlib.sha256(data).hexdigest()
    print(f"{len(data.decode().splitlines())} entries, {len(data)} bytes, SHA-256 {sha}")
    if write:
        DICT_FILE.write_bytes(data)
        print(f"wrote {DICT_FILE}")
    if check:
        same = DICT_FILE.read_bytes() == data
        print("matches the frozen file" if same else "DIFFERS from the frozen file")
        return 0 if same else 1
    return 0


def cmd_measure(sets) -> None:
    d = textwords.dictionary(1)
    raw = DICT_FILE.read_bytes()
    print(f"dictionary: {len(d.entries)} entries, {len(raw)} B, {len(zlib.compress(raw, 9))} B deflated (APK zip)\n")
    print("| Held-out set | Messages | UTF-8 (normal text) | DEFLATE v1 packet | Word tokens packet | Best of (sent) |"
          " Saved vs UTF-8 | Median saving per message | Sent as plain text | Sent as words | Sent as DEFLATE |"
          " Word coverage | Fits one packet: normal / words / best |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    rows = (("Conversational (Tatoeba test)", "tatoeba_test"), ("Disaster, all (test split)", "disaster_test"))
    for label, key in rows:
        r = evaluate(sets[key], d)
        n = r["n"]
        print(f"| {label} | {n} | {r['utf8']} B | {r['deflate']} B | {r['words']} B | {r['best']} B |"
              f" {pct(1 - r['best'] / r['utf8'])} | {pct(r['median_saving'])} | {pct(r['chosen']['plain'] / n)} |"
              f" {pct(r['chosen']['words'] / n)} | {pct(r['chosen']['deflate'] / n)} | {pct(r['coverage'])} |"
              f" {pct(r['fit']['plain'] / n)} / {pct(r['fit']['words'] / n)} / {pct(r['fit']['best'] / n)} |")
        print(f"  ({label}: {r['excluded_over_400']} messages over {MAX_INPUT} B left out; without words the best-of"
              f" is {r['best_nowords']} B = {pct(1 - r['best_nowords'] / r['utf8'])} saved;"
              f" words alone {pct(1 - r['words'] / r['utf8'])}, DEFLATE alone {pct(1 - r['deflate'] / r['utf8'])})",
              file=sys.stderr)
    # exactness on every held-out message (also the long ones)
    for key in ("tatoeba_validation", "tatoeba_test", "disaster_validation", "disaster_test"):
        for text in sets[key]:
            if len(text.encode("utf-8")) <= textwords.MAX_TEXT_BYTES:
                assert decode_words(encode_words(text, d), d) == text
    print("\nround trip exact on every held-out message", file=sys.stderr)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=("select", "build", "measure"))
    ap.add_argument("--disaster", type=Path, required=True)
    ap.add_argument("--tatoeba", type=Path, required=True)
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    sets = load(args.disaster, args.tatoeba)
    print("sets: " + ", ".join(f"{k} {len(v)}" for k, v in sets.items()), file=sys.stderr)
    if args.command == "select":
        cmd_select(sets)
    elif args.command == "build":
        return cmd_build(sets, args.write, args.check)
    else:
        cmd_measure(sets)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
