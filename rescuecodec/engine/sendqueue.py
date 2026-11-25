"""Send queue with an airtime budget, and receive-side dedupe. Licence: Apache-2.0.

Order (same as the Kotlin engine): Mayday > Found, Request (and Ack) > Hazard > Sector > Track > Text.
Inside one rank the higher RescueCodec priority goes first, then first in, first out.

Airtime budget: the sender may use `duty_cycle` of the time over a sliding `window_s`
(default 10 percent of one hour = 360 s, the EU 868 MHz limit that Meshtastic also assumes).
All airtime numbers are ESTIMATES (see rescuecodec.encoding.airtime).
- Mayday and Ack are never held back (life safety, tiny packets). Their airtime still counts.
- Other types are held when sending them would take the used share over their limit:
  found, request, hazard 100 %, sector 90 %, text 70 %, track 70 %.
  Held items stay in the queue and go out when the window frees up.
"""
from __future__ import annotations

import itertools
import time
from collections import deque
from collections.abc import Mapping
from dataclasses import dataclass, field
from typing import Any

from rescuecodec.encoding.airtime import ESTIMATE_LABEL, message_airtime_seconds
from rescuecodec.v2 import codes as C
from rescuecodec.v2.codec import effective_priority, encode, message_type

#: Lower number = sent first.
TYPE_RANK = {"mayday": 0, "found": 1, "request": 1, "ack": 1, "hazard": 2, "sector": 3, "track": 4, "text": 5, "unknown": 5}
#: Share of the airtime budget that may be used before this type is held. None = never held.
BUDGET_LIMIT = {"mayday": None, "ack": None, "found": 1.0, "request": 1.0, "hazard": 1.0,
                "sector": 0.9, "text": 0.7, "track": 0.7, "unknown": 0.7}
PRIORITY_LEVEL = {"critical": 4, "urgent": 3, "normal": 2, "routine": 1}


class AirtimeBudget:
    """Sliding-window airtime budget. Times are seconds (any clock, as long as it is the same one)."""

    def __init__(self, window_s: float = 3600.0, duty_cycle: float = 0.10):
        if window_s <= 0 or not 0 < duty_cycle <= 1:
            raise ValueError("window_s must be positive and duty_cycle between 0 and 1")
        self.window_s = window_s
        self.duty_cycle = duty_cycle
        self._sent: deque[tuple[float, float]] = deque()

    @property
    def budget_s(self) -> float:
        return self.window_s * self.duty_cycle

    def _prune(self, now: float) -> None:
        while self._sent and self._sent[0][0] <= now - self.window_s:
            self._sent.popleft()

    def used_s(self, now: float) -> float:
        self._prune(now)
        return sum(s for _, s in self._sent)

    def remaining_s(self, now: float) -> float:
        return max(0.0, self.budget_s - self.used_s(now))

    def record(self, now: float, seconds: float) -> None:
        self._sent.append((now, seconds))

    def allows(self, kind: str, seconds: float, now: float) -> bool:
        limit = BUDGET_LIMIT.get(kind, 0.7)
        return limit is None or self.used_s(now) + seconds <= limit * self.budget_s + 1e-9

    def status(self, now: float) -> dict[str, Any]:
        used = self.used_s(now)
        return {"window_s": self.window_s, "duty_cycle": self.duty_cycle, "budget_s": self.budget_s,
                "used_s": used, "remaining_s": max(0.0, self.budget_s - used), "used_percent": 100 * used / self.budget_s,
                "basis": ESTIMATE_LABEL}


@dataclass
class QueueItem:
    seq: int
    message: dict
    payload: bytes
    kind: str
    priority: str
    rank: int
    airtime_s: float            # ESTIMATE
    meshtastic_priority: int
    queued_at: float
    sort_key: tuple = field(default=(), repr=False)

    @property
    def airtime_basis(self) -> str:
        return ESTIMATE_LABEL


class SendQueue:
    """Priority queue of encoded messages with an airtime budget."""

    def __init__(self, budget: AirtimeBudget | None = None, preset: str = "LONG_FAST", clock=time.monotonic):
        self.budget = budget or AirtimeBudget()
        self.preset = preset
        self._clock = clock
        self._items: list[QueueItem] = []
        self._seq = itertools.count(1)

    def __len__(self) -> int:
        return len(self._items)

    def put(self, msg: Mapping, now: float | None = None, payload: bytes | None = None) -> QueueItem | None:
        """Encode and queue a message. Returns None when the same bytes are already waiting (dedupe).
        Raises CodecError when the message is invalid.
        `payload` replaces the encoded message for a message that goes out in another form, for example a text sent
        as a plain Meshtastic text (port 1): those bytes are sent and their airtime is counted. Type and priority
        still come from `msg`."""
        now = self._clock() if now is None else now
        payload = encode(msg) if payload is None else bytes(payload)
        if any(i.payload == payload for i in self._items):
            return None
        kind = message_type(msg)
        prio = effective_priority(msg)
        item = QueueItem(next(self._seq), dict(msg), payload, kind, prio, TYPE_RANK[kind],
                         message_airtime_seconds(len(payload), self.preset), C.MESHTASTIC_PRIORITY[prio], now)
        item.sort_key = (item.rank, -PRIORITY_LEVEL[prio], item.seq)
        self._items.append(item)
        self._items.sort(key=lambda i: i.sort_key)
        return item

    def pending(self) -> list[QueueItem]:
        """Everything waiting, in send order."""
        return list(self._items)

    def peek(self, now: float | None = None) -> QueueItem | None:
        now = self._clock() if now is None else now
        return next((i for i in self._items if self.budget.allows(i.kind, i.airtime_s, now)), None)

    def pop(self, now: float | None = None) -> QueueItem | None:
        """Take the next item that may be sent now and book its airtime. None if nothing may go out."""
        now = self._clock() if now is None else now
        item = self.peek(now)
        if item is None:
            return None
        self._items.remove(item)
        self.budget.record(now, item.airtime_s)
        return item

    def held(self, now: float | None = None) -> list[QueueItem]:
        """Items that wait only because of the airtime budget."""
        now = self._clock() if now is None else now
        return [i for i in self._items if not self.budget.allows(i.kind, i.airtime_s, now)]

    def clear(self) -> None:
        self._items.clear()


class Deduper:
    """Receive side (spec section 14): the mesh can deliver a packet twice.

    A message with an id is a duplicate when (sender, id) was seen in the last 24 hours.
    A message without an id is a duplicate when (sender, payload bytes) was seen in the last 10 minutes.
    """

    def __init__(self, id_ttl_s: float = 24 * 3600, payload_ttl_s: float = 600.0):
        self.id_ttl_s, self.payload_ttl_s = id_ttl_s, payload_ttl_s
        self._ids: dict[tuple[Any, int], float] = {}
        self._payloads: dict[tuple[Any, bytes], float] = {}

    def _prune(self, now: float) -> None:
        self._ids = {k: t for k, t in self._ids.items() if now - t < self.id_ttl_s}
        self._payloads = {k: t for k, t in self._payloads.items() if now - t < self.payload_ttl_s}

    def seen(self, sender: Any, msg: Mapping, payload: bytes, now: float) -> bool:
        """True if this is a duplicate. Otherwise remember it and return False."""
        self._prune(now)
        mid = msg.get("id")
        if mid:
            key = (sender, int(mid))
            if key in self._ids:
                return True
            self._ids[key] = now
            return False
        pkey = (sender, bytes(payload))
        if pkey in self._payloads:
            return True
        self._payloads[pkey] = now
        return False
