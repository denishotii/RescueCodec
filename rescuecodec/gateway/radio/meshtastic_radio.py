"""Meshtastic radio over TCP (simulator or a Wi-Fi radio) or USB serial (for example a Heltec V3).

The `meshtastic` Python package is imported only when a radio is started, so the rest of the
gateway works without it. Nothing here opens a device until `start()` is called.
"""
from __future__ import annotations

import glob
import logging
import threading
from collections.abc import Callable
from typing import Any

from rescuecodec.gateway.radio.base import (BROADCAST, PORT_PRIVATE_APP, RadioAdapter, RadioInfo, RadioPacket,
                                            ResultCallback, node_id)

log = logging.getLogger("rescuecodec.gateway.radio")

#: macOS device files of the USB-serial chips on common Meshtastic boards, best guess first.
#: Heltec V3 and most ESP32 boards use a Silicon Labs CP210x (usbserial-* or SLAB_USBtoUART*).
SERIAL_PATTERNS_PRIMARY = ("/dev/cu.usbserial-*", "/dev/cu.SLAB_USBtoUART*")
#: Other chips and other systems. Used only when no primary device exists.
SERIAL_PATTERNS_FALLBACK = ("/dev/cu.wchusbserial*", "/dev/cu.usbmodem*", "/dev/ttyUSB*", "/dev/ttyACM*")

TIMEOUT_S = 30   # the meshtastic library default is 300 s, far too long for a gateway that retries


class SerialPortError(RuntimeError):
    pass


def find_serial_ports(globber: Callable[[str], list[str]] | None = None) -> list[str]:
    """Candidate device files, primary patterns first. Only looks at file names, never opens anything."""
    globber = globber or glob.glob
    for patterns in (SERIAL_PATTERNS_PRIMARY, SERIAL_PATTERNS_FALLBACK):
        found = sorted({path for pattern in patterns for path in globber(pattern)})
        if found:
            return found
    return []


def pick_serial_port(setting: str, globber: Callable[[str], list[str]] | None = None) -> str:
    """`--serial auto` finds the one USB radio. A path is returned as is."""
    if setting and setting != "auto":
        return setting
    ports = find_serial_ports(globber)
    if not ports:
        raise SerialPortError("no USB serial radio found (looked for /dev/cu.usbserial-* and /dev/cu.SLAB_USBtoUART*)")
    if len(ports) > 1:
        raise SerialPortError(f"several USB serial devices found, pick one with --serial PATH: {', '.join(ports)}")
    return ports[0]


def _port_number(value: Any) -> int | None:
    """MessageToDict gives the port as a name ("PRIVATE_APP") or a number."""
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        if value.isdigit():
            return int(value)
        try:
            from meshtastic.protobuf import portnums_pb2
            return int(portnums_pb2.PortNum.Value(value))
        except Exception:
            return None
    return None


def packet_from_dict(packet: dict[str, Any], via: str) -> RadioPacket | None:
    """Meshtastic's packet dictionary -> RadioPacket. None if there is no decoded payload."""
    decoded = packet.get("decoded")
    if not isinstance(decoded, dict):
        return None
    port = _port_number(decoded.get("portnum"))
    payload = decoded.get("payload")
    if port is None or not isinstance(payload, (bytes, bytearray)):
        return None
    sender = packet.get("fromId") or (node_id(packet["from"]) if packet.get("from") else None)
    if not sender:
        return None
    to = packet.get("toId") or (node_id(packet["to"]) if packet.get("to") else BROADCAST)
    if to == "!ffffffff":
        to = BROADCAST
    start, limit = packet.get("hopStart"), packet.get("hopLimit", 0)
    hops = start - limit if isinstance(start, int) and start > 0 and isinstance(limit, int) and start >= limit else None
    rssi, snr = packet.get("rxRssi"), packet.get("rxSnr")
    return RadioPacket(sender=sender, to=to, port=port, payload=bytes(payload), packet_id=int(packet.get("id", 0)),
                       rssi=int(rssi) if rssi else None, snr=float(snr) if snr is not None else None,
                       hops=hops, channel=int(packet.get("channel", 0)), via=via)


def node_from_dict(raw: dict[str, Any]) -> dict[str, Any] | None:
    num = raw.get("num")
    if not isinstance(num, int):
        return None
    user, pos, metrics = raw.get("user") or {}, raw.get("position") or {}, raw.get("deviceMetrics") or {}
    out: dict[str, Any] = {
        "node": user.get("id") or node_id(num), "num": num,
        "short_name": user.get("shortName"), "long_name": user.get("longName"),
        "hw_model": user.get("hwModel"), "role": (user.get("role") or "CLIENT") if user else None,   # CLIENT is 0, so JSON leaves it out
        "last_heard": raw.get("lastHeard"), "snr": raw.get("snr"), "hops_away": raw.get("hopsAway"),
        "battery_pct": metrics.get("batteryLevel"), "pos": None,
    }
    lat, lon = pos.get("latitude"), pos.get("longitude")
    if lat is None and pos.get("latitudeI") is not None:
        lat, lon = pos["latitudeI"] / 1e7, pos.get("longitudeI", 0) / 1e7
    if lat is not None and lon is not None and (lat or lon):
        out["pos"] = {"lat": round(float(lat), 5), "lon": round(float(lon), 5)}
    return out


class MeshtasticRadio(RadioAdapter):
    """kind="tcp": target is "host:port". kind="serial": target is "auto" or a device path.

    `interface_factory(kind, target)` returns a not yet connected meshtastic interface. Tests replace it.
    """

    def __init__(self, kind: str, target: str, *, interface_factory: Callable[[str, str], Any] | None = None,
                 retry_s: float = 3.0):
        super().__init__()
        if kind not in ("tcp", "serial"):
            raise ValueError("kind must be tcp or serial")
        self.kind, self.target = kind, target
        self._factory = interface_factory or self._default_factory
        self._retry_s = retry_s
        self._iface: Any = None
        self._connected = False
        self._stop = threading.Event()
        self._lost = threading.Event()
        self._thread: threading.Thread | None = None
        self._subscribed = False
        self._last_error: str | None = None
        self._device: str | None = None
        self._own_node: str | None = None

    # ------------------------------------------------------------ opening
    @staticmethod
    def _default_factory(kind: str, target: str) -> Any:
        if kind == "tcp":
            from meshtastic.tcp_interface import TCPInterface
            from rescuecodec.gateway.config import split_host_port
            host, port = split_host_port(target, 4403)
            return TCPInterface(hostname=host, portNumber=port, connectNow=False, timeout=TIMEOUT_S)
        from meshtastic.serial_interface import SerialInterface
        return SerialInterface(devPath=pick_serial_port(target), connectNow=False, timeout=TIMEOUT_S)

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._subscribe()
        self._thread = threading.Thread(target=self._run, name=f"radio-{self.kind}", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._lost.set()
        if self._thread and self._thread is not threading.current_thread():
            self._thread.join(timeout=5)
        self._unsubscribe()
        self._close_iface()

    def _run(self) -> None:
        backoff = self._retry_s
        while not self._stop.is_set():
            iface = None
            try:
                iface = self._factory(self.kind, self.target)
                self._device = getattr(iface, "devPath", None) or self.target
                self._iface = iface
                self._lost.clear()
                log.info("connecting to the radio at %s", self._device)
                iface.connect()
                iface.waitForConfig()
            except Exception as exc:                    # radio missing, busy, wrong port, timeout
                self._last_error = f"{type(exc).__name__}: {exc}"
                log.warning("radio not available (%s), retrying in %.0f s", self._last_error, backoff)
                self._close_iface()
                if self._stop.wait(backoff):
                    break
                backoff = min(backoff * 2, 30.0)
                continue
            backoff = self._retry_s
            self._last_error = None
            self._own_node = self._read_own_node()
            self._set_connected(True)
            log.info("radio connected: %s", self.info())
            self._announce_nodes()
            while not self._stop.is_set() and not self._lost.wait(1.0):
                pass
            self._set_connected(False)
            self._close_iface()
            if not self._stop.is_set():
                log.warning("radio connection lost, reconnecting")
                self._stop.wait(self._retry_s)

    def _read_own_node(self) -> str | None:
        try:
            return (self._iface.getMyNodeInfo() or {}).get("user", {}).get("id")
        except Exception:
            return None

    def _close_iface(self) -> None:
        iface, self._iface = self._iface, None
        if iface is not None:
            try:
                iface.close()
            except Exception:
                log.debug("closing the radio failed", exc_info=True)

    def _set_connected(self, value: bool) -> None:
        if self._connected != value:
            self._connected = value
            self.on_connection(value)

    # ------------------------------------------------------------ pubsub
    def _subscribe(self) -> None:
        if self._subscribed:
            return
        from pubsub import pub
        pub.subscribe(self._on_receive, "meshtastic.receive")
        pub.subscribe(self._on_lost, "meshtastic.connection.lost")
        pub.subscribe(self._on_node_updated, "meshtastic.node.updated")
        self._subscribed = True

    def _unsubscribe(self) -> None:
        if not self._subscribed:
            return
        from pubsub import pub
        for listener, topic in ((self._on_receive, "meshtastic.receive"), (self._on_lost, "meshtastic.connection.lost"),
                                (self._on_node_updated, "meshtastic.node.updated")):
            try:
                pub.unsubscribe(listener, topic)
            except Exception:
                pass
        self._subscribed = False

    def _mine(self, interface: Any) -> bool:
        return self._iface is not None and interface is self._iface

    def _on_receive(self, packet: dict, interface: Any) -> None:
        if not self._mine(interface):
            return
        radio_packet = packet_from_dict(packet, self.kind)
        if radio_packet is None:
            return
        if radio_packet.sender == self._own_node:        # our own packet coming back
            return
        self.on_packet(radio_packet)

    def _on_lost(self, interface: Any) -> None:
        if self._mine(interface):
            self._lost.set()

    def _on_node_updated(self, node: dict, interface: Any) -> None:
        if self._mine(interface) and self._connected:
            normalized = node_from_dict(node)
            if normalized:
                self.on_node(normalized)

    def _announce_nodes(self) -> None:
        for node in self.nodes():
            self.on_node(node)

    # ------------------------------------------------------------ facts
    @property
    def connected(self) -> bool:
        return self._connected and self._iface is not None

    def info(self) -> RadioInfo:
        iface = self._iface
        info = RadioInfo(connected=self.connected, kind=self.kind, detail=self._device or self.target)
        if iface is None or not self._connected:
            return info
        info.node = self._own_node
        try:
            lora = iface.localNode.localConfig.lora
            from meshtastic.protobuf import config_pb2
            info.region = config_pb2.Config.LoRaConfig.RegionCode.Name(lora.region)
            info.preset = config_pb2.Config.LoRaConfig.ModemPreset.Name(lora.modem_preset)
        except Exception:
            pass
        try:
            channels = iface.localNode.channels or []
            info.channel = next((c.settings.name or None for c in channels if c.role == 1), None)  # 1 = PRIMARY
        except Exception:
            pass
        try:
            info.firmware = (iface.metadata.firmware_version or None) if iface.metadata else None
        except Exception:
            pass
        return info

    def nodes(self) -> list[dict[str, Any]]:
        iface = self._iface
        if iface is None:
            return []
        try:
            raw_nodes = list((iface.nodes or {}).values())
        except Exception:
            return []
        return [n for n in (node_from_dict(r) for r in raw_nodes) if n]

    # ------------------------------------------------------------ sending
    def send(self, payload: bytes, *, port: int = PORT_PRIVATE_APP, want_ack: bool = False, priority: int | None = None,
             channel: int = 0, hop_limit: int | None = None, on_result: ResultCallback | None = None) -> int:
        iface = self._iface
        if iface is None or not self._connected:
            raise RuntimeError("no Meshtastic radio connected")
        kwargs: dict[str, Any] = {"destinationId": BROADCAST, "portNum": port, "wantAck": want_ack,
                                  "channelIndex": channel}
        if hop_limit is not None:
            kwargs["hopLimit"] = hop_limit
        if priority is not None:
            kwargs["priority"] = priority
        if want_ack and on_result is not None:
            def on_response(response: dict) -> None:
                routing = (response.get("decoded") or {}).get("routing") or {}
                reason = routing.get("errorReason") or "NONE"
                on_result(reason == "NONE", None if reason == "NONE" else reason.lower())
            kwargs["onResponse"] = on_response
            kwargs["onResponseAckPermitted"] = True
        sent = iface.sendData(bytes(payload), **kwargs)
        return int(getattr(sent, "id", 0) or 0)
