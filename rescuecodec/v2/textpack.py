"""Packed texts: raw DEFLATE with a shared preset dictionary (spec/SPEC.md section 3.5).

    data = pack("We are at the hut", 1)     # raw DEFLATE, level 9, dictionary spec/vocab/text-dict-v1.txt
    unpack(data, 1)                         # -> "We are at the hut"

Any DEFLATE stream unpacks to the same text everywhere, so only the dictionary bytes must be identical in every
library. The packed bytes of two compressors may differ; both are valid.
"""
from __future__ import annotations

import hashlib
import os
import zlib
from functools import lru_cache
from pathlib import Path

#: Largest text a receiver unpacks (bytes of UTF-8). Anything longer is rejected (a "zip bomb" guard).
MAX_UNPACKED_BYTES = 1024
#: Dictionary versions: number -> (file in spec/vocab, SHA-256 of its bytes).
DICTS = {1: ("text-dict-v1.txt", "f1cc69167232438d0440ec8f2be9fa4270c4dd04c502dae13aa2d91bd31c6ec9")}
#: The dictionary new texts are packed with.
CURRENT_DICT = 1


class PackError(ValueError):
    """`code` is a codec error code: malformed, too_large, invalid_value."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


def _spec_dir() -> Path:          # same as render.spec_dir (not imported: render imports the codec)
    env = os.getenv("RESCUECODEC_SPEC_DIR")
    return Path(env) if env else Path(__file__).resolve().parents[2] / "spec"


@lru_cache(maxsize=None)
def dictionary(version: int) -> bytes:
    """The bytes of a dictionary version. Raises KeyError for an unknown version, ValueError for a damaged file."""
    name, sha = DICTS[version]
    data = (_spec_dir() / "vocab" / name).read_bytes()
    if hashlib.sha256(data).hexdigest() != sha:
        raise ValueError(f"spec/vocab/{name} is damaged (SHA-256 differs; check line endings)")
    return data


def pack(text: str, version: int = CURRENT_DICT) -> bytes:
    """UTF-8 text -> raw DEFLATE bytes (level 9, window 15, memLevel 8, with the dictionary)."""
    comp = zlib.compressobj(9, zlib.DEFLATED, -15, 8, zlib.Z_DEFAULT_STRATEGY, zdict=dictionary(version))
    return comp.compress(text.encode("utf-8")) + comp.flush()


def unpack(data: bytes, version: int) -> str:
    """Raw DEFLATE bytes -> text. Stops after MAX_UNPACKED_BYTES. Raises PackError (or KeyError for an unknown
    dictionary)."""
    inflater = zlib.decompressobj(-15, zdict=dictionary(version))
    try:
        out = inflater.decompress(bytes(data), MAX_UNPACKED_BYTES + 1)
    except zlib.error as exc:
        raise PackError("malformed", f"not a valid DEFLATE stream: {exc}") from exc
    if len(out) > MAX_UNPACKED_BYTES:
        raise PackError("too_large", f"unpacks to more than {MAX_UNPACKED_BYTES} bytes")
    if not inflater.eof:
        raise PackError("malformed", "the DEFLATE stream is cut off")
    if inflater.unused_data:
        raise PackError("malformed", "bytes after the end of the DEFLATE stream")
    try:
        return out.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise PackError("malformed", "the unpacked text is not valid UTF-8") from exc
