"""One-way physical-radio receive gateway into the laptop's synthetic mesh.

This is an application-layer bridge. It never sends through the radio; the
virtual mesh is in-memory and has no RF transceiver.
"""
from __future__ import annotations

from rescuecodec.protocol.codec import PacketError, decode_report
from rescuecodec.transport.base import Packet
from rescuecodec.transport.emulator import MeshEmulator
from rescuecodec.transport.meshtastic import MeshtasticTransport


class PhysicalToVirtualGateway:
    def __init__(self, mesh: MeshEmulator, *, serial_path: str = "/dev/cu.usbserial-0001", virtual_destination: str = "C"):
        self.mesh = mesh
        self.radio = MeshtasticTransport(serial_path=serial_path)
        self.virtual_destination = virtual_destination
        self.local_node_id: str | None = None
        self.running = False
        self.metrics = {"radio_packets_seen": 0, "valid_reports_forwarded": 0, "not_for_gateway": 0, "unsupported_or_malformed": 0, "forward_errors": 0}
        self._peer_nodes: set[str] = set()

    @property
    def capabilities(self):
        return {"direction": "physical_radio_to_virtual_mesh_only", "radio_tx_enabled": False,
                "virtual_transport": "synthetic_in_memory", "serial_path": self.radio.serial_path,
                "virtual_destination": self.virtual_destination, **self.metrics}

    def start(self):
        if self.running: return self.capabilities
        self.radio.connect()
        self.local_node_id = self.radio.interface.getMyNodeInfo()["user"]["id"]
        self.radio.subscribe("*", self._on_radio_packet)
        self.running = True
        return self.capabilities

    def stop(self):
        self.running = False
        self.radio.disconnect()
        for node in tuple(self._peer_nodes):
            self.mesh.set_link(node, "B", up=False)
            self.mesh.links.pop(node, None)
            self.mesh.available.pop(node, None)
        self._peer_nodes.clear()

    def _on_radio_packet(self, packet: Packet):
        self.metrics["radio_packets_seen"] += 1
        if packet.destination not in ("^all", self.local_node_id):
            self.metrics["not_for_gateway"] += 1
            return
        try:
            decode_report(packet.payload)  # only relay supported, version-validated RescueCodec reports
        except PacketError:
            self.metrics["unsupported_or_malformed"] += 1
            return
        source = packet.source
        self.mesh.set_link(source, "B", up=True)
        self._peer_nodes.add(source)
        try:
            self.mesh.send(source, self.virtual_destination, packet.payload)
            self.metrics["valid_reports_forwarded"] += 1
        except Exception:
            self.metrics["forward_errors"] += 1

