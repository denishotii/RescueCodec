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

from rescuecodec.gateway.radio.base import (BROADCAST, PORT_PRIVATE_APP, RadioAdapter, RadioInfo, RadioPacket,
                                            ResultCallback)
from rescuecodec.transport.base import Packet
from rescuecodec.transport.emulator import MeshEmulator
from rescuecodec.v2 import decode_any, encode

log = logging.getLogger("rescuecodec.gateway.emulator")

GATEWAY_NODE = "!0000e001"
PEER_NODE = "!0000e002"


class EmulatorRadio(RadioAdapter):
    kind = "sim"

    def __init__(self, mesh: MeshEmulator | None = None, node: str = GATEWAY_NODE, peers: tuple[str, ...] = (PEER_NODE,),
                 demo: bool = False):
        super().__init__()
        self.node = node
        self.peers = peers
        self.mesh = mesh or MeshEmulator(links={node: set(peers), **{p: {node} for p in peers}})
        self.mesh.connect()
        self.demo = demo
        self._connected = False
        self._ids = itertools.count(1)
        self.plain_texts: list[str] = []         # text-port packets "sent" (the emulator does not deliver them)
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

    def info(self) -> RadioInfo:
        return RadioInfo(connected=self._connected, kind="sim", node=self.node if self._connected else None,
                         channel="RescueCodec" if self._connected else None, region="EU_868" if self._connected else None,
                         preset="LONG_FAST" if self._connected else None,
                         firmware="emulator" if self._connected else None, detail="in-memory emulator")

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
             channel: int = 0, hop_limit: int | None = None, on_result: ResultCallback | None = None) -> int:
        if not self._connected:
            raise RuntimeError("no Meshtastic radio connected")
        packet_id = next(self._ids)
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

    def inject(self, sender: str, payload: bytes, *, to: str = BROADCAST) -> None:
        """Pretend that `sender` transmitted `payload` (for tests and demos)."""
        self.mesh.send(sender, self.node, bytes(payload))

    def _on_mesh_packet(self, packet: Packet) -> None:
        hops = max(0, len(packet.hops) - 2) if packet.hops else 0
        self.on_packet(RadioPacket(sender=packet.source, to=BROADCAST, port=PORT_PRIVATE_APP, payload=packet.payload,
                                   packet_id=packet.message_id, hops=hops, via="sim"))

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
