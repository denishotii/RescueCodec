"""Optional Meshtastic adapter. Merely connecting is read-only; send() transmits."""
from __future__ import annotations

from typing import Callable


class MeshtasticTransport:
    def __init__(self, *, serial_path: str | None = None, hostname: str | None = None, port: int | None = None):
        self.serial_path, self.hostname, self.port = serial_path, hostname, port
        self.interface = None
        self.callbacks: list[Callable] = []

    @property
    def capabilities(self):
        return {"kind": "meshtastic", "binary": True, "physical_radio": self.serial_path is not None, "application_budget_bytes": 200}

    def connect(self):
        from pubsub import pub
        if self.serial_path:
            from meshtastic.serial_interface import SerialInterface
            self.interface = SerialInterface(devPath=self.serial_path)
        else:
            from meshtastic.tcp_interface import TCPInterface
            self.interface = TCPInterface(hostname=self.hostname or "127.0.0.1", portNumber=self.port or 4402)
        pub.subscribe(self._receive, "meshtastic.receive")

    def disconnect(self):
        if self.interface:
            from pubsub import pub
            pub.unsubscribe(self._receive, "meshtastic.receive")
            self.interface.close()
            self.interface = None

    def subscribe(self, node, callback): self.callbacks.append(callback)
    def _receive(self, packet, interface):
        from rescuecodec.transport.base import Packet
        if packet.get("decoded", {}).get("portnum") != "PRIVATE_APP":
            return
        payload = packet.get("decoded", {}).get("payload", b"")
        if isinstance(payload, bytes):
            for callback in self.callbacks: callback(Packet(str(packet.get("fromId")), str(packet.get("toId")), payload, int(packet.get("id", 0))))

    def send(self, source, destination, payload):
        if self.interface is None: raise RuntimeError("transport is disconnected")
        from meshtastic.protobuf import portnums_pb2
        self.interface.sendData(payload, destinationId=destination, portNum=portnums_pb2.PortNum.PRIVATE_APP, wantAck=True)
        from rescuecodec.transport.base import Packet
        return Packet(source, destination, payload, 0)
