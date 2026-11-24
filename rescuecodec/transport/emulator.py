"""Deterministic laptop-only mesh emulator; latency and loss are synthetic."""
from __future__ import annotations

import random
import threading
import time
from collections import defaultdict, deque
from typing import Callable

from rescuecodec.transport.base import Packet


class MeshEmulator:
    def __init__(self, links=None, *, latency_ms: float = 0, loss: float = 0, duplicate: float = 0, seed: int = 1):
        self.links = links or {"A": {"B"}, "B": {"A", "C"}, "C": {"B"}}
        self.latency_ms, self.loss, self.duplicate = latency_ms, loss, duplicate
        self.rng = random.Random(seed)
        self.available = {n: True for n in self.links}
        self.available.update({v: True for ns in self.links.values() for v in ns})
        self.callbacks: dict[str, list[Callable]] = defaultdict(list)
        self.connected = False
        self._seq = 0
        self._lock = threading.RLock()
        self.metrics = {"sent": 0, "delivered": 0, "lost": 0, "duplicates": 0, "hops": 0}

    @property
    def capabilities(self):
        return {"kind": "synthetic_emulator", "binary": True, "multihop": True, "physical_radio": False, "latency_is_synthetic": True}

    def connect(self): self.connected = True
    def disconnect(self): self.connected = False
    def subscribe(self, node, callback): self.callbacks[node].append(callback)
    def set_link(self, a, b, up=True):
        self.links.setdefault(a, set()); self.links.setdefault(b, set())
        self.available.setdefault(a, True); self.available.setdefault(b, True)
        if up: self.links[a].add(b); self.links[b].add(a)
        else: self.links[a].discard(b); self.links[b].discard(a)
    def set_node(self, node, up=True): self.available[node] = up

    def route(self, source, destination):
        if source not in self.available or destination not in self.available or not self.available[source] or not self.available[destination]:
            return None
        q = deque([(source, (source,))]); seen = {source}
        while q:
            node, path = q.popleft()
            if node == destination: return path
            for nxt in sorted(self.links.get(node, ())):
                if nxt not in seen and self.available.get(nxt, False): seen.add(nxt); q.append((nxt, path + (nxt,)))
        return None

    def send(self, source, destination, payload):
        if not self.connected: raise RuntimeError("transport is disconnected")
        if not isinstance(payload, bytes): raise TypeError("transport accepts raw bytes")
        path = self.route(source, destination)
        with self._lock:
            self._seq += 1; mid = self._seq; self.metrics["sent"] += 1
            self.metrics["hops"] += max(0, len(path or ()) - 1)
        if path is None or self.rng.random() < self.loss:
            self.metrics["lost"] += 1
            return Packet(source, destination, payload, mid, path or ())
        if self.latency_ms: time.sleep(self.latency_ms / 1000)
        packet = Packet(source, destination, bytes(payload), mid, path)
        for callback in tuple(self.callbacks.get(destination, ())): callback(packet)
        self.metrics["delivered"] += 1
        if self.rng.random() < self.duplicate:
            self.metrics["duplicates"] += 1
            for callback in tuple(self.callbacks.get(destination, ())): callback(packet)
        return packet
