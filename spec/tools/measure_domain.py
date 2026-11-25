#!/usr/bin/env python3
"""Measure the domain frame (0x03, SPEC 3.8) on held-out text, through the real sender rule.

    python -I spec/tools/measure_domain.py --corpus DIR [--apk dist/RescueCodec-debug.apk]

DIR is the corpus folder of spec/tools/domain_vocab.py (disaster/, tatoeba/, the PDF and HTML sources). It stays
outside the repo; only the totals this script prints are committed (docs/COMPACT_TEXT.md, "Domain vocabularies").

Text sets. Every text is at most 400 bytes (the most a chat that packs takes) and none was used to build a list:
  - one set per domain: the held-out units of domain_vocab.py (20 % of the blocks of every source, plus our team
    messages), minus any text that is a build unit of ANY domain;
  - conversational: the Tatoeba test split of words_dict.py (never used for word dictionary 2), minus build units;
  - disaster messages: the disaster test split of words_dict.py, minus build units;
  - our team messages: spec/tools/text-corpus.json, English (never used to build a domain list).

Forms, in bytes of the whole payload:
  plain     the text as a normal Meshtastic text (UTF-8)
  0x02      the compact frame with word dictionary 2 (SPEC 3.7), always
  domain    the domain frame of the set's own list (SPEC 3.8), always; the general sets use Emergency
  general   what goes out with the setting "General": rescuecodec.engine.textmode.choose_form, a chat that packs,
            compact on, a 2-byte message id, no position (spec 2.2 rule: plain, DEFLATE, words, 0x02)
  auto      the same with the setting "Auto" (spec 2.3 rule: also every domain frame)
  pinned    the same with the setting pinned to the set's own list (domain sets only)
A text that does not fit one packet in any form is cut by choose_form; such a text is counted at its smallest
complete size (plain, 0x02 or a domain frame), not at the size of the cut text. Words = whitespace-separated.
"Words in the list" = words written as list codes in the set's own domain frame (counted with the bit model
of domain_vocab.py, which gives the same sizes as the reference encoder).
Every payload that goes out is decoded again and must give the exact text.
Licence of this script: Apache-2.0.
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
import zipfile
import zlib
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "spec" / "tools"))

import domain_vocab as dv  # noqa: E402
import words_dict as w1  # noqa: E402
from rescuecodec.engine.textmode import choose_form  # noqa: E402
from rescuecodec.v2 import codes as C  # noqa: E402
from rescuecodec.v2 import decode_any, textdomain, textwords  # noqa: E402

PACKET = C.MAX_ENCODE_BYTES             # 200 B: one radio message
MAX_TEXT = C.MAX_PACKED_TEXT_BYTES      # 400 B: what a chat that packs takes
EVAL_ID = w1.EVAL_ID                    # 1000: a 2-byte message id in the Envelope forms
DOMAINS = list(dv.DOMAINS)              # emergency, sar, fire, hiking, medical (id order)


@dataclass
class Totals:
    texts: int = 0
    words: int = 0
    plain: int = 0
    lean: int = 0
    domain: int = 0
    general: int = 0
    auto: int = 0
    pinned: int = 0
    w_vocab: int = 0
    w_all: int = 0
    picks: Counter = field(default_factory=Counter)
    picks_vocab: Counter = field(default_factory=Counter)
    fit_plain: int = 0
    fit_general: int = 0
    fit_auto: int = 0
    smaller: int = 0                    # auto strictly smaller than general
    saving_auto: list = field(default_factory=list)

    def add(self, r: dict) -> None:
        for k in ("words", "plain", "lean", "domain", "general", "auto", "pinned", "w_vocab", "w_all", "fit_plain",
                  "fit_general", "fit_auto", "smaller"):
            setattr(self, k, getattr(self, k) + r[k])
        self.texts += 1
        self.picks[r["pick"]] += 1
        if r["vocab"]:
            self.picks_vocab[r["vocab"]] += 1
        self.saving_auto.append(1 - r["auto"] / r["plain"])


def _words(text: str) -> int:
    return len(text.split())


def _frame(text: str, vid: int) -> int:
    """Domain frame bytes, not capped at 200 (0x03 + id + the stream)."""
    data = textdomain.encode_stream(text, vid)
    assert textdomain.decode_stream(data, vid) == text, text
    return 2 + len(data)


def _lean(text: str) -> int:
    return 2 + len(textwords.encode_words(text, 2))


def _sent(text: str, vocab: str, complete: int) -> tuple[int, bool, object]:
    """(bytes counted, fits one packet, TextForm) of the real sender rule."""
    f = choose_form({"id": EVAL_ID, "text": {"text": text}}, True, "", compact=True, vocab=vocab)
    if f.cut:
        return complete, False, f
    got = decode_any(f.payload) if f.packed else {"text": {"text": f.payload.decode("utf-8")}}
    assert got["text"]["text"] == text, (vocab, text)
    return len(f.payload), True, f


def measure_one(text: str, own: str | None, dd, d2) -> dict:
    """Sizes of one text in every form and setting (see the module docstring)."""
    own_id = textdomain.vocab_id(own or "emergency")
    plain = len(text.encode("utf-8"))
    lean = _lean(text)
    frames = {vid: _frame(text, vid) for vid in range(1, 6)}
    g, g_fit, _ = _sent(text, "general", min(plain, lean))
    a, a_fit, fa = _sent(text, "auto", min(plain, lean, *frames.values()))
    p = _sent(text, own, min(plain, lean, frames[own_id]))[0] if own else 0
    enc = dv.encode_bits(text, dd, d2, 10)
    if fa.form == "domain":
        pick = "domain_own" if own and fa.vocab == own else "domain_other"
    else:
        pick = fa.form
    return {"words": _words(text), "plain": plain, "lean": lean, "domain": frames[own_id], "general": g, "auto": a,
            "pinned": p, "w_vocab": enc.words_domain, "w_all": enc.words_domain + enc.words_v2 + enc.words_literal,
            "fit_plain": int(plain <= PACKET), "fit_general": int(g_fit), "fit_auto": int(a_fit),
            "smaller": int(a < g), "pick": pick, "vocab": fa.vocab if fa.form == "domain" else None}


def measure(items: list[tuple[str, str]], own: str | None) -> tuple[Totals, dict[str, Totals]]:
    """Totals of a set, and by kind of text ("documents": manuals and web pages, "messages": real or example
    messages and our team messages)."""
    dd = textdomain.vocabulary(textdomain.vocab_id(own or "emergency"))
    d2 = textwords.dictionary(2)
    total, by_kind = Totals(), {"documents": Totals(), "messages": Totals()}
    for text, kind in items:
        r = measure_one(text, own, dd, d2)
        total.add(r)
        by_kind[kind].add(r)
    return total, by_kind


DOC_KINDS = ("pdf", "html", "mplus")


def load_sets(corpus: Path) -> dict[str, tuple[list[tuple[str, str]], str | None]]:
    """name -> ([(text, kind)], own domain or None). kind: "documents" or "messages"."""
    kinds = {s.id: "documents" if s.kind in DOC_KINDS else "messages" for s in dv.SOURCES}
    build: set[str] = set()
    held: dict[str, list[tuple[str, str]]] = {}
    for d in DOMAINS:
        units = dv.domain_units(d, corpus)
        build |= {dv._norm(u.text) for u in units if not u.held_out}
        held[d] = [(u.text, kinds[u.source]) for u in units if u.held_out]
    sets: dict[str, tuple[list[tuple[str, str]], str | None]] = {}
    dropped: dict[str, int] = {}

    def keep(name: str, items: list[tuple[str, str]], own: str | None) -> None:
        out = [(x, k) for x, k in items
               if x.strip() and len(x.encode("utf-8")) <= MAX_TEXT and dv._norm(x) not in build]
        dropped[name] = len(items) - len(out)
        sets[name] = (out, own)

    for d in DOMAINS:
        keep(d, held[d], d)
    w = w1.load(corpus / "disaster", corpus / "tatoeba" / "eng_sentences.tsv")
    keep("conversational", [(x, "messages") for x in w["tatoeba_test"]], None)
    keep("disaster", [(x, "messages") for x in w["disaster_test"]], None)
    team = json.loads((ROOT / "spec" / "tools" / "text-corpus.json").read_text(encoding="utf-8"))
    keep("team", [(x, "messages") for part in ("tune", "holdout") for x in team[part]["en"]], None)
    print("left out (over 400 B or a build unit of a domain): "
          + ", ".join(f"{k} {v}" for k, v in dropped.items()), file=sys.stderr)
    return sets


LABELS = {"emergency": "Emergency", "sar": "Search & Rescue", "fire": "Firefighting", "hiking": "Hiking / Mountain",
          "medical": "Medical / First aid", "conversational": "Conversational (Tatoeba test)",
          "disaster": "Disaster messages (test split)", "team": "Our team messages (English)"}


def pct(x: float) -> str:
    return f"{100 * x:.1f} %"


def report(res: dict[str, Totals], kinds: dict[str, dict[str, Totals]], apk: Path | None) -> None:
    print("\n### Bytes per word (saving against the typed text)\n")
    print("| Text set | Texts | Words | Typed | 0x02 always | Domain frame always | Sent, General | Sent, Auto "
          "| Auto vs General |")
    print("|---|---|---|---|---|---|---|---|---|")
    for name, t in res.items():
        def bw(n: int) -> str:
            return f"{n / t.words:.2f} ({pct(1 - n / t.plain)})"
        print(f"| {LABELS[name]} | {t.texts} | {t.words} | {t.plain / t.words:.2f} | {bw(t.lean)} | {bw(t.domain)} "
              f"| {bw(t.general)} | {bw(t.auto)} | {pct(1 - t.auto / t.general)} |")
    print("\n### What Auto sends, and one packet\n")
    print("| Text set | Words in the list | Own domain frame | Other domain frame | 0x02 | DEFLATE | Plain "
          "| Smaller than General | Median saving | Fits 200 B: typed / General / Auto | Pinned vs General |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for name, t in res.items():
        n = t.texts
        pin = pct(1 - t.pinned / t.general) if t.pinned else "-"
        own = pct(t.picks["domain_own"] / n) if name in DOMAINS else "-"
        other = t.picks["domain_other"]
        print(f"| {LABELS[name]} | {pct(t.w_vocab / max(1, t.w_all))} | {own} | {pct(other / n)} "
              f"| {pct(t.picks['lean'] / n)} | {pct(t.picks['deflate'] / n)} | {pct(t.picks['plain'] / n)} "
              f"| {pct(t.smaller / n)} | {pct(statistics.median(t.saving_auto))} "
              f"| {pct(t.fit_plain / n)} / {pct(t.fit_general / n)} / {pct(t.fit_auto / n)} | {pin} |")
        if t.picks["words"]:
            print(f"  ({LABELS[name]}: words in the Envelope {t.picks['words']})", file=sys.stderr)
    print("\n### Domain sets by kind of text: Auto against General\n")
    print("| Text set | Documents: texts, B/word General -> Auto, saved | Messages: texts, B/word General -> Auto, saved |")
    print("|---|---|---|")
    for name in DOMAINS:
        cells = []
        for kind in ("documents", "messages"):
            t = kinds[name][kind]
            cells.append(f"{t.texts}, {t.general / t.words:.2f} -> {t.auto / t.words:.2f}, {pct(1 - t.auto / t.general)}"
                         if t.texts else "-")
        print(f"| {LABELS[name]} | " + " | ".join(cells) + " |")
    print("\n### Which list Auto picked (domain frames only)\n")
    for name, t in res.items():
        print(f"- {LABELS[name]}: " + ", ".join(f"{d} {t.picks_vocab[d]}" for d in DOMAINS if t.picks_vocab[d]))
    dom = Totals()
    for name in DOMAINS:
        for k in ("texts", "words", "plain", "lean", "domain", "general", "auto", "pinned", "smaller"):
            setattr(dom, k, getattr(dom, k) + getattr(res[name], k))
    print(f"\nAll five domain sets: {dom.texts} texts, {dom.words} words; typed {dom.plain} B, General {dom.general} B, "
          f"Auto {dom.auto} B ({pct(1 - dom.auto / dom.general)} smaller than General, "
          f"{pct(1 - dom.auto / dom.plain)} smaller than typed); pinned {pct(1 - dom.pinned / dom.general)} vs General")
    print(f"Round trip exact: every payload that went out ({sum(t.texts for t in res.values())} texts, "
          "settings General, Auto and pinned) and every domain frame of the five lists.")
    print("\n### Vocabulary bytes\n")
    files = sorted((ROOT / "spec" / "vocab").glob("domain-*-v1.txt"))
    raw = sum(f.stat().st_size for f in files)
    z = sum(len(zlib.compress(f.read_bytes(), 9)) for f in files)
    print(f"- spec/vocab/domain-*-v1.txt: {len(files)} files, {raw} B, {z} B with zlib level 9")
    if apk and apk.exists():
        with zipfile.ZipFile(apk) as zf:
            infos = [i for i in zf.infolist() if "domain-" in i.filename and i.filename.endswith("-v1.txt")]
            d2 = [i for i in zf.infolist() if i.filename.endswith("words-dict-v2.txt")]
        print(f"- In the APK ({apk.name}): {len(infos)} entries, {sum(i.file_size for i in infos)} B, "
              f"{sum(i.compress_size for i in infos)} B compressed in the zip"
              + (f" (word dictionary 2: {d2[0].compress_size} B compressed)" if d2 else ""))
        for i in infos:
            print(f"    {i.filename}: {i.file_size} B, {i.compress_size} B in the zip")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--corpus", required=True, type=Path)
    ap.add_argument("--apk", type=Path, help="an APK built from this tree, for the vocabulary bytes in it")
    ap.add_argument("--limit", type=int, default=0, help="only N texts of every set, evenly spread (quick runs)")
    a = ap.parse_args()
    sets = load_sets(a.corpus)
    res: dict[str, Totals] = {}
    kinds: dict[str, dict[str, Totals]] = {}
    for name, (items, own) in sets.items():
        if a.limit:
            items = items[::max(1, len(items) // a.limit)][:a.limit]
        res[name], kinds[name] = measure(items, own)
        print(f"measured {name}: {len(items)} texts", file=sys.stderr)
    report(res, kinds, a.apk)
    return 0


if __name__ == "__main__":
    sys.exit(main())
