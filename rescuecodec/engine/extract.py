"""Rule-based extractor for search and rescue reports (German and English). Licence: Apache-2.0.

Turns one spoken or typed sentence into one canonical RescueCodec message (no id, no device GPS:
the app adds both). Words come from spec/vocab/sar.yaml. Rules are in spec/SPEC.md section 11.

    from rescuecodec.engine import extract
    r = extract("Kind gefunden, ansprechbar, stark unterkühlt, Knöchel verletzt. Wir brauchen eine Trage.")
    r.message      # {"found": {"age": "child", "consciousness": "conscious", ...}}
    r.unsupported  # False. True means: do not guess, send r.message (a text message) after a person agrees.

Principles: only what the sentence says. Unknown stays unknown. Negation turns a cue off. When one message
cannot carry the facts safely (two persons in different states, an object found), the result is a text message.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from collections.abc import Mapping
from typing import Any

from rescuecodec.engine.vocab import Vocab, fold, load_vocab, tokenize
from rescuecodec.v2 import codes as C
from rescuecodec.v2.codec import CodecError, encode, message_type

SUFFIXES = frozenset({"s", "e", "n", "en", "er", "es", "em"})
FILLERS = frozenset({"selbst", "auch", "noch", "wieder", "sehr", "ganz", "very", "still", "just", "really", "quite"})
MORE_WORDS = frozenset({"more", "weitere", "weiteren", "weiterer", "mehr", "extra", "additional", "zusaetzliche"})
DETERMINERS = frozenset({"a", "an", "the", "some", "ein", "eine", "einen", "einem", "der", "die", "das", "den", "dem"})
# negation words the vocabulary file does not list (the file stays the main source)
EXTRA_NEGATION = frozenset({"nobody", "noone", "niemand", "nothing", "nichts"})
BOUNDARY_WORDS = frozenset({"aber", "but", "sondern", "however", "jedoch", "doch"})
OBJECT_WORDS = frozenset({
    "backpack", "rucksack", "jacket", "jacke", "phone", "handy", "smartphone", "hat", "muetze", "cap", "glove", "gloves",
    "handschuh", "handschuhe", "boot", "boots", "stiefel", "shoe", "shoes", "schuh", "schuhe", "tracks", "spur", "spuren",
    "fussspuren", "footprints", "footprint", "clothing", "kleidung", "equipment", "ausruestung", "wallet", "bag",
    "tasche", "car", "wagen", "item", "items", "gegenstand", "sachen",
})
UNIT_M = frozenset({"m", "meter", "metern", "metre", "metres", "meters"})
UNIT_KM = frozenset({"km", "kilometer", "kilometern", "kilometre", "kilometres", "kilometers"})
PERCENT_WORDS = frozenset({"%", "percent", "prozent"})
MAX_TEXT_BYTES = C.MAX_TEXT_BYTES
ELLIPSIS = "…"                # added at the end of a text that fit_text shortened
IGNORED_FIELDS = frozenset({"acc", "ack.status"})
# A negated value that has an opposite meaning flips; every other negated value is dropped.
FLIP = {("consciousness", "conscious"): "unconscious", ("consciousness", "unconscious"): "conscious",
        ("breathing", "normal"): "none", ("mobility", "walks"): "cannot_walk"}
PRIORITY_LEVEL = {"routine": 1, "normal": 2, "urgent": 3, "critical": 4}
# unsupported reasons
R_EMPTY, R_NO_TYPE, R_CONFLICT, R_OBJECT, R_MISSING, R_AMBIGUOUS, R_INVALID = (
    "empty", "no_supported_type", "conflicting_states", "object_found", "missing_field", "ambiguous", "invalid")


@dataclass
class Match:
    field: str
    code: str
    start: int
    end: int
    cost: int = 0
    lang: str = ""
    negated: bool = False
    uncertain: bool = False
    flipped: bool = False


@dataclass
class Extraction:
    text: str
    message: dict | None
    unsupported: bool
    reason: str | None = None
    method: str = "rules"
    lang: str = "unknown"
    evidence: list[dict] = field(default_factory=list)
    cues: list[str] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)
    pos: dict | None = None      # spoken coordinates, kept even when the result is a text fallback

    @property
    def type(self) -> str | None:
        return next(iter(k for k in self.message if k in C.BODY_TYPES), None) if self.message else None

    def to_dict(self) -> dict[str, Any]:
        return {"text": self.text, "message": self.message, "unsupported": self.unsupported, "reason": self.reason,
                "method": self.method, "lang": self.lang, "evidence": self.evidence, "cues": self.cues, "notes": self.notes}


# ---------------------------------------------------------------- token matching

def _within_one_edit(a: str, b: str) -> bool:
    if a == b:
        return True
    la, lb = len(a), len(b)
    if abs(la - lb) > 1:
        return False
    if la == lb:
        diff = [i for i in range(la) if a[i] != b[i]]
        if len(diff) == 1:
            return True
        return len(diff) == 2 and diff[1] == diff[0] + 1 and a[diff[0]] == b[diff[1]] and a[diff[1]] == b[diff[0]]
    if la > lb:
        a, b = b, a
    for i in range(len(b)):
        if a[:i] == b[:i] and a[i:] == b[i + 1:]:
            return True
    return False


def _token_cost(tok: str, ptok: str, fuzzy: "bool | frozenset[str]" = True) -> int | None:
    """0 for an exact or inflected match, 1 for a typo, None for no match.

    `fuzzy` is False (no typos), True, or the set of known vocabulary words: a known word is never
    treated as a typo of another word ("verwirrt" is not "verirrt")."""
    if tok == ptok:
        return 0
    if tok.startswith(ptok) and ((len(ptok) >= 4 and tok[len(ptok):] in SUFFIXES) or (len(ptok) == 3 and tok[3:] == "s")):
        return 0
    if fuzzy and len(ptok) >= 6 and len(tok) >= 6 and tok[:2] == ptok[:2] and not ptok[0].isdigit() \
            and not (fuzzy is not True and tok in fuzzy) and _within_one_edit(tok, ptok):
        return 1
    return None


def _match_at(tokens: list[str], clauses: list[int], i: int, ptoks: tuple[str, ...], fuzzy: "bool | frozenset[str]" = True) -> tuple[int, int] | None:
    """Match a phrase at token i. One filler word may sit between two phrase words. Returns (end, cost)."""
    j, cost = i, 0
    for k, pt in enumerate(ptoks):
        if j >= len(tokens) or clauses[j] != clauses[i]:
            return None
        c = _token_cost(tokens[j], pt, fuzzy)
        if c is None and k > 0 and tokens[j] in FILLERS and j + 1 < len(tokens) and clauses[j + 1] == clauses[i]:
            j += 1
            c = _token_cost(tokens[j], pt, fuzzy)
        if c is None:
            return None
        cost += c
        j += 1
    return j, cost


# ---------------------------------------------------------------- the extractor

class _Run:
    def __init__(self, text: str, voc: Vocab, lang_hint: str | None):
        self.text = text
        self.voc = voc
        self.known = frozenset(t for p in voc.phrases for t in p.tokens)
        self.notes: list[str] = []
        self.pos: dict | None = None
        self._coords(text)
        self.tokens, self.clauses = self._tokenize(self.clean)
        self.matches = self._find_matches()
        self.lang = self._language(lang_hint)

    # --- preparation
    _COORD = re.compile(r"(?<![\d.,])(-?\d{1,2}[.,]\d{3,8})\s*°?\s*[nN]?\s*[,;/]?\s+(-?\d{1,3}[.,]\d{3,8})\s*°?\s*[eE]?(?![\d])"
                        r"|(?<![\d.,])(-?\d{1,2}[.,]\d{3,8})\s*°?\s*[nN]?\s*[,;/]\s*(-?\d{1,3}[.,]\d{3,8})\s*°?\s*[eE]?(?![\d])")

    def _coords(self, text: str) -> None:
        self.clean = text
        m = self._COORD.search(text)
        if not m:
            return
        groups = [g for g in m.groups() if g]
        lat, lon = (float(g.replace(",", ".")) for g in groups[:2])
        if -90 <= lat <= 90 and -180 <= lon <= 180:
            self.pos = {"lat": round(lat, 5), "lon": round(lon, 5), "acc": "estimated"}
            self.clean = text[:m.start()] + " " + text[m.end():]

    @staticmethod
    def _tokenize(text: str) -> tuple[list[str], list[int]]:
        folded = fold(text)
        tokens: list[str] = []
        clauses: list[int] = []
        clause, last_end = 0, 0
        for m in re.finditer(r"\d+(?:[.,]\d+)?|[a-z]+(?:'[a-z]+)?|%", folded):
            if re.search(r"[.,;:!?\n()]", folded[last_end:m.start()]):
                clause += 1
            tokens.append(m.group())
            clauses.append(clause)
            last_end = m.end()
        return tokens, clauses

    def _find_matches(self) -> list[Match]:
        found: dict[tuple, Match] = {}
        for ph in self.voc.phrases:
            if ph.field in IGNORED_FIELDS:
                continue
            fuzzy = False if ph.field in ("negation", "uncertainty") else self.known
            for i in range(len(self.tokens)):
                hit = _match_at(self.tokens, self.clauses, i, ph.tokens, fuzzy)
                if hit:
                    key = (ph.field, ph.code, i, hit[0])
                    if key not in found or found[key].cost > hit[1]:
                        found[key] = Match(ph.field, ph.code, i, hit[0], hit[1], ph.lang)
        matches = list(found.values())
        mods = [m for m in matches if m.field in ("negation", "uncertainty")]
        cues = [m for m in matches if m.field not in ("negation", "uncertainty")]
        # a longer phrase eats the shorter phrase inside it ("nicht ansprechbar" beats "ansprechbar"),
        # except that message cue words ("haben das kind") never eat field words.
        keep = []
        for m in cues:
            eaten = any(n is not m and n.field != "msg" and n.start <= m.start and m.end <= n.end
                        and (n.end - n.start) > (m.end - m.start) for n in cues)
            if not eaten:
                keep.append(m)
        # exact beats typo on the same span and field
        best: dict[tuple, Match] = {}
        for m in keep:
            k = (m.field, m.start, m.end)
            if k not in best or m.cost < best[k].cost:
                best[k] = m
        keep = [m for m in keep if m.cost == best[(m.field, m.start, m.end)].cost]
        keep.sort(key=lambda m: (m.start, m.end))
        self._annotate(keep, mods)
        return keep

    def _annotate(self, keep: list[Match], mods: list[Match]) -> None:
        covered = set()
        for m in keep:
            covered.update(range(m.start, m.end))
        unc_spans = [m for m in mods if m.field == "uncertainty"]
        unc_tokens = set()
        for u in unc_spans:
            unc_tokens.update(range(u.start, u.end))
        neg_tokens = set()
        for n in mods:
            if n.field == "negation" and not any(t in covered or t in unc_tokens for t in range(n.start, n.end)):
                neg_tokens.add(n.end - 1)
        for i, tok in enumerate(self.tokens):
            if tok in EXTRA_NEGATION and i not in covered and i not in unc_tokens:
                neg_tokens.add(i)
        for m in keep:
            for ni in neg_tokens:
                if m.start - 3 <= ni < m.start and self._same_scope(ni, m.start):
                    m.negated = True
                    break
            for u in unc_spans:
                if (m.start - 3 <= u.end - 1 < m.start or m.end <= u.start <= m.end + 1) and self._same_scope(min(u.start, m.start), max(u.start, m.start)):
                    if m.field != "msg":
                        m.uncertain = True
                        break

    def _same_scope(self, a: int, b: int) -> bool:
        lo, hi = min(a, b), max(a, b)
        if self.clauses[lo] != self.clauses[hi]:
            return False
        return not any(t in BOUNDARY_WORDS for t in self.tokens[lo:hi])

    def _language(self, hint: str | None) -> str:
        if hint in ("de", "en"):
            return hint
        # words listed in both languages ("mayday", "sos", "gps") count only when there is nothing else
        listed = {"de": set(), "en": set()}
        for p in self.voc.phrases:
            listed.get(p.lang, set()).add(p.tokens)
        both = listed["de"] & listed["en"]
        mine = [m for m in self.matches if tuple(self.tokens[m.start:m.end]) not in both] or self.matches
        de = sum(1 for m in mine if m.lang == "de")
        en = sum(1 for m in mine if m.lang == "en")
        return "de" if de > en else "en" if en > de else "unknown"

    # --- helpers over matches
    def effective(self, field_name: str) -> list[Match]:
        """Matches of one field after negation and uncertainty. Negated values flip or drop."""
        out = []
        for m in self.matches:
            if m.field != field_name or m.uncertain:
                continue
            if m.negated:
                flipped = FLIP.get((m.field, m.code))
                if flipped:
                    out.append(Match(m.field, flipped, m.start, m.end, m.cost, m.lang, flipped=True))
                continue
            out.append(m)
        return out

    def cue(self, kind: str) -> list[Match]:
        return [m for m in self.matches if m.field == "msg" and m.code == kind and not m.negated]

    def number_at(self, i: int) -> tuple[int, int] | None:
        if i >= len(self.tokens):
            return None
        tok = self.tokens[i]
        if tok.isdigit():
            return int(tok), 1
        for words, value in self.voc.numbers.items():
            if self.tokens[i:i + len(words)] == list(words) and len(words) > 0:
                return value, len(words)
        return None

    def number_ending_at(self, i: int) -> int | None:
        if i < 0:
            return None
        for n in (2, 1):
            got = self.number_at(i - n + 1) if i - n + 1 >= 0 else None
            if got and got[1] == n:
                return got[0]
        return None

    def noun_class(self, j: int) -> tuple[bool, bool, int]:
        """Is the word group at j a helper noun, a person noun? Returns (helper, person, length)."""
        best = (False, False, 0)
        age_phrases = {p.tokens for p in self.voc.phrases if p.field == "age"}
        for group, kind in ((self.voc.helpers, 0), (self.voc.persons, 1), (tuple(age_phrases), 1)):
            for ptoks in group:
                hit = _match_at(self.tokens, self.clauses, j, ptoks, fuzzy=False)
                if hit:
                    n = hit[0] - j
                    h, p, ln = best
                    best = (h or kind == 0, p or kind == 1, max(ln, n))
        return best

    def quantities(self) -> list[dict]:
        """Every 'number (more) noun' in the text: {value, helper, person, clause, more, index}."""
        out = []
        for i in range(len(self.tokens)):
            got = self.number_at(i)
            if not got or got[0] < 2:
                continue
            value, n = got
            j = i + n
            more = False
            if j < len(self.tokens) and self.tokens[j] in MORE_WORDS:
                more, j = True, j + 1
            if j < len(self.tokens) and self.tokens[j] in MORE_WORDS and not more:
                more, j = True, j + 1
            if j >= len(self.tokens) or self.clauses[j] != self.clauses[i]:
                continue
            helper, person, _ = self.noun_class(j)
            if helper or person:
                out.append({"value": value, "helper": helper, "person": person, "clause": self.clauses[i], "more": more, "index": i})
        return out

    def clause_has_request(self, clause: int) -> bool:
        for m in self.matches:
            if self.clauses[m.start] != clause or m.negated:
                continue
            if (m.field == "msg" and m.code == "request") or m.field == "needs":
                return True
        return False

    def severity_and_text(self, m: Match) -> str:
        return " ".join(self.tokens[m.start:m.end])


# ---------------------------------------------------------------- building messages

def _flags(matches: list[Match], table: dict[str, int]) -> list[str]:
    return sorted({m.code for m in matches if m.code in table}, key=lambda c: table[c])


def _single(matches: list[Match], order: list[str] | None = None) -> tuple[str | None, bool]:
    """One value from several. Returns (value, conflict)."""
    codes = {m.code for m in matches}
    if not codes:
        return None, False
    if len(codes) == 1:
        return next(iter(codes)), False
    return None, True


def _build_found(run: _Run) -> tuple[dict | None, str | None]:
    body: dict[str, Any] = {}
    conflict = False
    # age: a specific group beats "adult" ("elderly man")
    ages = {m.code for m in run.effective("age")}
    if len(ages) > 1:
        ages.discard("adult")
    if len(ages) > 1:
        conflict = True
    elif ages:
        body["age"] = ages.pop()
    cons = {m.code for m in run.effective("consciousness")}
    if cons == {"conscious", "confused"}:
        cons = {"confused"}
    if len(cons) > 1:
        conflict = True
    elif cons:
        body["consciousness"] = cons.pop()
    breath = {m.code for m in run.effective("breathing")}
    if len(breath) > 1:
        conflict = True
    elif breath:
        body["breathing"] = breath.pop()
    mob = {m.code for m in run.effective("mobility")}
    if "trapped" in mob:
        mob.discard("cannot_walk")
    if len(mob) > 1:
        conflict = True
    elif mob:
        body["mobility"] = mob.pop()
    injuries = _flags(run.effective("injuries"), C.INJURIES)
    needs = _flags(run.effective("needs"), C.NEEDS)
    # a vague "injured" adds nothing when a specific injury is named
    if "other" in injuries and len(injuries) > 1:
        injuries.remove("other")
    if "other" in needs and len(needs) > 1:
        needs.remove("other")
    if injuries:
        body["injuries"] = injuries
    if needs:
        body["needs"] = needs
    count = responders = 0
    for q in run.quantities():
        both = q["helper"] and q["person"]
        is_responders = (q["helper"] and not q["person"]) or (both and (q["more"] or run.clause_has_request(q["clause"])))
        if is_responders and not responders:
            responders = q["value"]
        elif q["person"] and not is_responders and not count:
            count = q["value"]
    if count >= 2:
        body["count"] = min(count, C.MAX_COUNT)
    if responders >= 2:
        body["responders"] = min(responders, C.MAX_COUNT)
    if count >= 2 and re.search(r"\bone\b.{0,60}\b(?:and one|the other|and the other|another)\b|"
                                 r"\b(?:eine|einer|einen|ein)\b.{0,60}\b(?:die|der|den) ander\w*|\bund (?:eine|einer)\b",
                                 fold(run.text)):
        conflict = True
    if conflict:
        return None, R_CONFLICT
    return body, None


def _object_found(run: _Run) -> bool:
    """'Found a backpack' is not a person found."""
    for cue in run.cue("found"):
        for idx in (cue.end, cue.start - 1):
            step = 1 if idx == cue.end else -1
            while 0 <= idx < len(run.tokens) and run.tokens[idx] in DETERMINERS:
                idx += step
            if not 0 <= idx < len(run.tokens):
                continue
            if run.tokens[idx] in OBJECT_WORDS:
                return True
            if any(m.start <= idx < m.end and m.field in ("needs", "hazard.kind", "request.resource") for m in run.matches):
                return True
    return False


def _build_sector(run: _Run) -> tuple[dict | None, str | None]:
    numbers = set()
    for cue in run.cue("sector"):
        i = cue.end
        while i < len(run.tokens) and run.tokens[i] in ("nr", "no", "nummer", "number"):
            i += 1
        got = run.number_at(i)
        if got:
            numbers.add(got[0])
    if not numbers:
        return None, R_NO_TYPE
    if len(numbers) > 1:
        return None, R_AMBIGUOUS
    status = {m.code for m in run.effective("sector.status")}
    coverage = 0
    from_percent = False
    for i, tok in enumerate(run.tokens):
        if tok in PERCENT_WORDS and i > 0:
            n = run.number_ending_at(i - 1)
            if n and 0 < n <= 100:
                coverage, from_percent = n, True
    if not coverage:
        for words, value in run.voc.coverage_fixed.items():
            for i in range(len(run.tokens)):
                if _match_at(run.tokens, run.clauses, i, words, fuzzy=False):
                    coverage = value
    if "partial" in status:
        status = {"partial"}
    if not status and coverage and from_percent:
        status = {"partial"} if coverage < 100 else {"cleared"}
    if len(status) != 1:
        return None, R_NO_TYPE if not status else R_CONFLICT
    st = next(iter(status))
    if coverage and coverage < 100 and st == "cleared":
        st = "partial"
    body: dict[str, Any] = {"sector": numbers.pop(), "status": st}
    if coverage and st == "partial":
        body["coverage"] = coverage
    return body, None


def _build_request(run: _Run) -> tuple[dict | None, str | None]:
    res = [m for m in run.effective("request.resource")]
    if not res:
        return None, R_MISSING
    first = res[0]
    body: dict[str, Any] = {"resource": first.code}
    count = run.number_ending_at(first.start - 1)
    if count is None and first.start - 2 >= 0 and run.tokens[first.start - 1] in MORE_WORDS:
        count = run.number_ending_at(first.start - 2)
    if count and count >= 2:
        body["count"] = min(count, C.MAX_COUNT)
    urg = run.effective("request.urgency")
    if urg:
        body["urgency"] = urg[0].code
    return body, None


def _build_hazard(run: _Run) -> tuple[dict | None, str | None]:
    kinds = run.effective("hazard.kind")
    kinds = [m for m in kinds if m.code != "other"] or kinds
    if not kinds:
        return None, R_MISSING
    body: dict[str, Any] = {"kind": kinds[0].code}
    sev = {m.code for m in run.effective("hazard.severity")}
    if sev:
        body["severity"] = max(sev, key=lambda s: C.SEVERITY.get(s, 0))
    for i, tok in enumerate(run.tokens):
        if tok in UNIT_M or tok in UNIT_KM:
            if i == 0 or not run.tokens[i - 1][0].isdigit():
                continue
            value = float(run.tokens[i - 1].replace(",", "."))
            metres = int(round(value * (1000 if tok in UNIT_KM else 1)))
            if 0 < metres <= C.MAX_RADIUS_M:
                body["radius"] = metres
                break
    return body, None


def _build_mayday(run: _Run) -> tuple[dict | None, str | None]:
    body: dict[str, Any] = {}
    reasons = [m for m in run.effective("mayday.reason") if m.code != "other"]
    if reasons:
        body["reason"] = reasons[0].code
    elif run.effective("injuries"):
        body["reason"] = "injured"
    for q in run.quantities():
        if q["value"] >= 1:
            body["persons"] = min(q["value"], C.MAX_COUNT)
            break
    else:
        for i, tok in enumerate(run.tokens):
            if tok in ("of", "von") and i + 1 < len(run.tokens) and run.tokens[i + 1] in ("us", "uns"):
                n = run.number_ending_at(i - 1)
                if n and n >= 1:
                    body["persons"] = min(n, C.MAX_COUNT)
    return body, None


def _priority(run: _Run, kind: str, body: dict) -> str | None:
    urgency_spans = {(m.start, m.end) for m in run.matches if m.field == "request.urgency"} if kind == "request" else set()
    levels = [PRIORITY_LEVEL[m.code] for m in run.effective("priority") if (m.start, m.end) not in urgency_spans]
    level = max(levels, default=0)
    if kind == "found" and body.get("breathing") == "none":
        level = max(level, PRIORITY_LEVEL["critical"])
    if kind == "mayday" or not level:
        return None
    name = next(n for n, v in PRIORITY_LEVEL.items() if v == level)
    return None if name == C.DEFAULT_PRIORITY[kind] else name


def _cut_text(text: str) -> str:
    text = " ".join(text.split())
    raw = text.encode("utf-8")[:MAX_TEXT_BYTES]
    return raw.decode("utf-8", "ignore")


def _encode_error(msg: Mapping) -> str | None:
    try:
        encode(msg)
        return None
    except CodecError as exc:
        return exc.code


def fit_text(msg: Mapping) -> Mapping:
    """Make a text message fit into one packet. Same rule as TextFit.fitText in the Kotlin engine.

    Only a text message, and only when it is too large with its position and id (over 200 bytes): the text is cut
    to the longest prefix that ends on a whole UTF-8 character, trailing spaces go, and "…" is added, so the whole
    message encodes to at most 200 bytes. The position and every other field stay. Every other message, and a text
    that fits or is broken for another reason, comes back unchanged (the same object).
    Example: a 180-byte text fallback plus a position and an id is 201 to 204 bytes; this cuts a few bytes off."""
    try:
        if message_type(msg) != "text":
            return msg
    except CodecError:
        return msg
    body = msg.get("text")
    if not isinstance(body, Mapping) or not isinstance(body.get("text"), str) or _encode_error(msg) != "too_large":
        return msg
    raw = body["text"].encode("utf-8")
    for cut in range(len(raw) - 1, -1, -1):      # longest first: the size only shrinks with the prefix
        if (raw[cut] & 0xC0) == 0x80:             # a cut here would split a character
            continue
        candidate = {**msg, "text": {**body, "text": raw[:cut].decode("utf-8").rstrip() + ELLIPSIS}}
        if _encode_error(candidate) is None:
            return candidate
    return msg


def fit_plain(text: str, limit: int = C.MAX_ENCODE_BYTES) -> str:
    """Make a plain Meshtastic text (port 1) fit into `limit` UTF-8 bytes. Same rule as TextFit.fitPlain in Kotlin.

    A text that fits comes back unchanged. A longer one is cut to the longest prefix that ends on a whole UTF-8
    character, trailing spaces go, and "…" is added, so the result is at most `limit` bytes.
    Example: a 230-byte readable line becomes 197 bytes of it plus "…" (200 bytes)."""
    raw = text.encode("utf-8")
    if len(raw) <= limit:
        return text
    cut = max(0, limit - len(ELLIPSIS.encode("utf-8")))
    while cut > 0 and (raw[cut] & 0xC0) == 0x80:       # a cut here would split a character
        cut -= 1
    return raw[:cut].decode("utf-8").rstrip() + ELLIPSIS


def _fallback(run: _Run | None, text: str, reason: str, cues: list[str] | None = None) -> Extraction:
    cut = _cut_text(text)
    msg = {"text": {"text": cut}} if cut else None
    return Extraction(text, msg, True, reason if cut else R_EMPTY, "rules", run.lang if run else "unknown",
                      _evidence(run) if run else [], cues or [], (run.notes if run else []), run.pos if run else None)


def _evidence(run: _Run) -> list[dict]:
    return [{"field": m.field, "code": m.code, "words": run.severity_and_text(m), "negated": m.negated,
             "uncertain": m.uncertain, "typo": bool(m.cost)} for m in run.matches if m.field != "msg" or True]


def extract(text: str, lang: str | None = None, vocab: Vocab | None = None) -> Extraction:
    """Extract one canonical message from one report. Never raises on odd input."""
    text = text or ""
    if not text.strip():
        return _fallback(None, "", R_EMPTY)
    run = _Run(text, vocab or load_vocab(), lang)
    cues = [k for k in ("mayday", "sector", "found", "request", "hazard") if run.cue(k)]
    body_type: str | None = None
    body: dict | None = None
    reason: str | None = None

    # type decision: Mayday > Sector (with a status) > Found > Request > Hazard
    mayday_reasons = [m for m in run.effective("mayday.reason") if m.code != "other"]
    if run.cue("mayday") or mayday_reasons:
        body_type = "mayday"
        body, reason = _build_mayday(run)
    elif run.cue("sector") and (sec := _build_sector(run))[0] is not None:
        body_type, body, reason = "sector", sec[0], None
    else:
        sector_problem = _build_sector(run)[1] if run.cue("sector") else None
        has_age = bool(run.effective("age"))
        implied_found = has_age and any(run.effective(f) for f in ("consciousness", "breathing", "mobility", "injuries"))
        generic = (not run.cue("request") and not run.cue("hazard") and not run.effective("hazard.kind")
                   and any(run.effective(f) for f in ("consciousness", "breathing", "mobility", "injuries"))
                   and any(_match_at(run.tokens, run.clauses, i, pt, fuzzy=False)
                           for i in range(len(run.tokens)) for pt in run.voc.person_nouns))
        if run.cue("found") or implied_found or generic:
            if run.cue("found") and _object_found(run):
                return _fallback(run, text, R_OBJECT, cues)
            body_type = "found"
            body, reason = _build_found(run)
        elif sector_problem == R_AMBIGUOUS:
            return _fallback(run, text, R_AMBIGUOUS, cues)
        elif run.cue("request"):
            body_type = "request"
            body, reason = _build_request(run)
        elif run.cue("hazard") or run.effective("hazard.kind"):
            body_type = "hazard"
            body, reason = _build_hazard(run)
    if body_type is None or body is None:
        return _fallback(run, text, reason or R_NO_TYPE, cues)

    msg: dict[str, Any] = {}
    prio = _priority(run, body_type, body)
    if prio:
        msg["priority"] = prio
    if run.pos:
        msg["pos"] = dict(run.pos)
    msg[body_type] = body
    probe = dict(msg)
    if body_type in C.ID_REQUIRED:
        probe["id"] = 1
    try:
        encode(probe)
    except CodecError as exc:
        run.notes.append(f"not encodable: {exc}")
        return _fallback(run, text, R_INVALID, cues)
    return Extraction(text, msg, False, None, "rules", run.lang, _evidence(run), cues, run.notes, run.pos)
