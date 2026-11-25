"""What the gateway needs from a radio. Three adapters implement it: TCP (simulator or Wi-Fi radio),
USB serial (Heltec and others) and the in-memory emulator."""
from __future__ import annotations

from abc import ABC, abstractmethod
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

PORT_PRIVATE_APP = 256
PORT_TEXT_MESSAGE = 1
BROADCAST = "^all"

#: In front of every readable copy of a RescueCodec message that a gateway posts on the text port (--text-over-air,
#: also_plain). Stock apps show it as is. RescueCodec receivers drop texts that start with it: they already have the
#: message from port 256 (SPEC section 2.1).
TEXT_COPY_PREFIX = "[RC] "


@dataclass
class RadioPacket:
    """One packet heard on the radio. All radio numbers are measured by the radio, never estimated."""
    sender: str                    # "!a1b2c3d4"
    to: str                        # "^all" or "!a1b2c3d4"
    port: int                      # Meshtastic port number, 256 = PRIVATE_APP
    payload: bytes
    packet_id: int = 0
    rssi: int | None = None
    snr: float | None = None
    hops: int | None = None        # hop_start - hop_limit when the firmware reports both
    channel: int = 0
    via: str = "tcp"               # serial | tcp | ble | sim


@dataclass
class RadioInfo:
    connected: bool = False
    kind: str = "none"             # serial | tcp | ble | sim | none
    node: str | None = None
    channel: str | None = None     # name of the channel we send on
    channel_index: int | None = None
    channel_error: str | None = None   # why no safe channel was found (messages are refused then)
    other_channel: int = 0             # port 256 packets dropped because they came in on another channel
    region: str | None = None
    preset: str | None = None
    firmware: str | None = None
    detail: str | None = None      # device path or host:port, for logs

    def as_health(self, port: int = PORT_PRIVATE_APP) -> dict[str, Any]:
        return {"connected": self.connected, "kind": self.kind, "node": self.node, "port": port,
                "channel": self.channel, "channel_index": self.channel_index,
                "channel_error": self.channel_error, "other_channel": self.other_channel, "region": self.region, "preset": self.preset, "firmware": self.firmware}


#: result callback for a sent packet: (delivered, reason). Called at most once, from any thread.
ResultCallback = Callable[[bool, str | None], None]


class RadioAdapter(ABC):
    kind = "none"

    def __init__(self) -> None:
        self.on_packet: Callable[[RadioPacket], None] = lambda packet: None
        self.on_connection: Callable[[bool], None] = lambda connected: None
        self.on_node: Callable[[dict[str, Any]], None] = lambda node: None

    @abstractmethod
    def start(self) -> None:
        """Start connecting in the background. Never blocks and never raises: a missing radio only shows in info()."""

    @abstractmethod
    def stop(self) -> None: ...

    @property
    @abstractmethod
    def connected(self) -> bool: ...

    @abstractmethod
    def info(self) -> RadioInfo: ...

    @abstractmethod
    def send(self, payload: bytes, *, port: int = PORT_PRIVATE_APP, want_ack: bool = False, priority: int | None = None,
             channel: int | None = None, hop_limit: int | None = None, on_result: ResultCallback | None = None) -> int:
        """Broadcast `payload` on the channel (None = the radio's resolved private channel). Returns the radio packet id.
        Raises RuntimeError when not connected or when no safe channel exists."""

    def nodes(self) -> list[dict[str, Any]]:
        """Nodes the radio knows. Keys: node, num, short_name, long_name, hw_model, role, last_heard (unix s),
        snr, hops_away, battery_pct, pos ({lat, lon})."""
        return []


def node_id(num: int) -> str:
    return f"!{num & 0xFFFFFFFF:08x}"


def is_team_text(packet: RadioPacket, own_node: str | None, team_channel: int | None) -> bool:
    """Is this plain Meshtastic text (port 1) for us? A broadcast only on the team channel (the same channel as
    port 256), or a direct message to our own node on any channel. Never the public LongFast chatter."""
    if packet.to == BROADCAST:
        return team_channel is not None and packet.channel == team_channel
    return own_node is not None and packet.to == own_node
