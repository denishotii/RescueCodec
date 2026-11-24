from __future__ import annotations

from dataclasses import dataclass
from typing import Callable, Protocol


@dataclass(frozen=True)
class Packet:
    source: str
    destination: str
    payload: bytes
    message_id: int
    hops: tuple[str, ...] = ()


class Transport(Protocol):
    def connect(self) -> None: ...
    def disconnect(self) -> None: ...
    def send(self, source: str, destination: str, payload: bytes) -> Packet: ...
    def subscribe(self, node: str, callback: Callable[[Packet], None]) -> None: ...
    @property
    def capabilities(self) -> dict: ...
