"""Which way a chat text goes: packed for RescueCodec (port 256) or a normal Meshtastic text (port 1).

Rule (spec/SPEC.md section 2.3). Same rule as TextMode in the Kotlin engine.
1. Every chat (a channel, or a direct chat with one node) has a mode: auto (default), compressed or normal.
2. A public channel always gets normal text. Mode normal always gets normal text.
3. Mode compressed: packed. Mode auto: a direct chat is packed when the peer runs RescueCodec (it sent a port 256
   packet in the last 24 h); a channel is packed when no Meshtastic user (a node that sent port 1 texts there and no
   port 256 packet) was heard on it in the last 24 h.
4. Packed only when the packet is at least 3 bytes smaller than the words as a normal text. Else normal text.
5. Compact text (opt-in setting, off by default): on a chat that packs, every complete form is built (normal text,
   DEFLATE Envelope, word tokens in the Envelope, compact frame 0x02) and the strictly smallest goes out; the normal
   text when it is smaller or equal. SPEC sections 2.3, 3.6 and 3.7.
6. A chat that packs takes up to 400 bytes of text; the packet stays at most 200 bytes (fit_packed cuts the rest).
Example: "We are at the hut, two people with us, waiting for the vehicle on the forest road" is 81 B as a normal
text and 42 B packed with an id (no position), so it goes packed on a RescueCodec channel.
"""
from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from rescuecodec.engine.extract import ELLIPSIS, fit_plain
from rescuecodec.v2 import codes as C
from rescuecodec.v2.codec import CodecError, decode, encode, encode_compact
from rescuecodec.v2.textpack import CURRENT_DICT
from rescuecodec.v2.textwords import CURRENT_DICT as CURRENT_WORDS

MODES = ("auto", "compressed", "normal")
MIN_SAVING_BYTES = 3
#: How long a node stays "RescueCodec" or "Meshtastic user" after it was last heard.
MEMORY_S = 24 * 3600


def wants_packed(mode: str, *, public: bool, direct: bool, peer_rescuecodec: bool = False,
                 meshtastic_users: int = 0, rescuecodec_nodes: int = 0) -> tuple[bool, str]:
    """(packed wanted, reason). Reasons: public_channel, mode_normal, mode_compressed, peer_rescuecodec,
    peer_unknown, meshtastic_users, rescuecodec_only, nobody_known.

    Auto on a channel is careful: packed only when at least one RescueCodec node and no Meshtastic user was heard
    there in the last 24 h. When nobody is known yet, normal text (everyone can read it)."""
    if public:
        return False, "public_channel"
    if mode == "normal":
        return False, "mode_normal"
    if mode == "compressed":
        return True, "mode_compressed"
    if direct:
        return (True, "peer_rescuecodec") if peer_rescuecodec else (False, "peer_unknown")
    if meshtastic_users:
        return False, "meshtastic_users"
    return (True, "rescuecodec_only") if rescuecodec_nodes else (False, "nobody_known")


@dataclass
class TextForm:
    packed: bool                    # True: RescueCodec text on port 256 (DEFLATE or word tokens); False: port 1 text
    payload: bytes                  # the bytes that go on the air
    message: dict[str, Any]         # canonical message of what goes out
    plain_size: int                 # bytes as a normal Meshtastic text (cut to 200 bytes like fit_plain)
    packed_size: int | None         # bytes as a DEFLATE packed RescueCodec text, None when it cannot be built (over 200 B)
    reason: str                     # why: a wants_packed reason, or "no_saving" / "too_large"
    form: str = "plain"             # "plain", "deflate", "words" (tokens in the Envelope) or "lean" (compact frame)
    words_size: int | None = None   # bytes as word tokens in the Envelope, None when not tried or over 200 B
    text: str = ""                  # the words that go out (shortened with "…" when they did not fit)
    cut: bool = False               # True when the text was shortened to fit one packet
    lean_size: int | None = None    # bytes as a compact frame (0x02), None when not tried, over 200 B or with a position


def _rest(message: Mapping) -> dict[str, Any]:
    return {k: v for k, v in message.items() if k != "text"}


def _payload(rest: Mapping, body: dict[str, Any]) -> bytes | None:
    try:
        return encode({**rest, "text": body})
    except CodecError:                           # over 200 bytes, or over 1024 bytes of text
        return None


def _lean(rest: Mapping, words: str) -> bytes | None:
    """The compact frame of a text, or None: it has no id, position or time, so a message with `pos` or `time`
    (they would be lost) does not get one, nor a text over 200 bytes."""
    if rest.get("pos") or rest.get("time"):
        return None
    try:
        return encode_compact(words, CURRENT_WORDS)
    except CodecError:
        return None


def fit_packed(text: str, fits) -> str:
    """Shorten a text for a chat that packs (SPEC section 3.6.6). Same rule as TextFit.fitPacked in Kotlin.

    A text of at most MAX_PACKED_TEXT_BYTES (400) bytes that `fits` comes back unchanged. Else the longest prefix
    that ends on a whole UTF-8 character, with trailing whitespace stripped and "…" added, that is at most 400 bytes
    and `fits`. `fits(text)` says whether one form keeps the text exactly within 200 bytes."""
    raw = text.encode("utf-8")
    if len(raw) <= C.MAX_PACKED_TEXT_BYTES and fits(text):
        return text
    for cut in range(min(len(raw) - 1, C.MAX_PACKED_TEXT_BYTES - len(ELLIPSIS.encode("utf-8"))), -1, -1):
        if (raw[cut] & 0xC0) == 0x80:            # a cut here would split a character
            continue
        candidate = raw[:cut].decode("utf-8").rstrip() + ELLIPSIS
        if fits(candidate):
            return candidate
    return ELLIPSIS


def choose_form(message: Mapping, want_packed: bool, reason: str = "", compact: bool = False) -> TextForm:
    """Build the forms of a text message and pick one. `message` is a canonical text message; its id, pos, time
    and priority go only into the packed forms. Never raises for a text message.

    Compact off: DEFLATE when it fits and saves at least 3 bytes against the normal text, else the normal text.
    Compact on (the chat must pack, `want_packed`): the complete payloads of the compact frame (0x02, no id), the word
    tokens in the Envelope and DEFLATE are built, and the strictly smallest goes out (ties: compact frame, then
    words, then DEFLATE); the normal text when it is smaller or equal, unless it would cut the text.
    A chat that packs takes up to 400 bytes of text; what does not fit one packet is shortened (fit_packed)."""
    words = message["text"]["text"]
    rest = _rest(message)
    cut = False
    if want_packed:
        def fits(w: str) -> bool:
            return (len(w.encode("utf-8")) <= C.MAX_ENCODE_BYTES
                    or _payload(rest, {"text": w, "dict": CURRENT_DICT}) is not None
                    or (compact and (_lean(rest, w) is not None
                                     or _payload(rest, {"text": w, "words": CURRENT_WORDS}) is not None)))
        fitted = fit_packed(words, fits)
        cut = fitted != words
        words = fitted
    plain = fit_plain(words)
    plain_bytes = plain.encode("utf-8")
    plain_msg: dict[str, Any] = {"text": {"text": plain}}
    if message.get("priority"):
        plain_msg = {"priority": message["priority"], **plain_msg}
    packed_payload = _payload(rest, {"text": words, "dict": CURRENT_DICT})
    packed_size = len(packed_payload) if packed_payload is not None else None
    if compact and want_packed:
        return _smallest(message, rest, words, plain, plain_msg, packed_payload, reason, cut)
    if want_packed and packed_payload is not None and packed_size <= len(plain_bytes) - MIN_SAVING_BYTES:
        return TextForm(True, packed_payload, decode(packed_payload), len(plain_bytes), packed_size, reason, "deflate",
                        None, words, cut)
    why = reason if not want_packed else ("too_large" if packed_payload is None else "no_saving")
    return TextForm(False, plain_bytes, plain_msg, len(plain_bytes), packed_size, why, "plain", None, plain,
                    cut or plain != words)


def _smallest(message: Mapping, rest: Mapping, words: str, plain: str, plain_msg: dict[str, Any],
              packed_payload: bytes | None, reason: str, cut: bool) -> TextForm:
    """Compact on, a chat that packs: the strictly smallest complete payload; the normal text on a tie."""
    plain_bytes = plain.encode("utf-8")
    lean = _lean(rest, words)
    words_payload = _payload(rest, {"text": words, "words": CURRENT_WORDS})
    sizes = {"lean": len(lean) if lean is not None else None,
             "words": len(words_payload) if words_payload is not None else None,
             "deflate": len(packed_payload) if packed_payload is not None else None}
    packed = [(size, order, name) for order, (name, size) in enumerate(sizes.items()) if size is not None]
    best = min(packed) if packed else None
    lossy = plain != words                       # the normal text would be cut, a packed form keeps it whole
    if best is not None and (best[0] < len(plain_bytes) or lossy):
        name = best[2]
        if name == "lean":
            payload = lean
            msg: dict[str, Any] = {"text": {"text": words, "words": CURRENT_WORDS}}
            if message.get("priority"):          # not on the air (like a normal text): the radio's packet priority
                msg = {"priority": message["priority"], **msg}
        else:
            payload = words_payload if name == "words" else packed_payload
            msg = decode(payload)
        return TextForm(True, payload, msg, len(plain_bytes), sizes["deflate"], reason, name, sizes["words"], words,
                        cut, sizes["lean"])
    why = "too_large" if best is None else "no_saving"
    return TextForm(False, plain_bytes, plain_msg, len(plain_bytes), sizes["deflate"], why, "plain", sizes["words"],
                    plain, cut or plain != words, sizes["lean"])
