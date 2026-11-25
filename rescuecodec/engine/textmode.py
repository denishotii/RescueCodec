"""Which way a chat text goes: packed for RescueCodec (port 256) or a normal Meshtastic text (port 1).

Rule (spec/SPEC.md section 2.3). Same rule as TextMode in the Kotlin engine.
1. Every chat (a channel, or a direct chat with one node) has a mode: auto (default), compressed or normal.
2. A public channel always gets normal text. Mode normal always gets normal text.
3. Mode compressed: packed. Mode auto: a direct chat is packed when the peer runs RescueCodec (it sent a port 256
   packet in the last 24 h); a channel is packed when no Meshtastic user (a node that sent port 1 texts there and no
   port 256 packet) was heard on it in the last 24 h.
4. Packed only when the packet is at least 3 bytes smaller than the words as a normal text. Else normal text.
Example: "We are at the hut, two people with us, waiting for the vehicle on the forest road" is 81 B as a normal
text and 42 B packed with an id (no position), so it goes packed on a RescueCodec channel.
"""
from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from rescuecodec.engine.extract import fit_plain
from rescuecodec.v2.codec import CodecError, decode, encode
from rescuecodec.v2.textpack import CURRENT_DICT

MODES = ("auto", "compressed", "normal")
MIN_SAVING_BYTES = 3
#: How long a node stays "RescueCodec" or "Meshtastic user" after it was last heard.
MEMORY_S = 24 * 3600


def wants_packed(mode: str, *, public: bool, direct: bool, peer_rescuecodec: bool = False,
                 meshtastic_users: int = 0) -> tuple[bool, str]:
    """(packed wanted, reason). Reasons: public_channel, mode_normal, mode_compressed, peer_rescuecodec,
    peer_unknown, meshtastic_users, rescuecodec_only."""
    if public:
        return False, "public_channel"
    if mode == "normal":
        return False, "mode_normal"
    if mode == "compressed":
        return True, "mode_compressed"
    if direct:
        return (True, "peer_rescuecodec") if peer_rescuecodec else (False, "peer_unknown")
    return (False, "meshtastic_users") if meshtastic_users else (True, "rescuecodec_only")


@dataclass
class TextForm:
    packed: bool                    # True: RescueCodec text on port 256 with deflate; False: normal text on port 1
    payload: bytes                  # the bytes that go on the air
    message: dict[str, Any]         # canonical message of what goes out
    plain_size: int                 # bytes as a normal Meshtastic text (cut to 200 bytes like fit_plain)
    packed_size: int | None         # bytes as a packed RescueCodec text, None when it cannot be built (over 200 B)
    reason: str                     # why: a wants_packed reason, or "no_saving" / "too_large"


def choose_form(message: Mapping, want_packed: bool, reason: str = "") -> TextForm:
    """Build both forms of a text message and pick one. `message` is a canonical text message; its id, pos, time
    and priority go only into the packed form. Never raises for a text message."""
    words = message["text"]["text"]
    plain = fit_plain(words)
    plain_bytes = plain.encode("utf-8")
    plain_msg: dict[str, Any] = {"text": {"text": plain}}
    if message.get("priority"):
        plain_msg = {"priority": message["priority"], **plain_msg}
    packed_payload = None
    packed_msg = None
    try:
        packed_msg = {**{k: v for k, v in message.items() if k != "text"}, "text": {"text": words, "dict": CURRENT_DICT}}
        packed_payload = encode(packed_msg)
    except CodecError:                           # over 200 bytes, or over 1024 bytes of text: normal text
        packed_payload = None
    packed_size = len(packed_payload) if packed_payload is not None else None
    if want_packed and packed_payload is not None and packed_size <= len(plain_bytes) - MIN_SAVING_BYTES:
        return TextForm(True, packed_payload, decode(packed_payload), len(plain_bytes), packed_size, reason)
    why = reason if not want_packed else ("too_large" if packed_payload is None else "no_saving")
    return TextForm(False, plain_bytes, plain_msg, len(plain_bytes), packed_size, why)
