"""In-memory radio on top of the existing MeshEmulator (rescuecodec.transport.emulator).

No hardware, no network. Use it to try the API, to run the dashboard without a radio and in unit tests.
Radio readings (RSSI, SNR) do not exist here, so they stay empty: nothing is invented.
With demo=True a pretend team ("Demo 1") reports a find and a hazard, and accepts what the gateway sends.
"""
from __future__ import annotations

import itertools
import logging
import threading
import time
from typing import Any

from rescuecodec.gateway.radio.base import (BROADCAST, PORT_PRIVATE_APP, PORT_TEXT_MESSAGE, Channel, RadioAdapter,
                                            RadioInfo, RadioPacket, ResultCallback, is_for_us, listening_channels)
from rescuecodec.transport.base import Packet
from rescuecodec.transport.emulator import MeshEmulator
from rescuecodec.v2 import decode_any, encode

log = logging.getLogger("rescuecodec.gateway.emulator")

GATEWAY_NODE = "!0000e001"
PEER_NODE = "!0000e002"
#: the emulator's channels unless a test passes its own: one private team channel on index 0
DEFAULT_CHANNELS = (Channel(0, "RescueCodec", False),)


class EmulatorRadio(RadioAdapter):
    kind = "sim"

    def __init__(self, mesh: MeshEmulator | None = None, node: str = GATEWAY_NODE, peers: tuple[str, ...] = (PEER_NODE,),
                 demo: bool = False, channels: tuple[Channel, ...] | list[Channel] = DEFAULT_CHANNELS,
                 channel_name: str = "RescueCodec", listen: list[str] | None = None, show_public: bool = False):
        super().__init__()
        self._channels = list(channels)
        self.channel_name = channel_name        # the default channel for sending, found by name like on a real radio
        self.listen = listen
        self.show_public = show_public
        self.node = node
        self.peers = peers
        self.mesh = mesh or MeshEmulator(links={node: set(peers), **{p: {node} for p in peers}})
        self.mesh.connect()
        self.demo = demo
        self._connected = False
        self._ids = itertools.count(1)
        self.plain_texts: list[str] = []         # text-port packets "sent" (the emulator does not deliver them)
        self.sent: list[dict[str, Any]] = []     # every packet handed to send(): port, payload, want_ack, priority, channel
        self._text_ids = itertools.count(0x7000_0001)
        self._timers: list[threading.Timer] = []
        self._stopped = False

    # ------------------------------------------------------------ lifecycle
    def start(self) -> None:
        self._stopped = False
        self.mesh.subscribe(self.node, self._on_mesh_packet)
        self._connected = True
        self.on_connection(True)
        for node in self.nodes():
            self.on_node(node)
        if self.demo:
            for peer in self.peers:
                self.mesh.subscribe(peer, lambda packet: self.hear_from_gateway(packet.payload))
            self._after(2.0, self._demo_report)

    def stop(self) -> None:
        self._stopped = True
        for timer in self._timers:
            timer.cancel()
        self._timers.clear()
        if self._connected:
            self._connected = False
            self.on_connection(False)

    @property
    def connected(self) -> bool:
        return self._connected

    def channels(self) -> list[Channel]:
        return list(self._channels) if self._connected else []

    def default_channel(self) -> Channel | None:
        named = self.channel_by_name(self.channel_name)
        if named is not None:
            return None if named.public else named
        private = [c for c in self.channels() if not c.public]
        return private[0] if len(private) == 1 else None

    def listen_channels(self) -> list[Channel]:
        return listening_channels(self.channels(), self.default_channel(), self.listen, self.show_public)

    def info(self) -> RadioInfo:
        if not self._connected:
            return RadioInfo(connected=False, kind="sim", detail="in-memory emulator")
        default = self.default_channel()
        return RadioInfo(connected=True, kind="sim", node=self.node, channel=default.name if default else None,
                         channel_index=default.index if default else None, channels=self.listen_channels(),
                         channel_error=None if default else "no private channel to send on", region="EU_868",
                         preset="LONG_FAST", firmware="emulator", detail="in-memory emulator")

    def nodes(self) -> list[dict[str, Any]]:
        now = int(time.time())
        rows = [{"node": self.node, "num": int(self.node[1:], 16), "short_name": "GW", "long_name": "Emulated gateway",
                 "hw_model": "EMULATOR", "role": "CLIENT", "last_heard": now, "snr": None, "hops_away": 0,
                 "battery_pct": None, "pos": None}]
        for peer in self.peers:
            rows.append({"node": peer, "num": int(peer[1:], 16), "short_name": "DEMO", "long_name": "Demo team 1",
                         "hw_model": "EMULATOR", "role": "CLIENT", "last_heard": now, "snr": None, "hops_away": 0,
                         "battery_pct": 87, "pos": {"lat": 50.69212, "lon": 7.21345} if self.demo else None})
        return rows

    # ------------------------------------------------------------ radio
    def send(self, payload: bytes, *, port: int = PORT_PRIVATE_APP, want_ack: bool = False, priority: int | None = None,
             channel: int | None = None, hop_limit: int | None = None, on_result: ResultCallback | None = None,
             to: str | None = None) -> int:
        if not self._connected:
            raise RuntimeError("no Meshtastic radio connected")
        packet_id = next(self._ids)
        self.sent.append({"port": port, "payload": bytes(payload), "want_ack": want_ack, "priority": priority,
                          "channel": channel, "to": to})
        if port != PORT_PRIVATE_APP:
            self.plain_texts.append(bytes(payload).decode("utf-8", "replace"))
            delivered, reason = True, None
        else:
            before = self.mesh.metrics["delivered"]
            others = [n for n in self.mesh.available if n != self.node]
            for other in others:
                self.mesh.send(self.node, other, bytes(payload))
            delivered = self.mesh.metrics["delivered"] > before or not others
            reason = None if delivered else "no_route"
        if want_ack and on_result is not None:
            on_result(delivered, reason)
        return packet_id

    def _default_index(self) -> int:
        default = self.default_channel()
        return default.index if default else 0

    def inject(self, sender: str, payload: bytes, *, to: str = BROADCAST, channel: int | None = None,
               packet_id: int | None = None) -> bool:
        """Pretend that `sender` transmitted `payload` on port 256 (for tests and demos). Without a channel it goes
        through the mesh emulator on the default channel. With a channel or a direct message, the gateway gets it only
        if it listens there, like on a real radio. Returns True if the gateway got it."""
        if channel is None and to == BROADCAST:
            self.mesh.send(sender, self.node, bytes(payload))
            return True
        return self._deliver(RadioPacket(sender=sender, to=to, port=PORT_PRIVATE_APP, payload=bytes(payload),
                                         packet_id=packet_id if packet_id is not None else next(self._text_ids), hops=0,
                                         channel=self._default_index() if channel is None else channel, via="sim"))

    def inject_text(self, sender: str, text: str | bytes, *, to: str = BROADCAST, channel: int | None = None,
                    packet_id: int | None = None) -> bool:
        """Pretend that `sender` sent a plain Meshtastic text (port 1), for example from the official app.
        Without a channel it comes on the default channel. Like a real radio, a text on a channel we do not listen on
        or a direct message to another node is not passed on. Returns True if the gateway got it."""
        return self._deliver(RadioPacket(sender=sender, to=to, port=PORT_TEXT_MESSAGE,
                                         payload=text.encode("utf-8") if isinstance(text, str) else bytes(text),
                                         packet_id=packet_id if packet_id is not None else next(self._text_ids), hops=0,
                                         channel=self._default_index() if channel is None else channel, via="sim"))

    def _deliver(self, packet: RadioPacket) -> bool:
        if not self._connected or not is_for_us(packet, self.node, {c.index for c in self.listen_channels()}):
            return False
        self.on_packet(packet)
        return True

    def _on_mesh_packet(self, packet: Packet) -> None:
        hops = max(0, len(packet.hops) - 2) if packet.hops else 0
        self.on_packet(RadioPacket(sender=packet.source, to=BROADCAST, port=PORT_PRIVATE_APP, payload=packet.payload,
                                   packet_id=packet.message_id, hops=hops, channel=self._default_index(), via="sim"))

    # ------------------------------------------------------------ demo team
    def _after(self, seconds: float, fn, *args) -> None:
        if self._stopped:
            return
        timer = threading.Timer(seconds, fn, args)
        timer.daemon = True
        self._timers = [t for t in self._timers if t.is_alive()]
        self._timers.append(timer)
        timer.start()

    def _peer_send(self, message: dict) -> None:
        if self._stopped:
            return
        try:
            self.inject(PEER_NODE, encode(message))
        except Exception:
            log.exception("demo team could not send")

    def _demo_report(self) -> None:
        self._peer_send({"id": 1, "pos": {"lat": 50.69212, "lon": 7.21345},
                         "found": {"age": "child", "consciousness": "conscious", "injuries": ["hypothermia", "fracture"],
                                   "needs": ["stretcher"], "responders": 2}})
        self._after(1.5, self._peer_send, {"id": 2, "pos": {"lat": 50.6931, "lon": 7.2162},
                                           "hazard": {"kind": "rockfall", "severity": "high", "radius": 50}})
        self._after(3.0, self._peer_send, {"time": time.strftime("%H:%M", time.gmtime()), "pos": {"lat": 50.69212, "lon": 7.21345},
                                           "track": {"points": [{"lat": 50.69212, "lon": 7.21345}, {"lat": 50.6925, "lon": 7.2139},
                                                                {"lat": 50.69291, "lon": 7.21422}]}})

    def hear_from_gateway(self, payload: bytes) -> None:
        """The demo team answers requests the gateway sends: it accepts them after a few seconds."""
        if not self.demo:
            return
        try:
            msg = decode_any(payload)
        except Exception:
            return
        for kind in ("found", "request", "mayday"):
            if kind in msg and msg.get("id"):
                ack = {"ack": {"ref": msg["id"], "node": self.node, "status": "accepted", "eta": 10, "team": "Demo 1"}}
                self._after(3.0, self._peer_send, ack)
                self._after(12.0, self._peer_send, {"ack": {**ack["ack"], "status": "done"}})
                return
