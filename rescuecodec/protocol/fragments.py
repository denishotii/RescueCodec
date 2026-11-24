"""Bounded fragment envelope and out-of-order reassembler."""
from __future__ import annotations

import struct
import time
from dataclasses import dataclass, field

FRAGMENT_HEADER = struct.Struct("!BIBB")  # version, message id, index, total
FRAGMENT_VERSION = 1


def fragment(payload: bytes, message_id: int, *, packet_budget: int = 200) -> list[bytes]:
    chunk_size = packet_budget - FRAGMENT_HEADER.size
    if chunk_size <= 0 or len(payload) > 65_535: raise ValueError("invalid budget or message too large")
    chunks = [payload[i:i+chunk_size] for i in range(0, len(payload), chunk_size)] or [b""]
    if len(chunks) > 255 or not 0 <= message_id <= 0xFFFFFFFF: raise ValueError("fragment metadata out of range")
    return [FRAGMENT_HEADER.pack(FRAGMENT_VERSION, message_id, i, len(chunks)) + chunk for i, chunk in enumerate(chunks)]


@dataclass
class _Assembly:
    total: int
    created: float
    chunks: dict[int, bytes] = field(default_factory=dict)


class Reassembler:
    def __init__(self, *, expiration_seconds: float = 60, max_messages: int = 128, max_message_size: int = 65_535):
        self.expiration_seconds, self.max_messages, self.max_message_size = expiration_seconds, max_messages, max_message_size
        self.pending: dict[int, _Assembly] = {}

    def add(self, packet: bytes, *, now: float | None = None) -> bytes | None:
        now = time.monotonic() if now is None else now
        self.expire(now)
        if len(packet) < FRAGMENT_HEADER.size: raise ValueError("truncated fragment")
        version, mid, index, total = FRAGMENT_HEADER.unpack_from(packet)
        if version != FRAGMENT_VERSION or total == 0 or index >= total: raise ValueError("invalid fragment header")
        body = packet[FRAGMENT_HEADER.size:]
        item = self.pending.get(mid)
        if item is None:
            if len(self.pending) >= self.max_messages: raise ValueError("reassembly capacity reached")
            item = self.pending[mid] = _Assembly(total, now)
        if item.total != total: raise ValueError("inconsistent fragment count")
        item.chunks[index] = body
        if sum(map(len, item.chunks.values())) > self.max_message_size: del self.pending[mid]; raise ValueError("message exceeds reassembly limit")
        if len(item.chunks) == total:
            data = b"".join(item.chunks[i] for i in range(total)); del self.pending[mid]; return data
        return None

    def expire(self, now: float | None = None) -> list[int]:
        now = time.monotonic() if now is None else now
        expired = [mid for mid, item in self.pending.items() if now - item.created > self.expiration_seconds]
        for mid in expired: del self.pending[mid]
        return expired
