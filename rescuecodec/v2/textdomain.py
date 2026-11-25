"""Domain frames: chat texts with a small team vocabulary of N-bit codes (spec/SPEC.md section 3.8).

    stream = encode_stream("Patient is conscious, bleeding from the leg.", "medical")
    decode_stream(stream, "medical")          # -> the same text, byte for byte

A domain vocabulary has 2^N - 8 entries (N = 10 for every vocabulary of version 1: 1016 entries). The stream is a bit
stream of N-bit codes, most significant bit first, the last byte padded with zero bits:
    0 .. 2^N-9      entry: line number - 1 of spec/vocab/domain-<domain>-v1.txt
    2^N-8  ESC16    the next 16 bits are a token of word dictionary 2 (0x0000..0xEFFF)
    2^N-7  CAP      the next entry (or escaped token) with its first letter a-z written A-Z
    2^N-6  UPPER    the next entry (or escaped token) with every letter a-z written A-Z
    2^N-5  GLUE     no space before the next entry
    2^N-4  LIT      the next 8 bits are a length L (1..255), then L bytes of UTF-8, copied exactly
    2^N-3 .. 2^N-1  reserved: the stream is malformed
Spaces, punctuation and literals follow SPEC section 3.6.3 exactly (an escaped token counts as an entry). The encoder
is the shortest-stream search of section 3.6.4 with costs in bits, so Python, Kotlin and TypeScript write the same
bytes. Never a near match: a word that is in neither the vocabulary nor word dictionary 2 goes as a literal.
"""
from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from functools import lru_cache

from rescuecodec.v2 import textwords
from rescuecodec.v2.textwords import (LEFT_ATTACH, MAX_TEXT_BYTES, RIGHT_ATTACH, WordDict, WordsError, _spec_dir,
                                      match, split_pieces)

#: Version of the frame rules (spec/vocab/domains.yaml `format`).
FORMAT = 1
#: The top 8 codes of every code space are controls.
RESERVED = 8
ESC16, CAP, UPPER, GLUE, LIT = range(5)          # offsets from 2^N - 8
#: Word dictionary that ESC16 tokens come from.
GENERAL_DICT = 2
#: Longest literal in one LIT code (the length is 8 bits).
MAX_LITERAL = 255


@dataclass(frozen=True)
class Vocab:
    id: int                  # the byte after 0x03 on the air
    domain: str              # stable name, e.g. "sar"; the same domain may get a new id for a new version
    label: str               # long name for settings, e.g. "Search & Rescue"
    short: str               # short name for message tags, e.g. "S&R"
    version: int
    bits: int                # N: width of a code
    file: str                # in spec/vocab
    sha256: str
    entries: int             # 2^N - 8


#: Vocabulary id -> vocabulary (spec/vocab/domains.yaml). Ids 0 and 6..255 are not assigned.
VOCABS: dict[int, Vocab] = {v.id: v for v in (
    Vocab(1, "emergency", "Emergency", "Emergency", 1, 10, "domain-emergency-v1.txt",
          "e98e8c9026b9f75b29a681dfaee2a49ebac39fbbea4d1471e60949ac71164664", 1016),
    Vocab(2, "sar", "Search & Rescue", "S&R", 1, 10, "domain-sar-v1.txt",
          "444798678a105827cf783b990c0ceda05b4cf927b4846c15e0345213f60092ee", 1016),
    Vocab(3, "fire", "Firefighting", "Fire", 1, 10, "domain-fire-v1.txt",
          "d37f489ed55a944d59fcc3b53c985da2facf3aefa81cf9053240d80cf62dc9a2", 1016),
    Vocab(4, "hiking", "Hiking / Mountain", "Hiking", 1, 10, "domain-hiking-v1.txt",
          "3e03a004a0696afc41738d71280e13125390eff85f3ab073d6b9c7b510e6464a", 1016),
    Vocab(5, "medical", "Medical / First aid", "Medical", 1, 10, "domain-medical-v1.txt",
          "21a76e1f01d3aac7291875f6b863244fc873d724cdae4afddd7a3cfcade6be3b", 1016),
)}
#: Domain name -> the id new texts are encoded with.
CURRENT: dict[str, int] = {v.domain: v.id for v in sorted(VOCABS.values(), key=lambda v: v.version)}
#: Choices of the "team vocabulary" setting: auto (every domain), general (no domain frame) or one domain.
SETTINGS = ("auto", "general", *CURRENT)


def vocab_id(vocab: int | str) -> int:
    """A vocabulary id from an id or a domain name ("sar"). KeyError when neither is known."""
    if isinstance(vocab, bool):
        raise KeyError(vocab)
    if isinstance(vocab, int):
        if vocab not in VOCABS:
            raise KeyError(vocab)
        return vocab
    if isinstance(vocab, str) and vocab in CURRENT:
        return CURRENT[vocab]
    raise KeyError(vocab)


def candidates(setting: str) -> tuple[int, ...]:
    """Vocabulary ids the sender tries for a setting: auto = all (by id), general = none, a domain = that one.
    ValueError for an unknown setting."""
    if setting == "auto":
        return tuple(sorted(CURRENT.values()))
    if setting == "general":
        return ()
    if setting in CURRENT:
        return (CURRENT[setting],)
    raise ValueError(f"team vocabulary must be one of {', '.join(SETTINGS)}")


@lru_cache(maxsize=None)
def vocabulary(vocab: int | str) -> WordDict:
    """The entries of a vocabulary. KeyError for an unknown id, ValueError for a damaged file (SHA-256 differs)."""
    v = VOCABS[vocab_id(vocab)]
    data = (_spec_dir() / "vocab" / v.file).read_bytes()
    if hashlib.sha256(data).hexdigest() != v.sha256:
        raise ValueError(f"spec/vocab/{v.file} is damaged (SHA-256 differs; check line endings)")
    d = WordDict.from_bytes(data)
    if len(d.entries) != v.entries or v.entries != (1 << v.bits) - RESERVED:
        raise ValueError(f"spec/vocab/{v.file} must have {(1 << v.bits) - RESERVED} entries")
    return d


def available(vocab: int | str) -> bool:
    """True when this library has the vocabulary and word dictionary 2 (for ESC16), both matching their SHA-256."""
    try:
        vocabulary(vocab)
    except (KeyError, ValueError, OSError):
        return False
    return textwords.available(GENERAL_DICT)


# ------------------------------------------------------------------ bits

class _Writer:
    def __init__(self) -> None:
        self.out = bytearray()
        self.acc = 0
        self.n = 0

    def put(self, value: int, width: int) -> None:
        self.acc = (self.acc << width) | value
        self.n += width
        while self.n >= 8:
            self.n -= 8
            self.out.append((self.acc >> self.n) & 0xFF)
        self.acc &= (1 << self.n) - 1

    def done(self) -> bytes:
        if self.n:
            self.out.append((self.acc << (8 - self.n)) & 0xFF)     # zero padding, fewer than 8 bits
        return bytes(self.out)


class _Reader:
    def __init__(self, data: bytes) -> None:
        self.data = data
        self.pos = 0
        self.total = 8 * len(data)

    def left(self) -> int:
        return self.total - self.pos

    def get(self, width: int) -> int:
        value = 0
        for _ in range(width):
            byte = self.data[self.pos >> 3]
            value = (value << 1) | ((byte >> (7 - (self.pos & 7))) & 1)
            self.pos += 1
        return value


def _len8(s: str) -> int:
    return len(s.encode("utf-8"))


def _quote(s: str) -> str:
    """A string in double quotes with JSON escapes (the same in every language), for traces."""
    return json.dumps(s, ensure_ascii=False)


def literal_chunks(run: bytes) -> list[bytes]:
    """A literal run as LIT pieces of at most 255 bytes: each the longest prefix that ends on a whole character."""
    out = []
    while run:
        cut = min(MAX_LITERAL, len(run))
        while cut < len(run) and (run[cut] & 0xC0) == 0x80:
            cut -= 1
        out.append(run[:cut])
        run = run[cut:]
    return out


# ------------------------------------------------------------------ encode

_TOK, _LIT, _START = 0, 1, 2
_INF = 1 << 40


def encode_stream(text: str, vocab: int | str) -> bytes:
    """Text -> the shortest domain bit stream (SPEC section 3.8.4), padded to whole bytes. Raises WordsError
    (too_large), KeyError for an unknown vocabulary, ValueError for a damaged one."""
    v = VOCABS[vocab_id(vocab)]
    dd = vocabulary(v.id)
    d2 = textwords.dictionary(GENERAL_DICT)
    nb = v.bits
    base = (1 << nb) - RESERVED
    lit_head = nb + 8
    if _len8(text) > MAX_TEXT_BYTES:
        raise WordsError("too_large", f"a compact text is at most {MAX_TEXT_BYTES} bytes")
    gaps, pieces = split_pieces(text)
    n = len(pieces)
    span_max = max(dd.max_span, d2.max_span)
    cost = [[_INF, _INF] for _ in range(n + 1)]
    back: list[list[tuple | None]] = [[None, None] for _ in range(n + 1)]

    def best(span: str) -> tuple[int, int, bool, int] | None:
        """(index, case control or 0, escaped, bits): the vocabulary first (always cheaper), else word dictionary 2."""
        m = match(span, dd)
        if m is not None:
            return m[0], m[1], False, nb * (2 if m[1] else 1)
        m = match(span, d2)
        if m is not None:
            return m[0], m[1], True, nb * (2 if m[1] else 1) + 16
        return None

    for k in range(n):
        g = gaps[k]
        g_bits = 8 * _len8(g)
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
                idx, ctrl, escaped, ct = m
                implied = st == _TOK and span[0] not in LEFT_ATTACH and pieces[k - 1][-1] not in RIGHT_ATTACH
                if implied and g == " ":
                    add, mode = ct, "none"
                elif implied and g == "":
                    add, mode = nb + ct, "glue"
                elif g == "":
                    add, mode = ct, "none"
                elif st == _LIT:
                    add, mode = g_bits + ct, "gap"
                else:
                    add, mode = lit_head + g_bits + ct, "gap"
                if c0 + add < cost[j + 1][_TOK]:
                    cost[j + 1][_TOK] = c0 + add
                    back[j + 1][_TOK] = (k, st, "tok", idx, ctrl, escaped, mode)
            add = g_bits + 8 * _len8(pieces[k]) + (0 if st == _LIT else lit_head)
            if c0 + add < cost[k + 1][_LIT]:
                cost[k + 1][_LIT] = c0 + add
                back[k + 1][_LIT] = (k, st, "lit")

    last = gaps[n]
    steps: list[tuple] = []
    if n:
        end_tok = cost[n][_TOK] + (0 if last == "" else lit_head + 8 * _len8(last))
        end_lit = cost[n][_LIT] + 8 * _len8(last)
        pos, st = n, (_TOK if end_tok <= end_lit else _LIT)
        while pos > 0:
            step = back[pos][st]
            steps.append(step)
            pos, st = step[0], step[1]
        steps.reverse()

    w = _Writer()
    run = bytearray()

    def flush() -> None:
        for chunk in literal_chunks(bytes(run)):
            w.put(base + LIT, nb)
            w.put(len(chunk), 8)
            for byte in chunk:
                w.put(byte, 8)
        run.clear()

    for step in steps:
        k = step[0]
        if step[2] == "lit":
            run.extend((gaps[k] + pieces[k]).encode("utf-8"))
            continue
        _, _, _, idx, ctrl, escaped, mode = step
        if mode == "gap":
            run.extend(gaps[k].encode("utf-8"))
        flush()
        if mode == "glue":
            w.put(base + GLUE, nb)
        if ctrl:
            w.put(base + (CAP if ctrl == textwords.CAP else UPPER), nb)
        if escaped:
            w.put(base + ESC16, nb)
            w.put(idx, 16)
        else:
            w.put(idx, nb)
    run.extend(last.encode("utf-8"))
    flush()
    return w.done()


# ------------------------------------------------------------------ decode

def decode_stream(data: bytes, vocab: int | str, trace: list[str] | None = None) -> str:
    """Domain bit stream -> text. Raises WordsError (malformed, too_large); KeyError for an unknown vocabulary,
    ValueError for a damaged one. `trace`, when given, gets one line per code for tools and test vectors, e.g.
    'CAP', 'E 31 "search"', 'ESC16 0x0D0A "pizza"', 'LIT 3 " 4."', 'GLUE'."""
    v = VOCABS[vocab_id(vocab)]
    dd = vocabulary(v.id)
    d2 = textwords.dictionary(GENERAL_DICT)
    nb = v.bits
    base = (1 << nb) - RESERVED
    r = _Reader(bytes(data))
    out = bytearray()
    prev = _START                  # last unit written: start, entry or literal
    prev_right = False             # the last entry ends with ( [ {
    glue = False
    case = 0                       # 0, CAP or UPPER
    while r.left() >= nb:
        code = r.get(nb)
        if code < base or code == base + ESC16:
            if code < base:
                if code >= len(dd.entries):
                    raise WordsError("malformed", f"code {code} is not in the vocabulary ({len(dd.entries)} entries)")
                entry = dd.entries[code]
                if trace is not None:
                    trace.append(f"E {code} {_quote(entry)}")
            else:
                if r.left() < 16:
                    raise WordsError("malformed", "the escaped token is cut off")
                token = r.get(16)
                if token >= textwords.FIRST_CONTROL or token >= len(d2.entries):
                    raise WordsError("malformed", f"escaped token 0x{token:04X} is not in word dictionary {GENERAL_DICT}")
                entry = d2.entries[token]
                if trace is not None:
                    trace.append(f"ESC16 0x{token:04X} {_quote(entry)}")
            if case == CAP and "a" <= entry[0] <= "z":
                entry = chr(ord(entry[0]) - 32) + entry[1:]
            elif case == UPPER:
                entry = textwords._ascii_upper(entry)
            if prev == _TOK and not glue and entry[0] not in LEFT_ATTACH and not prev_right:
                out.append(0x20)
            out.extend(entry.encode("utf-8"))
            prev, prev_right, glue, case = _TOK, entry[-1] in RIGHT_ATTACH, False, 0
        elif code == base + LIT:
            if glue or case:
                raise WordsError("malformed", "a control must be followed by an entry, not a literal")
            if r.left() < 8:
                raise WordsError("malformed", "the literal length is cut off")
            size = r.get(8)
            if size == 0:
                raise WordsError("malformed", "empty literal")
            if r.left() < 8 * size:
                raise WordsError("malformed", f"the literal of {size} bytes is cut off")
            chunk = bytes(r.get(8) for _ in range(size))
            try:
                chunk.decode("utf-8")
            except UnicodeDecodeError as exc:
                raise WordsError("malformed", "a literal is not valid UTF-8") from exc
            out.extend(chunk)
            prev = _LIT
            if trace is not None:
                trace.append(f"LIT {size} {_quote(chunk.decode('utf-8'))}")
        elif code in (base + CAP, base + UPPER):
            if case:
                raise WordsError("malformed", "two case controls before one entry")
            case = CAP if code == base + CAP else UPPER
            if trace is not None:
                trace.append("CAP" if case == CAP else "UPPER")
        elif code == base + GLUE:
            if glue:
                raise WordsError("malformed", "two GLUE controls before one entry")
            glue = True
            if trace is not None:
                trace.append("GLUE")
        else:
            raise WordsError("malformed", f"reserved code {code} (control {code - base})")
        if len(out) > MAX_TEXT_BYTES:
            raise WordsError("too_large", f"decodes to more than {MAX_TEXT_BYTES} bytes")
    rest = r.left()
    if rest >= 8 or (rest and r.get(rest)):
        raise WordsError("malformed", "the padding must be fewer than 8 zero bits")
    if glue or case:
        raise WordsError("malformed", "the stream ends with a control")
    if not out:
        raise WordsError("malformed", "empty stream")
    return out.decode("utf-8")
