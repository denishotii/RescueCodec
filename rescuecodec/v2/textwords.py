"""Compact texts: 2-byte word tokens from a shared word dictionary (spec/SPEC.md section 3.6).

    data = encode_words("We found Aylin Demir", 1)   # [We] [found] [literal " Aylin Demir"]
    decode_words(data, 1)                           # -> "We found Aylin Demir", byte for byte

The stream is a list of 16-bit big-endian units:
    0x0000..0xEFFF  dictionary entry (line number - 1 of spec/vocab/words-dict-v1.txt)
    0xF001          CAP    next entry with its first letter a-z written A-Z
    0xF002          UPPER  next entry with every letter a-z written A-Z
    0xF003          GLUE   no space before the next entry
    0xF800 + N      literal: the next N bytes (1..2047) are UTF-8, copied exactly
    anything else   reserved: the stream is malformed
Spaces: the decoder writes one space between two adjacent entries, except before an entry that starts with
. , ; : ! ? ) ] } or the ellipsis, and after an entry that ends with ( [ {. Never next to a literal: a literal carries
its own spaces. The encoder finds the shortest stream (dynamic programming, fixed tie rules) so Python, Kotlin and
TypeScript write the same bytes. Never a near match: a word that is not exactly in the dictionary (up to the
CAP/UPPER forms) goes as a literal.
"""
from __future__ import annotations

import hashlib
import os
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

#: Largest text a receiver decodes (bytes of UTF-8), same as for DEFLATE texts.
MAX_TEXT_BYTES = 1024
#: Dictionary versions: number -> (file in spec/vocab, SHA-256 of its bytes).
DICTS = {1: ("words-dict-v1.txt", "ffe93afb4623fcd364479169281ca745cc263317a4f83ce27f77d8d37b26a0d8")}
#: The dictionary new texts are encoded with.
CURRENT_DICT = 1

CAP = 0xF001
UPPER = 0xF002
GLUE = 0xF003
LITERAL = 0xF800
MAX_LITERAL = 0x07FF            # 2047 bytes in one run
FIRST_CONTROL = 0xF000          # 0xF000..0xF7FF controls (only F001..F003 defined), 0xF800.. literals

WHITESPACE = " \t\n\r"
#: Characters that are a piece on their own.
SPLIT = '.,;:!?()[]{}"…'
#: An entry starting with one of these gets no space before it.
LEFT_ATTACH = ".,;:!?)]}…"
#: An entry ending with one of these gets no space after it.
RIGHT_ATTACH = "([{"


class WordsError(ValueError):
    """`code` is a codec error code: malformed, too_large."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class WordDict:
    entries: tuple[str, ...]
    index: dict[str, int]
    max_span: int                # most pieces in one entry

    @classmethod
    def from_entries(cls, entries: list[str]) -> "WordDict":
        if len(entries) > FIRST_CONTROL:
            raise ValueError(f"at most {FIRST_CONTROL} entries")
        index: dict[str, int] = {}
        span = 1
        for i, entry in enumerate(entries):
            if not entry or entry in index or any(c in "\t\n\r" for c in entry) or entry != entry.strip(" "):
                raise ValueError(f"bad or repeated entry {i}: {entry!r}")
            index[entry] = i
            gaps, pieces = split_pieces(entry)
            span = max(span, len(pieces))
        return cls(tuple(entries), index, span)

    @classmethod
    def from_bytes(cls, data: bytes) -> "WordDict":
        text = data.decode("utf-8")
        if not text.endswith("\n"):
            raise ValueError("the dictionary must end with a line break")
        return cls.from_entries(text[:-1].split("\n"))


def _spec_dir() -> Path:          # same as render.spec_dir (not imported: render imports the codec)
    env = os.getenv("RESCUECODEC_SPEC_DIR")
    return Path(env) if env else Path(__file__).resolve().parents[2] / "spec"


@lru_cache(maxsize=None)
def dictionary(version: int) -> WordDict:
    """The word dictionary of a version. KeyError for an unknown version, ValueError for a damaged file."""
    name, sha = DICTS[version]
    data = (_spec_dir() / "vocab" / name).read_bytes()
    if hashlib.sha256(data).hexdigest() != sha:
        raise ValueError(f"spec/vocab/{name} is damaged (SHA-256 differs; check line endings)")
    return WordDict.from_bytes(data)


def available(version: int) -> bool:
    """True when this library has the dictionary `version` and its bytes match the SHA-256 of the spec."""
    try:
        dictionary(version)
        return True
    except (KeyError, ValueError, OSError):
        return False


# ------------------------------------------------------------------ pieces

def split_pieces(text: str) -> tuple[list[str], list[str]]:
    """Text -> (gaps, pieces) with text == gaps[0] + pieces[0] + gaps[1] + ... + pieces[n-1] + gaps[n].

    A gap is a run of space, tab, CR, LF (maybe empty). A piece is one SPLIT character, or a run of anything else."""
    gaps: list[str] = []
    pieces: list[str] = []
    i, n = 0, len(text)
    j = i
    while j < n and text[j] in WHITESPACE:
        j += 1
    gaps.append(text[i:j])
    i = j
    while i < n:
        if text[i] in SPLIT:
            j = i + 1
        else:
            j = i
            while j < n and text[j] not in WHITESPACE and text[j] not in SPLIT:
                j += 1
        pieces.append(text[i:j])
        i = j
        while j < n and text[j] in WHITESPACE:
            j += 1
        gaps.append(text[i:j])
        i = j
    return gaps, pieces


def _ascii_lower(s: str) -> str:
    return "".join(chr(ord(c) + 32) if "A" <= c <= "Z" else c for c in s)


def _ascii_upper(s: str) -> str:
    return "".join(chr(ord(c) - 32) if "a" <= c <= "z" else c for c in s)


def match(s: str, d: WordDict) -> tuple[int, int] | None:
    """(entry index, control or 0) for the text `s`: the exact entry, else CAP of an entry, else UPPER of an entry."""
    idx = d.index.get(s)
    if idx is not None:
        return idx, 0
    if "A" <= s[0] <= "Z":
        idx = d.index.get(chr(ord(s[0]) + 32) + s[1:])
        if idx is not None:
            return idx, CAP
    low = _ascii_lower(s)
    if low != s and _ascii_upper(low) == s:
        idx = d.index.get(low)
        if idx is not None:
            return idx, UPPER
    return None


def _len8(s: str) -> int:
    return len(s.encode("utf-8"))


# ------------------------------------------------------------------ encode

_TOK, _LIT, _START = 0, 1, 2
_INF = 1 << 30


def encode_words(text: str, version: int | WordDict = CURRENT_DICT) -> bytes:
    """Text -> the shortest token stream (SPEC section 3.6, "Encoder"). Raises WordsError (too_large)."""
    d = version if isinstance(version, WordDict) else dictionary(version)
    if _len8(text) > MAX_TEXT_BYTES:
        raise WordsError("too_large", f"a compact text is at most {MAX_TEXT_BYTES} bytes")
    gaps, pieces = split_pieces(text)
    n = len(pieces)
    cost = [[_INF, _INF] for _ in range(n + 1)]
    back: list[list[tuple | None]] = [[None, None] for _ in range(n + 1)]

    def relax(pos: int, state: int, value: int, step: tuple) -> None:
        if value < cost[pos][state]:
            cost[pos][state] = value
            back[pos][state] = step

    for k in range(n):
        g = gaps[k]
        for st in ((_START,) if k == 0 else (_TOK, _LIT)):
            c0 = 0 if st == _START else cost[k][st]
            if c0 >= _INF:
                continue
            span = ""
            for j in range(k, min(n, k + d.max_span)):
                span = pieces[k] if j == k else span + gaps[j] + pieces[j]
                m = match(span, d)
                if m is None:
                    continue
                idx, ctrl = m
                ct = 4 if ctrl else 2
                implied = st == _TOK and span[0] not in LEFT_ATTACH and pieces[k - 1][-1] not in RIGHT_ATTACH
                if implied and g == " ":
                    add, mode = ct, "none"
                elif implied and g == "":
                    add, mode = 2 + ct, "glue"
                elif g == "":
                    add, mode = ct, "none"
                elif st == _LIT:
                    add, mode = _len8(g) + ct, "gap"
                else:
                    add, mode = 2 + _len8(g) + ct, "gap"
                relax(j + 1, _TOK, c0 + add, (k, st, "tok", idx, ctrl, mode))
            add = _len8(g) + _len8(pieces[k]) + (0 if st == _LIT else 2)
            relax(k + 1, _LIT, c0 + add, (k, st, "lit"))

    last = gaps[n]
    if n == 0:
        steps: list[tuple] = []
    else:
        end_tok = cost[n][_TOK] + (0 if last == "" else 2 + _len8(last))
        end_lit = cost[n][_LIT] + _len8(last)
        pos, st = n, (_TOK if end_tok <= end_lit else _LIT)
        steps = []
        while pos > 0:
            step = back[pos][st]
            steps.append(step)
            pos, st = step[0], step[1]
        steps.reverse()

    out = bytearray()
    run = bytearray()

    def flush() -> None:
        if run:
            out.extend((LITERAL + len(run)).to_bytes(2, "big"))
            out.extend(run)
            run.clear()

    for step in steps:
        k = step[0]
        if step[2] == "lit":
            run.extend((gaps[k] + pieces[k]).encode("utf-8"))
            continue
        _, _, _, idx, ctrl, mode = step
        if mode == "gap":
            run.extend(gaps[k].encode("utf-8"))
        flush()
        if mode == "glue":
            out.extend(GLUE.to_bytes(2, "big"))
        if ctrl:
            out.extend(ctrl.to_bytes(2, "big"))
        out.extend(idx.to_bytes(2, "big"))
    run.extend(last.encode("utf-8"))
    flush()
    return bytes(out)


# ------------------------------------------------------------------ decode

def decode_words(data: bytes, version: int | WordDict) -> str:
    """Token stream -> text. Raises WordsError (malformed, too_large); KeyError for an unknown dictionary."""
    d = version if isinstance(version, WordDict) else dictionary(version)
    data = bytes(data)
    out = bytearray()
    prev = _START                  # last unit written: start, token or literal
    prev_right = False             # the last token ends with ( [ {
    glue = False
    case = 0
    i = 0
    while i < len(data):
        if i + 2 > len(data):
            raise WordsError("malformed", "the token stream is cut off (half a token)")
        code = (data[i] << 8) | data[i + 1]
        i += 2
        if code < FIRST_CONTROL:
            if code >= len(d.entries):
                raise WordsError("malformed", f"token {code} is not in the dictionary ({len(d.entries)} entries)")
            entry = d.entries[code]
            if case == CAP and "a" <= entry[0] <= "z":
                entry = chr(ord(entry[0]) - 32) + entry[1:]
            elif case == UPPER:
                entry = _ascii_upper(entry)
            if prev == _TOK and not glue and entry[0] not in LEFT_ATTACH and not prev_right:
                out.append(0x20)
            out.extend(entry.encode("utf-8"))
            prev, prev_right, glue, case = _TOK, entry[-1] in RIGHT_ATTACH, False, 0
        elif code > LITERAL:
            if glue or case:
                raise WordsError("malformed", "a control must be followed by a token, not a literal")
            size = code - LITERAL
            if i + size > len(data):
                raise WordsError("malformed", f"the literal of {size} bytes is cut off")
            chunk = data[i:i + size]
            try:
                chunk.decode("utf-8")
            except UnicodeDecodeError as exc:
                raise WordsError("malformed", "a literal is not valid UTF-8") from exc
            out.extend(chunk)
            i += size
            prev = _LIT
        elif code in (CAP, UPPER):
            if case:
                raise WordsError("malformed", "two case controls before one token")
            case = code
        elif code == GLUE:
            if glue:
                raise WordsError("malformed", "two GLUE controls before one token")
            glue = True
        else:
            raise WordsError("malformed", f"unknown control 0x{code:04X}" if code != LITERAL else "empty literal")
        if len(out) > MAX_TEXT_BYTES:
            raise WordsError("too_large", f"decodes to more than {MAX_TEXT_BYTES} bytes")
    if glue or case:
        raise WordsError("malformed", "the stream ends with a control")
    if not out:
        raise WordsError("malformed", "empty token stream")
    return out.decode("utf-8")
