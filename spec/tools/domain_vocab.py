#!/usr/bin/env python3
"""Build, choose and measure the domain vocabularies of the 0x03 domain frame (8, 9 or 10-bit word codes).

A domain vocabulary is a small word list for one kind of team (Search & Rescue, Firefighting, Hiking / Mountain,
Medical / First aid, general Emergency). In the domain frame a word of that list costs N bits (N = 8, 9 or 10),
any other word of word dictionary 2 costs N + 16 bits (escape + 16-bit token), and everything else is an exact
literal. spec/vocab/domains.yaml has the frame layout, the control codes, the vocabulary ids, the SHA-256 of every
file, the sources with their licences, and the measured numbers this script prints.

Inputs (NOT in the repo; openly licensed text downloaded into a folder outside it; run with `python -I`).
The folder has one sub-folder per source, see SOURCES below and spec/vocab/domains.yaml for URLs and licences:
    DIR/disaster/disaster_response_{training,validation,test}.csv   (CC BY 4.0)
    DIR/tatoeba/eng_sentences.tsv                                   (CC BY 2.0 FR)
    DIR/<pdf source>/doc.txt          `pdftotext -q -enc UTF-8 doc.pdf doc.txt` (poppler 26.04, reading order)
    DIR/<html source>/pNNN_xxxxxx.txt + .url   one text block per line (p, li, h1-h5, td, dd, dt), URL beside it
    DIR/medlineplus/mplus_topics_*.xml (the MedlinePlus health-topic XML; only the public-domain summaries are read)

    python -I spec/tools/domain_vocab.py select --corpus DIR           # size x phrase table on held-out text
    python -I spec/tools/domain_vocab.py build  --corpus DIR [--check] # write (or compare) the frozen files

Steps (deterministic, no randomness):
 1. Units. Documents are cut into sentences (lines joined into paragraphs, bullets start a new one); messages and
    Tatoeba sentences are units as they are. Units of 3+ words and at most 400 bytes are kept.
 2. Split. A unit goes to held-out when sha256(source:block) % 100 < 20. A block is a page (HTML, MedlinePlus
    topic), 8 paragraphs of a PDF, or the message itself, so neighbouring sentences stay on one side. Duplicates
    (lower case, spaces collapsed) are dropped inside a domain, and a held-out unit equal to a build unit is
    dropped. Our own team messages (spec/tools/text-corpus.json) are held-out only; sar.yaml is build only.
 3. Score. Pieces are counted exactly as the codec splits text (case-sensitive). Saving of an entry, in bits per
    use: its fallback cost (escape + 16-bit token = N + 16 if word dictionary 2 has it, else a literal:
    N + 8 header + 8 per byte incl. one space) minus N. Capitalised / UPPER forms of an entry save the same minus
    the N-bit CAP / UPPER control; a capitalised form of its own ("The", "We") saves N per use; a 2- or 3-word
    phrase saves (words - 1) x N per use. Counts are divided by the source's build bytes and weighted (most
    sources 1), so no single big source decides alone.
 4. Privacy filter. An entry is printable ASCII, has no '@', 'http', 'www' and no run of 5+ digits, and is in at
    least 5 different build units. A piece word dictionary 2 does not have must also be in 2+ sources, or in 10+
    units of documents (not messages), so names and typos from real messages stay out.
 5. Select. One ranking by score; a phrase only after all its words, a capitalised form only after its lower-case
    word; at most PHRASE share of the entries are phrases. 2^N - 8 entries; the 8 top codes are controls.
 6. Choose. For every domain, N in (8, 9, 10) x PHRASE in (0, 10 %, 25 %) is encoded on the held-out units with
    the shortest-stream rule of SPEC 3.6.4 (bit costs) and the sender rule "strictly smallest of plain text,
    0x02 compact frame (word dictionary 2) and this domain frame, plain wins ties". The variant with the fewest
    bytes sent (mean over the domain's sources of sent / plain) wins; ties go to the smaller N.
Licence of this script: Apache-2.0.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import heapq
import html
import json
import math
import re
import sys
import unicodedata
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from rescuecodec.v2 import textwords  # noqa: E402
from rescuecodec.v2.textwords import LEFT_ATTACH, RIGHT_ATTACH, WordDict, encode_words, match, split_pieces  # noqa: E402

VOCAB_DIR = ROOT / "spec" / "vocab"
SIZES = (8, 9, 10)
PHRASE_SHARES = (0.0, 0.10, 0.25)
RESERVED = 8                      # top 8 codes of every code space are controls
CONTROLS = ("ESC16", "CAP", "UPPER", "GLUE", "LIT", "reserved", "reserved", "reserved")
HELD_OUT = 20                     # percent
PDF_BLOCK = 8                     # paragraphs per split block
MAX_UNIT = 400                    # bytes
MIN_UNITS = 5
RARE_UNITS_DOC = 10
TATOEBA_CAP = 6000                # sentences per domain (by hash order)
DISASTER_CAP = 12000              # messages per domain (by hash order)
FRAME_HEADER = 2                  # 0x03 + vocabulary id
V2 = 2

#: Vocabulary id (byte 1 of the frame) -> domain. The width N follows from the id (domains.yaml).
DOMAINS = {
    "emergency": {"id": 1, "label": "Emergency", "short": "Emergency"},
    "sar": {"id": 2, "label": "Search & Rescue", "short": "S&R"},
    "fire": {"id": 3, "label": "Firefighting", "short": "Fire"},
    "hiking": {"id": 4, "label": "Hiking / Mountain", "short": "Hiking"},
    "medical": {"id": 5, "label": "Medical / First aid", "short": "Medical"},
}


@dataclass
class Source:
    id: str
    kind: str                     # pdf, html, mplus, disaster, tatoeba, sar_yaml, team
    title: str
    url: str
    licence: str
    domains: dict                 # domain -> filter (regex on URL / category list / regex on text / True)
    weight: float = 1.0
    role: str = "both"            # both, build, eval
    retrieved: str = "2026-11-25"
    folder: str = ""


PD = "Public domain (U.S. federal government work, 17 U.S.C. 105)"
NWS_OUTDOOR = r"/safety/(lightning|cold|heat|winter|thunderstorm|wind|fog)"
SOURCES = [
    Source("disaster", "disaster", "Disaster Response Messages (Figure Eight / Robert Munro), English message column, "
           "PII-flagged messages left out", "https://github.com/rmunro/disaster_response_messages", "CC BY 4.0",
           {"emergency": ["related"], "sar": ["search_and_rescue", "missing_people"], "fire": ["fire"],
            "medical": ["medical_help", "medical_products", "hospitals"]}),
    Source("tatoeba", "tatoeba", "Tatoeba English sentences (eng_sentences.tsv), keyword-selected per domain",
           "https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2", "CC BY 2.0 FR",
           {"emergency": r"\b(emergency|help|danger\w*|evacuat\w*|flood\w*|earthquakes?|storms?|shelter|police|"
                         r"accidents?|disasters?|warning|safe|hurry|radio)\b",
            "sar": r"\b(rescue[ds]?|rescuers?|missing|search(ed|ing)?|lost|stranded|trapped|survivors?|"
                   r"helicopters?|find (him|her|them))\b",
            "fire": r"\b(fires?|firefighters?|fire ?m[ae]n|smoke|flames?|burn(s|ed|t|ing)?|extinguish\w*|blaze|"
                    r"wildfires?|hoses?)\b",
            "hiking": r"\b(hik(e|es|ed|ing|ers?)|trails?|mountains?|summit|climb(s|ed|ing|ers?)?|camp(s|ed|ing)?|"
                      r"tents?|backpacks?|hills?|peaks?|ridge|valley|forest|woods|cliffs?|snow|glaciers?|compass)\b",
            "medical": r"\b(doctors?|hospital|nurses?|ambulance|bleed\w*|blood|injur\w*|wound\w*|pain|hurts?|broken|"
                       r"fever|medicine|bandages?|breath\w*|unconscious|heart attack|sick|ill)\b"}),
    Source("sar_yaml", "sar_yaml", "RescueCodec spec/vocab/sar.yaml, English words", "spec/vocab/sar.yaml",
           "Apache-2.0 (our own)", {"sar": True}, weight=0.5, role="build"),
    Source("team", "team", "RescueCodec spec/tools/text-corpus.json, English team messages (tune + holdout)",
           "spec/tools/text-corpus.json", "Apache-2.0 (our own)",
           {"sar": True, "emergency": True}, role="eval"),
    Source("irpg2022", "pdf", "NWCG Incident Response Pocket Guide (IRPG), PMS 461, January 2022",
           "https://gacc.nifc.gov/gbcc/dispatch/ut-ubc/site-files/IRPG.pdf",
           "Public domain (the IRPG says NWCG publications are in the public domain)", {"fire": True}),
    Source("usfa_radio", "pdf", "U.S. Fire Administration, Voice Radio Communications Guide for the Fire Service",
           "https://www.usfa.fema.gov/downloads/pdf/publications/voice_radio_communications_guide_for_the_fire_service.pdf",
           PD, {"fire": True, "emergency": True}),
    Source("cert1", "pdf", "FEMA CERT Basic Training Participant Manual (Jan 2011), Unit 1 Disaster Preparedness",
           "https://www.cert-la.com/downloads/manuals/CERT_PM_Unit1_Jan2011.pdf", PD + "; copy of the FEMA manual",
           {"emergency": True}),
    Source("cert2", "pdf", "FEMA CERT Participant Manual (Jan 2011), Unit 2 Fire Safety and Utility Controls",
           "https://www.cert-la.com/downloads/manuals/CERT_PM_Unit2_Jan2011.pdf", PD + "; copy of the FEMA manual",
           {"fire": True}),
    Source("cert3", "pdf", "FEMA CERT Participant Manual (Jan 2011), Unit 3 Disaster Medical Operations, Part 1",
           "https://www.cert-la.com/downloads/manuals/CERT_PM_Unit3_Jan2011.pdf", PD + "; copy of the FEMA manual",
           {"medical": True}),
    Source("cert4", "pdf", "FEMA CERT Participant Manual (Jan 2011), Unit 4 Disaster Medical Operations, Part 2",
           "https://www.cert-la.com/downloads/manuals/CERT_PM_Unit4_Jan2011.pdf", PD + "; copy of the FEMA manual",
           {"medical": True}),
    Source("cert5", "pdf", "FEMA CERT Participant Manual (Jan 2011), Unit 5 Light Search and Rescue Operations",
           "https://www.cert-la.com/downloads/manuals/CERT_PM_Unit5_Jan2011.pdf", PD + "; copy of the FEMA manual",
           {"sar": True}),
    Source("cert6", "pdf", "FEMA CERT Participant Manual (Jan 2011), Unit 6 CERT Organization",
           "https://www.cert-la.com/downloads/manuals/CERT_PM_Unit6_Jan2011.pdf", PD + "; copy of the FEMA manual",
           {"emergency": True}),
    Source("ics_glossary", "pdf", "FEMA Emergency Management Institute, ICS Glossary of Related Terms",
           "https://training.fema.gov/emiweb/is/icsresource/assets/glossary%20of%20related%20terms.pdf", PD,
           {"emergency": True}),
    Source("tc4021", "pdf", "U.S. Army TC 4-02.1 First Aid (2016, change 2 2018)",
           "https://ciehub.info/ref/TC/4-02x1_2018.pdf",
           PD + "; 'Approved for public release; distribution is unlimited'", {"medical": True}),
    Source("atp40211", "pdf", "U.S. Army ATP 4-02.11 Casualty Response, Tactical Combat Casualty Care and First Aid",
           "https://armypubs.army.mil/epubs/DR_pubs/DR_a/ARN46159-ATP_4-02.11-000-WEB-1.pdf",
           PD + "; 'Approved for public release, distribution is unlimited'", {"medical": True}),
    Source("gumo_sarplan", "pdf", "National Park Service, Guadalupe Mountains NP Search and Rescue Plan",
           "https://www.wilderness.net/toolboxes/documents/sar/GUMO_SAR%20Plan_signed.pdf", PD, {"sar": True}),
    Source("nps_sar", "html", "National Park Service search and rescue pages (6 pages)", "https://www.nps.gov/",
           PD + " (nps.gov disclaimer)", {"sar": True}),
    Source("nps", "html", "National Park Service hiking, climbing and trip safety pages (25 pages)",
           "https://www.nps.gov/", PD + " (nps.gov disclaimer)", {"hiking": True}),
    Source("usfs", "html", "USDA Forest Service 'Know before you go' safety pages (25 pages)",
           "https://www.fs.usda.gov/visit/know-before-you-go", PD,
           {"hiking": r"/(avalanches|bears|camping|cave-safety|heat|hiking|hypothermia|lightning|mountain-|safety|"
                      r"snakes|sun-safety|ticks|water-safety|wind|hail|hazard-trees|responsible)",
            "sar": r"/if-you-get-lost"}),
    Source("nws", "html", "NOAA National Weather Service safety pages (29 pages)", "https://www.weather.gov/safety/",
           "Public domain (weather.gov disclaimer)",
           {"emergency": r".", "hiking": NWS_OUTDOOR, "fire": r"/safety/wildfire"}),
    Source("ready", "html", "FEMA Ready.gov hazard and preparedness pages (29 pages)", "https://www.ready.gov/", PD,
           {"emergency": r".", "fire": r"ready\.gov/(home-fires|wildfires)$", "hiking": r"ready\.gov/avalanche$"}),
    Source("medlineplus", "mplus", "MedlinePlus health topic summaries (NLM), English topics in 'Injuries and "
           "Wounds', 'Symptoms' and the first-aid list", "https://medlineplus.gov/xml.html",
           "Public domain (MedlinePlus: health topic summaries are not copyrighted)", {"medical": True}),
]
FIRST_AID_TOPICS = {"animalbites", "burns", "choking", "cpr", "emergencymedicalservices", "foreignbodies",
                    "heatillness", "hypothermia", "insectbitesandstings", "poisoning", "shock", "sprainsandstrains",
                    "suddencardiacarrest", "woundsandinjuries", "bleeding", "fractures", "frostbite",
                    "headinjuries", "concussion", "dehydration", "snakebites", "spinalcordinjuries"}
MPLUS_GROUPS = {"Injuries and Wounds", "Symptoms"}

QUOTES = str.maketrans({"’": "'", "‘": "'", "“": '"', "”": '"', " ": " ", "­": ""})
BULLET = "•○□▪◦●☐✓✔➢*‣⁃"
SENT_RE = re.compile(r'(?<=[.!?])\s+(?=[A-Z"(\[])')
LETTER = re.compile(r"[A-Za-z]")
WEB_RE = re.compile(r"https?:|www\.|\.gov\b|\.html?\b|\.pdf\b|\bSkip to\b|\bShare this\b"
                    r"|comprehensive resources below|Safety Content$", re.IGNORECASE)   # NWS page boilerplate
PAGE_RE = re.compile(r"^(page\s+)?[0-9ivxlcIVXLC]{1,6}([-\u2013.][0-9]{1,4})?$", re.IGNORECASE)
WORD_RE = re.compile(r"^[A-Za-z][A-Za-z'\-]*$")


# ------------------------------------------------------------------ units

@dataclass
class Unit:
    source: str
    block: str
    text: str
    held_out: bool = False


def _clean_doc(text: str) -> str:
    return unicodedata.normalize("NFKC", text).translate(QUOTES)


def _ok_unit(t: str) -> bool:
    words = t.split()
    if len(words) < 3 or len(t.encode("utf-8")) > MAX_UNIT or "...." in t or ". . ." in t or WEB_RE.search(t):
        return False
    return sum(1 for w in words if LETTER.search(w)) * 2 >= len(words)


def _sentences(para: str) -> list[str]:
    para = " ".join(para.split())
    return [s for s in SENT_RE.split(para) if _ok_unit(s)]


def pdf_paragraphs(text: str) -> list[str]:
    paras: list[str] = []
    cur: list[str] = []

    def flush() -> None:
        if cur:
            paras.append(" ".join(cur))
            cur.clear()

    lines = [line.strip().replace("\f", "") for line in _clean_doc(text).split("\n")]
    # Running heads and feet ("COMMUNITY EMERGENCY RESPONSE TEAM", "IRPG", "5-12"): short lines seen 4+ times in a
    # document, and page numbers, are dropped before the lines are joined into paragraphs.
    repeated = Counter(line for line in lines if line and len(line) < 60)
    for s in lines:
        if s and (repeated[s] >= 4 or PAGE_RE.match(s)):
            continue
        if not s:
            flush()
            continue
        if s[0] in BULLET or re.match(r"^(\d{1,2}|[a-z])[.)]\s", s):
            flush()
            s = s.lstrip(BULLET).strip()
            if not s:
                continue
        if cur and cur[-1].endswith("-") and s[:1].islower():
            cur[-1] = cur[-1] + s
        else:
            cur.append(s)
        if s[-1] in ".!?:":
            flush()
    flush()
    return paras


def load_units(src: Source, corpus: Path, domain: str) -> list[Unit]:
    flt = src.domains[domain]
    out: list[Unit] = []
    if src.kind == "pdf":
        paras = pdf_paragraphs((corpus / src.id / "doc.txt").read_text(encoding="utf-8", errors="replace"))
        for i, p in enumerate(paras):
            for s in _sentences(p):
                out.append(Unit(src.id, f"{i // PDF_BLOCK}", s))
    elif src.kind == "html":
        pages = [(f.with_suffix(".url").read_text(encoding="utf-8").strip(),
                  _clean_doc(f.read_text(encoding="utf-8")).split("\n"))
                 for f in sorted((corpus / src.id).glob("*.txt"))]
        # Site boilerplate (menus, banners): a line on 30 % of the pages of a site (at least 3) is dropped.
        boiler = Counter(line for _, lines in pages for line in set(lines))
        limit = max(3, int(0.3 * len(pages)))
        for url, lines in pages:
            if flt is not True and not re.search(flt, url):
                continue
            for line in lines:
                if boiler[line] >= limit:
                    continue
                for s in _sentences(line):
                    out.append(Unit(src.id, url, s))
    elif src.kind == "mplus":
        xml = sorted((corpus / "medlineplus").glob("mplus_topics_*.xml"))[-1]
        for topic in ET.parse(xml).getroot().iter("health-topic"):
            if topic.get("language") != "English":
                continue
            url = topic.get("url", "")
            name = url.rsplit("/", 1)[-1].replace(".html", "")
            groups = {g.text for g in topic.findall("group")}
            if not (groups & MPLUS_GROUPS or name in FIRST_AID_TOPICS):
                continue
            summary = topic.findtext("full-summary") or ""
            summary = re.sub(r"</(p|li|h\d)>|<br\s*/?>", "\n", summary)
            summary = html.unescape(re.sub(r"<[^>]+>", "", summary))
            for line in _clean_doc(summary).split("\n"):
                if re.match(r"^(NIH|CDC|NLM|FDA|HHS|AHRQ|NHLBI|NIAMS|NINDS|NIA|NICHD)\b.*:", line.strip()):
                    continue
                for s in _sentences(line):
                    out.append(Unit(src.id, url, s))
    elif src.kind == "disaster":
        rows = []
        for f in sorted((corpus / "disaster").glob("disaster_response_*.csv")):
            with f.open(encoding="utf-8", newline="") as fh:
                rows.extend(csv.DictReader(fh))
        picked = []
        for r in rows:
            if r.get("PII") == "1" or not any(r.get(c) == "1" for c in flt):
                continue
            t = " ".join((r.get("message") or "").split())
            if t and len(t.encode("utf-8")) <= MAX_UNIT and len(t.split()) >= 2:
                picked.append(t)
        picked = sorted(set(picked), key=lambda t: hashlib.sha256(t.encode("utf-8")).hexdigest())[:DISASTER_CAP]
        out = [Unit(src.id, t, t) for t in picked]
    elif src.kind == "tatoeba":
        rx = re.compile(flt, re.IGNORECASE)
        picked = []
        with (corpus / "tatoeba" / "eng_sentences.tsv").open(encoding="utf-8") as fh:
            for line in fh:
                parts = line.rstrip("\n").split("\t")
                if len(parts) == 3 and rx.search(parts[2]) and len(parts[2].encode("utf-8")) <= MAX_UNIT:
                    picked.append(parts[2])
        picked = sorted(set(picked), key=lambda t: hashlib.sha256(t.encode("utf-8")).hexdigest())[:TATOEBA_CAP]
        out = [Unit(src.id, t, t) for t in picked]
    elif src.kind == "sar_yaml":
        text = (ROOT / "spec" / "vocab" / "sar.yaml").read_text(encoding="utf-8")
        for m in re.finditer(r"^\s*en:\s*\[(.*)\]\s*$", text, re.MULTILINE):
            for item in re.findall(r'"([^"]+)"|([^,\s][^,]*)', m.group(1)):
                t = (item[0] or item[1]).strip()
                if t:
                    out.append(Unit(src.id, t, t))
    elif src.kind == "team":
        data = json.loads((ROOT / "spec" / "tools" / "text-corpus.json").read_text(encoding="utf-8"))
        out = [Unit(src.id, t, t) for part in ("tune", "holdout") for t in data[part]["en"]]
    for u in out:
        if src.role == "eval":
            u.held_out = True
        elif src.role == "build":
            u.held_out = False
        else:
            h = int(hashlib.sha256(f"{src.id}:{u.block}".encode("utf-8")).hexdigest()[:8], 16)
            u.held_out = h % 100 < HELD_OUT
    return out


def _norm(t: str) -> str:
    return " ".join(t.lower().split())


def domain_units(domain: str, corpus: Path) -> list[Unit]:
    """Build and held-out units of a domain, deduplicated (inside the domain and across the split)."""
    units: list[Unit] = []
    seen: set[str] = set()
    for src in SOURCES:
        if domain not in src.domains:
            continue
        for u in load_units(src, corpus, domain):
            k = _norm(u.text)
            if k in seen:
                continue
            seen.add(k)
            units.append(u)
    build = {_norm(u.text) for u in units if not u.held_out}
    return [u for u in units if not u.held_out or _norm(u.text) not in build or u.source == "team"]


# ------------------------------------------------------------------ candidates and selection

def ascii_lower(s: str) -> str:
    return "".join(chr(ord(c) + 32) if "A" <= c <= "Z" else c for c in s)


def ascii_upper(s: str) -> str:
    return "".join(chr(ord(c) - 32) if "a" <= c <= "z" else c for c in s)


def eligible_text(s: str) -> bool:
    return (all(32 <= ord(c) < 127 for c in s) and "@" not in s and "http" not in s.lower()
            and "www" not in s.lower() and not re.search(r"\d{5}", s) and s == s.strip() and "\t" not in s)


@dataclass
class Stats:
    count: Counter = field(default_factory=Counter)      # source -> occurrences
    units: set = field(default_factory=set)               # unit numbers
    doc_units: set = field(default_factory=set)           # unit numbers from documents (pdf, html, mplus)
    sources: set = field(default_factory=set)


def count_candidates(units: list[Unit], doc_sources: set) -> tuple[dict, dict]:
    """piece or phrase -> Stats over build units; source -> build bytes."""
    stats: dict[str, Stats] = defaultdict(Stats)
    size: Counter = Counter()
    for n, u in enumerate(units):
        if u.held_out:
            continue
        size[u.source] += len(u.text.encode("utf-8"))
        gaps, pieces = split_pieces(u.text)
        for i, p in enumerate(pieces):
            st = stats[p]
            st.count[u.source] += 1
            st.units.add(n)
            st.sources.add(u.source)
            if u.source in doc_sources:
                st.doc_units.add(n)
            for k in (2, 3):
                if i + k <= len(pieces) and all(gaps[i + j] == " " for j in range(1, k)) \
                        and all(WORD_RE.match(pieces[i + j]) for j in range(k)):
                    ph = " ".join(pieces[i:i + k])
                    st2 = stats[ph]
                    st2.count[u.source] += 1
                    st2.units.add(n)
                    st2.sources.add(u.source)
    return stats, size


def select_entries(stats: dict, size: Counter, weights: dict, doc_sources: set, d2: WordDict, nbits: int,
                   phrase_share: float) -> list[str]:
    capacity = (1 << nbits) - RESERVED
    lh = nbits + 8

    def fallback(s: str) -> int:
        m = match(s, d2)
        if m is not None:
            return nbits + 16 + (nbits if m[1] else 0)
        return lh + 8 * (len(s.encode("utf-8")) + 1)

    def per_byte(st: Stats) -> float:
        return sum(weights[s] * c / size[s] for s, c in st.count.items() if size[s])

    def allowed(s: str) -> bool:
        st = stats.get(s)
        if st is None or not eligible_text(s) or len(st.units) < MIN_UNITS:
            return False
        if " " in s:
            return all(allowed_word(w) for w in s.split(" "))
        return allowed_word(s)

    def allowed_word(w: str) -> bool:
        st = stats.get(w)
        if st is None or not eligible_text(w) or len(st.units) < MIN_UNITS:
            return False
        if "A" <= w[0] <= "Z" and ascii_lower(w) not in stats:
            # Never written in lower case: a name or a heading. Tatoeba's stock names ("Tom", "Layla") and one
            # document's own names stay out: it must be in 2+ sources other than Tatoeba.
            return len(st.sources - {"tatoeba"}) >= 2
        if match(w, d2) is not None:
            return True
        return len(st.sources) >= 2 or len(st.doc_units) >= RARE_UNITS_DOC

    scored: list[tuple[float, str, tuple]] = []     # (score, text, requirements)
    for s, st in stats.items():
        if not allowed(s):
            continue
        if " " in s:
            k = s.count(" ") + 1
            scored.append((per_byte(st) * (k - 1) * nbits, s, tuple(s.split(" "))))
            continue
        low = ascii_lower(s)
        if low != s and low in stats and allowed(low):
            scored.append((per_byte(st) * nbits, s, (low,)))   # own entry for a capitalised form
            continue
        score = per_byte(st) * (fallback(s) - nbits)
        for form in {cap for cap in (s[:1].upper() + s[1:], ascii_upper(s)) if cap != s}:
            if form in stats:
                score += per_byte(stats[form]) * max(0, fallback(form) - 2 * nbits)
        if score > 0:
            scored.append((score, s, ()))
    # One ranking (score, then text). An item waits until everything it needs is chosen, then joins the queue.
    max_phrases = int(capacity * phrase_share)
    if not max_phrases:
        scored = [x for x in scored if " " not in x[1]]
    rank = {s: i for i, (_, s, _) in enumerate(sorted(scored, key=lambda x: (-x[0], x[1])))}
    waiting: dict[str, list] = defaultdict(list)
    heap: list[tuple[int, str, tuple]] = []
    for score, s, req in scored:
        if req:
            for r in set(req):
                waiting[r].append((s, req))
        else:
            heapq.heappush(heap, (rank[s], s, req))
    chosen: list[str] = []
    have: set[str] = set()
    phrases = 0
    while heap and len(chosen) < capacity:
        _, s, req = heapq.heappop(heap)
        if s in have:
            continue
        if " " in s:
            if phrases >= max_phrases:
                continue
            phrases += 1
        chosen.append(s)
        have.add(s)
        for t, treq in waiting.pop(s, ()):
            if t not in have and all(r in have for r in treq):
                heapq.heappush(heap, (rank[t], t, treq))
    return chosen


# ------------------------------------------------------------------ bit-cost encoder (SPEC 3.6.4 with N-bit codes)

_TOK, _LIT, _START = 0, 1, 2
_INF = 1 << 40


@dataclass
class Encoded:
    bits: int
    words_domain: int = 0
    words_v2: int = 0
    words_literal: int = 0


def encode_bits(text: str, dd: WordDict, d2: WordDict, nbits: int) -> Encoded:
    """Shortest domain stream in bits: entry N, escape N+16, CAP/UPPER/GLUE N, literal N+8 + 8 per byte."""
    lh = nbits + 8
    gaps, pieces = split_pieces(text)
    n = len(pieces)
    span_max = max(dd.max_span, d2.max_span)
    cost = [[_INF, _INF] for _ in range(n + 1)]
    back: list[list] = [[None, None] for _ in range(n + 1)]

    def best(span: str):
        m = match(span, dd)
        a = None if m is None else (nbits + (nbits if m[1] else 0), "d")
        m2 = match(span, d2)
        b = None if m2 is None else (nbits + 16 + (nbits if m2[1] else 0), "v")
        if a and (not b or a[0] <= b[0]):
            return a
        return b

    for k in range(n):
        g = gaps[k]
        g8 = 8 * len(g.encode("utf-8"))
        for st in ((_START,) if k == 0 else (_TOK, _LIT)):
            c0 = 0 if st == _START else cost[k][st]
            if c0 >= _INF:
                continue
            span = ""
            for j in range(k, min(n, k + span_max)):
                span = pieces[k] if j == k else span + gaps[j] + pieces[j]
                m = best(span)
                if m is None:
                    continue
                ct, which = m
                implied = st == _TOK and span[0] not in LEFT_ATTACH and pieces[k - 1][-1] not in RIGHT_ATTACH
                if implied and g == " ":
                    add, mode = ct, "none"
                elif implied and g == "":
                    add, mode = nbits + ct, "glue"
                elif g == "":
                    add, mode = ct, "none"
                elif st == _LIT:
                    add, mode = g8 + ct, "gap"
                else:
                    add, mode = lh + g8 + ct, "gap"
                if c0 + add < cost[j + 1][_TOK]:
                    cost[j + 1][_TOK] = c0 + add
                    back[j + 1][_TOK] = (k, st, "tok", which, j + 1 - k, mode)
            add = g8 + 8 * len(pieces[k].encode("utf-8")) + (0 if st == _LIT else lh)
            if c0 + add < cost[k + 1][_LIT]:
                cost[k + 1][_LIT] = c0 + add
                back[k + 1][_LIT] = (k, st, "lit")
    last = gaps[n]
    if n == 0:
        return Encoded(lh + 8 * len(last.encode("utf-8")))
    end_tok = cost[n][_TOK] + (0 if last == "" else lh + 8 * len(last.encode("utf-8")))
    end_lit = cost[n][_LIT] + 8 * len(last.encode("utf-8"))
    pos, st = n, (_TOK if end_tok <= end_lit else _LIT)
    total = min(end_tok, end_lit)
    steps = []
    while pos > 0:
        step = back[pos][st]
        steps.append(step)
        pos, st = step[0], step[1]
    steps.reverse()
    enc = Encoded(total)
    run = 0
    runs = []
    for step in steps:
        k = step[0]
        if step[2] == "lit":
            run += len((gaps[k] + pieces[k]).encode("utf-8"))
            enc.words_literal += 1 if LETTER.search(pieces[k]) or pieces[k].isdigit() else 0
            continue
        _, _, _, which, npieces, mode = step
        if mode == "gap":
            run += len(gaps[k].encode("utf-8"))
        if run:
            runs.append(run)
            run = 0
        words = sum(1 for p in pieces[k:k + npieces] if LETTER.search(p) or p.isdigit())
        if which == "d":
            enc.words_domain += words
        else:
            enc.words_v2 += words
    run += len(last.encode("utf-8"))
    if run:
        runs.append(run)
    enc.bits += sum((math.ceil(r / 255) - 1) * lh for r in runs)     # a literal holds at most 255 bytes
    return enc


def frame_bytes(bits: int) -> int:
    return FRAME_HEADER + (bits + 7) // 8


# ------------------------------------------------------------------ evaluation

@dataclass
class Eval:
    plain: int = 0
    base: int = 0          # strictly smallest of plain and the 0x02 frame (word dictionary 2)
    sent: int = 0          # strictly smallest of plain, 0x02 frame and this domain frame
    domain_only: int = 0   # the domain frame alone
    words: int = 0
    units: int = 0
    as_domain: int = 0
    w_domain: int = 0
    w_v2: int = 0
    w_lit: int = 0

    def add(self, o: "Eval") -> None:
        for k in self.__dataclass_fields__:
            setattr(self, k, getattr(self, k) + getattr(o, k))


_V2_CACHE: dict[str, int] = {}


def v2_frame(text: str) -> int:
    v = _V2_CACHE.get(text)
    if v is None:
        v = _V2_CACHE[text] = FRAME_HEADER + len(encode_words(text, V2))
    return v


def evaluate(units: list[Unit], entries: list[str], d2: WordDict, nbits: int) -> dict[str, Eval]:
    dd = WordDict.from_entries(entries)
    res: dict[str, Eval] = defaultdict(Eval)
    for u in units:
        if not u.held_out:
            continue
        e = Eval(units=1)
        e.plain = len(u.text.encode("utf-8"))
        e.words = len(u.text.split())
        v2 = v2_frame(u.text)
        e.base = v2 if v2 < e.plain else e.plain
        enc = encode_bits(u.text, dd, d2, nbits)
        dom = frame_bytes(enc.bits)
        e.domain_only = dom
        e.sent = dom if dom < e.base else e.base
        e.as_domain = 1 if dom < e.base else 0
        e.w_domain, e.w_v2, e.w_lit = enc.words_domain, enc.words_v2, enc.words_literal
        res[u.source].add(e)
    return res


def objective(res: dict[str, Eval]) -> float:
    return sum(e.sent / e.plain for e in res.values()) / len(res)


@dataclass
class Choice:
    domain: str
    nbits: int
    phrase_share: float
    entries: list
    res: dict
    table: list            # (nbits, share, objective, pooled)


def choose(domain: str, corpus: Path, d2: WordDict, log=print) -> Choice:
    units = domain_units(domain, corpus)
    doc_sources = {s.id for s in SOURCES if s.kind in ("pdf", "html", "mplus")}
    stats, size = count_candidates(units, doc_sources)
    weights = {s.id: s.weight for s in SOURCES}
    nb = Counter(u.source for u in units if not u.held_out)
    nh = Counter(u.source for u in units if u.held_out)
    log(f"\n## {domain}: build units {sum(nb.values())}, held-out units {sum(nh.values())}  "
        + ", ".join(f"{s} {nb[s]}/{nh[s]}" for s in sorted(set(nb) | set(nh))))
    best = None
    table = []
    for nbits in SIZES:
        for share in PHRASE_SHARES:
            entries = select_entries(stats, size, weights, doc_sources, d2, nbits, share)
            res = evaluate(units, entries, d2, nbits)
            obj = objective(res)
            pooled = Eval()
            for e in res.values():
                pooled.add(e)
            table.append((nbits, share, obj, pooled, len(entries)))
            log(f"  N={nbits:2d} phrases {share:4.0%} entries {len(entries):4d}: mean sent/plain {obj:.4f}  "
                f"pooled plain {pooled.plain} B, base {pooled.base} B, sent {pooled.sent} B "
                f"({1 - pooled.sent / pooled.base:+.1%} vs base), domain words "
                f"{pooled.w_domain / max(1, pooled.w_domain + pooled.w_v2 + pooled.w_lit):.1%}")
            if best is None or obj < best[0] - 1e-12:
                best = (obj, nbits, share, entries, res)
    obj, nbits, share, entries, res = best
    for s in sorted(res):
        e = res[s]
        aw = max(1, e.w_domain + e.w_v2 + e.w_lit)
        log(f"    chosen N={nbits} {s:13s} units {e.units:5d} plain {e.plain:6d} base {e.base:6d} sent {e.sent:6d} "
            f"({1 - e.sent / e.base:+.1%} vs base) words in vocab {e.w_domain / aw:.1%} escaped {e.w_v2 / aw:.1%} "
            f"literal {e.w_lit / aw:.1%}, sent as domain frame {e.as_domain / e.units:.0%}")
    return Choice(domain, nbits, share, entries, res, table)


# ------------------------------------------------------------------ files

def vocab_bytes(entries: list[str]) -> bytes:
    return ("\n".join(entries) + "\n").encode("utf-8")


def domains_yaml(choices: list[Choice], shas: dict, d2sha: str) -> str:
    out = []
    w = out.append
    w("# RescueCodec domain vocabularies for the 0x03 domain frame. Licence of this file: Apache-2.0.")
    w("# Generated by spec/tools/domain_vocab.py (reproducible; inputs listed under `sources`). Do not edit by hand:")
    w("# the vocabulary files are frozen like the word dictionaries (a change is a new id).")
    w("#")
    w("# Frame (port 256):  0x03 | vocabulary id (1 byte) | bit stream of N-bit codes, MSB first, last byte zero-padded.")
    w("# N follows from the id (table below). The decoder stops when fewer than N bits remain (padding is < 8 <= N).")
    w("# Code c < 2^N - 8: entry c = line c+1 of the vocabulary file (ID 0 is the first line).")
    w("# Controls: the top 8 codes of the code space, base = 2^N - 8:")
    w("#   base+0 ESC16  the next 16 bits are a token of word dictionary 2 (0x0000..0xEFFF; 0xF000.. is malformed)")
    w("#   base+1 CAP    the next entry (or escaped token) with its first a-z written A-Z")
    w("#   base+2 UPPER  the next entry (or escaped token) with every a-z written A-Z")
    w("#   base+3 GLUE   no space before the next entry")
    w("#   base+4 LIT    the next 8 bits are a length L = 1..255 (0 is malformed), then L bytes of UTF-8, copied exactly")
    w("#   base+5..base+7 reserved (malformed)")
    w("# Spaces, punctuation and literal ownership: exactly as SPEC 3.6.3 (an entry and an escaped token are both")
    w("# 'entries'; a literal carries its own spaces). Files: one entry per line, UTF-8, LF, ending with LF, printable")
    w("# ASCII, no leading/trailing space, no duplicates. `.gitattributes` keeps the bytes exact; check the SHA-256.")
    w("")
    w("format: 1")
    w(f"reserved_codes: {RESERVED}")
    w("controls: [" + ", ".join(CONTROLS) + "]   # offsets 0..7 from 2^N - 8")
    w(f"general_dictionary: {{file: words-dict-v2.txt, version: 2, sha256: {d2sha}}}")
    w("")
    w("vocabularies:")
    for c in sorted(choices, key=lambda c: DOMAINS[c.domain]["id"]):
        meta = DOMAINS[c.domain]
        name = f"domain-{c.domain}-v1.txt"
        w(f"  - id: {meta['id']}")
        w(f"    domain: {c.domain}")
        w(f"    label: \"{meta['label']}\"")
        w(f"    short: \"{meta['short']}\"")
        w("    version: 1")
        w(f"    bits: {c.nbits}")
        w(f"    entries: {len(c.entries)}")
        w(f"    phrases: {sum(1 for e in c.entries if ' ' in e)}")
        w(f"    file: {name}")
        w(f"    bytes: {len(vocab_bytes(c.entries))}")
        w(f"    sha256: {shas[name]}")
    w("  # ids 0 and 6..255 are not assigned: a decoder shows \"unsupported vocabulary\" (never guessed words).")
    w("")
    w("# Held-out measurement (spec/tools/domain_vocab.py select). Units: sentences of documents and real or")
    w("# example messages, up to 400 bytes, never used to build. base = strictly smallest of plain text and the")
    w("# 0x02 compact frame (word dictionary 2); sent = strictly smallest of plain, 0x02 frame and this domain frame.")
    w("# words_in_vocab = share of words written as N-bit domain codes; bytes_per_word = sent bytes / words.")
    w("measured:")
    for c in sorted(choices, key=lambda c: DOMAINS[c.domain]["id"]):
        pooled = Eval()
        for e in c.res.values():
            pooled.add(e)
        allw = max(1, pooled.w_domain + pooled.w_v2 + pooled.w_lit)
        w(f"  {c.domain}:")
        w(f"    chosen: {{bits: {c.nbits}, phrase_share: {c.phrase_share}}}")
        w("    size_table:   # bits, phrase share, mean sent/plain over sources (lower is better), pooled sent bytes")
        for nbits, share, obj, p, n in c.table:
            w(f"      - [{nbits}, {share}, {obj:.4f}, {p.sent}]")
        w(f"    held_out: {{units: {pooled.units}, words: {pooled.words}, plain_bytes: {pooled.plain}, "
          f"base_bytes: {pooled.base}, sent_bytes: {pooled.sent}, domain_frame_only_bytes: {pooled.domain_only}}}")
        w(f"    words_in_vocab: {pooled.w_domain / allw:.3f}")
        w(f"    words_escaped_v2: {pooled.w_v2 / allw:.3f}")
        w(f"    words_literal: {pooled.w_lit / allw:.3f}")
        w(f"    bytes_per_word: {{plain: {pooled.plain / pooled.words:.2f}, base: {pooled.base / pooled.words:.2f}, "
          f"sent: {pooled.sent / pooled.words:.2f}}}")
        w(f"    saved_vs_base: {1 - pooled.sent / pooled.base:.3f}")
        w(f"    saved_vs_plain: {1 - pooled.sent / pooled.plain:.3f}")
        w(f"    sent_as_domain_frame: {pooled.as_domain / pooled.units:.3f}")
        w("    per_source:   # units, plain, base, sent bytes, words in vocab")
        for s in sorted(c.res):
            e = c.res[s]
            aw = max(1, e.w_domain + e.w_v2 + e.w_lit)
            w(f"      {s}: [{e.units}, {e.plain}, {e.base}, {e.sent}, {e.w_domain / aw:.3f}]")
    w("")
    w("# Sources (text was downloaded once into a folder outside the repo; no raw text is committed).")
    w("# Privacy: an entry is printable ASCII, has no '@', 'http', 'www' or 5+ digits, is in 5+ build units, and a")
    w("# word not in word dictionary 2 is in 2+ sources or 10+ document units (no names or typos from messages).")
    w("sources:")
    for s in SOURCES:
        w(f"  - id: {s.id}")
        w(f"    title: \"{s.title}\"")
        w(f"    url: \"{s.url}\"")
        w(f"    licence: \"{s.licence}\"")
        w(f"    retrieved: {s.retrieved}")
        w(f"    domains: [{', '.join(s.domains)}]")
        w(f"    role: {s.role}")
        w(f"    weight: {s.weight}")
    w("attribution: >-")
    w("  Vocabularies derived in part from \"Disaster Response Messages\" (Figure Eight / Robert Munro, CC BY 4.0) and")
    w("  from sentences of Tatoeba (tatoeba.org, CC BY 2.0 FR). The other sources are U.S. federal government works")
    w("  (NWCG, FEMA / USFA, U.S. Army, NPS, USDA Forest Service, NOAA NWS, NLM MedlinePlus) in the public domain.")
    return "\n".join(out) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("command", choices=("select", "build"))
    ap.add_argument("--corpus", required=True, type=Path)
    ap.add_argument("--domain", action="append", help="only these domains (select)")
    ap.add_argument("--check", action="store_true", help="build: compare with the files instead of writing")
    a = ap.parse_args()
    d2 = textwords.dictionary(V2)
    d2sha = textwords.DICTS[V2][1]
    names = a.domain or list(DOMAINS)
    choices = [choose(d, a.corpus, d2) for d in names]
    print("\n## chosen")
    for c in choices:
        pooled = Eval()
        for e in c.res.values():
            pooled.add(e)
        allw = max(1, pooled.w_domain + pooled.w_v2 + pooled.w_lit)
        print(f"  {c.domain:9s} N={c.nbits} ({len(c.entries)} entries, phrases {c.phrase_share:.0%}): "
              f"words in vocab {pooled.w_domain / allw:.1%}, bytes/word plain {pooled.plain / pooled.words:.2f} "
              f"base {pooled.base / pooled.words:.2f} sent {pooled.sent / pooled.words:.2f}, "
              f"saved vs base {1 - pooled.sent / pooled.base:.1%}, vs plain {1 - pooled.sent / pooled.plain:.1%}")
    if a.command == "select":
        return 0
    if set(names) != set(DOMAINS):
        print("build needs every domain", file=sys.stderr)
        return 2
    files = {f"domain-{c.domain}-v1.txt": vocab_bytes(c.entries) for c in choices}
    shas = {n: hashlib.sha256(b).hexdigest() for n, b in files.items()}
    files["domains.yaml"] = domains_yaml(choices, shas, d2sha).encode("utf-8")
    bad = 0
    for n, b in files.items():
        p = VOCAB_DIR / n
        if a.check:
            same = p.exists() and p.read_bytes() == b
            print(f"{'same' if same else 'DIFFERENT'}  spec/vocab/{n}")
            bad += not same
        else:
            p.write_bytes(b)
            print(f"wrote spec/vocab/{n} ({len(b)} bytes, sha256 {hashlib.sha256(b).hexdigest()})")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
