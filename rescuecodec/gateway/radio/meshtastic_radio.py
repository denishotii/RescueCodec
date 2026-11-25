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

from rescuecodec.gateway.radio.base import (BROADCAST, PORT_PRIVATE_APP, PORT_TEXT_MESSAGE, PRESET_DISPLAY_NAME, Channel,
                                            RadioAdapter, RadioInfo, RadioPacket, ResultCallback, is_for_us,
                                            listening_channels, node_id)

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


#: The public default key of Meshtastic ("AQ==" expands to this). Reports must never go out under it.
DEFAULT_PSK = bytes([0xd4, 0xf1, 0xbb, 0x3a, 0x20, 0x29, 0x07, 0x59, 0xf0, 0xbc, 0xff, 0xab, 0xcf, 0x4e, 0x69, 0x01])
DEFAULT_CHANNEL_NAME = "RescueCodec"
ROLE_DISABLED = 0
ROLE_PRIMARY = 1


class ChannelError(RuntimeError):
    """No safe channel to send on. The text is for people."""


def is_default_key(psk: bytes) -> bool:
    """Empty (no encryption), one byte (the shorthand for the default key) or the expanded default key."""
    return len(psk) <= 1 or psk == DEFAULT_PSK


def resolve_channel(channels: Any, name: str = DEFAULT_CHANNEL_NAME, explicit: int | None = None) -> tuple[int, str]:
    """(index, name) of the channel for reports. Same rule as the Android app (ChannelChoice):
    the channel named `name`, else the only enabled channel with its own key. Never a guess, never the public key.
    `explicit` (--channel) forces an index; it must be enabled but may use any key, because the person chose it."""
    rows = []
    for position, channel in enumerate(channels or []):
        if int(getattr(channel, "role", 0) or 0) == ROLE_DISABLED:
            continue
        settings = getattr(channel, "settings", None)
        psk = bytes(getattr(settings, "psk", b"") or b"")
        rows.append((int(getattr(channel, "index", position)), str(getattr(settings, "name", "") or ""), not is_default_key(psk)))
    if not rows:
        raise ChannelError("the radio has not told us its channels yet")
    if explicit is not None:
        for index, label, _ in rows:
            if index == explicit:
                return index, label
        raise ChannelError(f"channel {explicit} is not set up on this radio")
    named = [r for r in rows if r[1].lower() == name.lower()]
    if named:
        index, label, own_key = named[0]
        if not own_key:
            raise ChannelError(f'channel "{label}" uses the public default key, give it its own key')
        return index, label
    own = [r for r in rows if r[2]]
    if len(own) == 1:
        return own[0][0], own[0][1]
    if not own:
        raise ChannelError(f'no private channel on this radio, add one named "{name}" with its own key (import the team QR with Replace)')
    raise ChannelError(f'several private channels on this radio, name the team channel "{name}" or pass --channel')


def channel_table(channels: Any, preset: str | None = None) -> list[Channel]:
    """Every enabled channel of the radio as Channel(index, name, public). An empty primary name shows as the modem
    preset's name ("LongFast"), an empty secondary name as "Channel N"."""
    out = []
    for position, channel in enumerate(channels or []):
        role = int(getattr(channel, "role", 0) or 0)
        if role == ROLE_DISABLED:
            continue
        settings = getattr(channel, "settings", None)
        index = int(getattr(channel, "index", position))
        name = str(getattr(settings, "name", "") or "")
        if not name:
            name = PRESET_DISPLAY_NAME.get(preset or "LONG_FAST", "LongFast") if role == ROLE_PRIMARY else f"Channel {index}"
        out.append(Channel(index, name, is_default_key(bytes(getattr(settings, "psk", b"") or b""))))
    return out


def _int_or_none(value: Any) -> int | None:
    try:
        return int(value) if value is not None else None
    except (TypeError, ValueError):
        return None


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
    # A hop limit of 0 is left out of the dict (proto3 default), so a missing hopLimit next to a hopStart means 0.
    start, limit = _int_or_none(packet.get("hopStart")), _int_or_none(packet.get("hopLimit", 0))
    hops = start - limit if start is not None and limit is not None and start > 0 and start >= limit else None
    rssi, snr = _int_or_none(packet.get("rxRssi")), packet.get("rxSnr")
    try:
        snr = float(snr) if snr is not None else None
    except (TypeError, ValueError):
        snr = None
    return RadioPacket(sender=sender, to=to, port=port, payload=bytes(payload), packet_id=_int_or_none(packet.get("id")) or 0,
                       rssi=rssi, snr=snr, hops=hops, channel=_int_or_none(packet.get("channel")) or 0, via=via)


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
                 retry_s: float = 3.0, channel_name: str = DEFAULT_CHANNEL_NAME, channel_index: int | None = None,
                 listen: list[str] | None = None, show_public: bool = False):
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
        self.channel_name = channel_name
        self.channel_index = channel_index          # None = look it up by name on the radio
        self.listen = listen                        # channel names to hear (None = every private channel)
        self.show_public = show_public              # also hear broadcasts on public channels (LongFast)
        self.other_channel = 0                      # packets dropped because they came in on another channel

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
            try:
                connected = self._connect_once()
            except Exception as exc:                    # radio missing, busy, wrong port, timeout
                connected = False
                self._last_error = f"{type(exc).__name__}: {exc}"
                log.warning("radio not available (%s), retrying in %.0f s", self._last_error, backoff)
            if not connected:
                self._safe(self._close_iface, "closing the radio")
                if self._stop.wait(backoff):
                    break
                backoff = min(backoff * 2, 30.0)
                continue
            backoff = self._retry_s
            try:
                while not self._stop.is_set() and not self._lost.wait(1.0):
                    pass
            finally:
                self._safe(lambda: self._set_connected(False), "telling the gateway the radio is gone")
                self._safe(self._close_iface, "closing the radio")
            if not self._stop.is_set():
                log.warning("radio connection lost, reconnecting")
                self._stop.wait(self._retry_s)

    def _connect_once(self) -> bool:
        """Open the radio and announce it. Raises when the radio is not there. Never leaves a half open state:
        the caller closes the interface on any exception."""
        iface = self._factory(self.kind, self.target)
        self._device = getattr(iface, "devPath", None) or self.target
        self._iface = iface
        self._lost.clear()
        log.info("connecting to the radio at %s", self._device)
        iface.connect()
        iface.waitForConfig()
        self._last_error = None
        self._own_node = self._read_own_node()
        self._set_connected(True)
        log.info("radio connected: %s", self.info())
        self._safe(self._log_channels, "listing the channels")
        self._safe(self._announce_nodes, "announcing the radio's nodes")
        return True

    @staticmethod
    def _safe(fn: Callable[[], Any], what: str) -> None:
        """Run a step whose failure must never end the radio thread."""
        try:
            fn()
        except Exception:
            log.exception("%s failed", what)

    def _log_channels(self) -> None:
        heard = self.listen_channels()
        log.info("listening on %s, plus direct messages to %s", ", ".join(f"{c.name} ({c.index})" for c in heard) or "no channel",
                 self._own_node)
        known = {c.name.lower(): c for c in self.channels()}
        for name in self.listen or []:
            channel = known.get(name.lower())
            if channel is None:
                log.warning('--channels names "%s", but the radio has no such channel', name)
            elif channel.public and not self.show_public:
                log.warning('channel "%s" is public: add --show-public to hear it', channel.name)

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
            try:
                self.on_connection(value)
            except Exception:
                log.exception("a connection listener failed")

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
        # Runs on the meshtastic reader thread: an exception here must not stop reading.
        try:
            if not self._mine(interface):
                return
            radio_packet = packet_from_dict(packet, self.kind)
            if radio_packet is None:
                return
            if radio_packet.sender == self._own_node:        # our own packet coming back
                return
            if radio_packet.port in (PORT_PRIVATE_APP, PORT_TEXT_MESSAGE):
                # Only broadcasts on the channels we listen on, and direct messages to us. The public LongFast chat
                # stays out unless --show-public.
                listening = {c.index for c in self.listen_channels()}
                if not is_for_us(radio_packet, self._own_node, listening):
                    if radio_packet.port == PORT_PRIVATE_APP:       # other teams or apps on port 256, or no safe channel
                        self.other_channel += 1
                    log.debug("ignored a port %d packet from %s on channel %s to %s (listening on %s)", radio_packet.port,
                              radio_packet.sender, radio_packet.channel, radio_packet.to, sorted(listening))
                    return
            self.on_packet(radio_packet)
        except Exception:
            log.exception("could not handle a received packet")

    def _on_lost(self, interface: Any) -> None:
        if self._mine(interface):
            self._lost.set()

    def _on_node_updated(self, node: dict, interface: Any) -> None:
        try:
            if self._mine(interface) and self._connected:
                normalized = node_from_dict(node)
                if normalized:
                    self.on_node(normalized)
        except Exception:
            log.exception("could not handle a node update")

    def _announce_nodes(self) -> None:
        for node in self.nodes():
            try:
                self.on_node(node)
            except Exception:
                log.exception("a node listener failed")

    # ------------------------------------------------------------ facts
    @property
    def connected(self) -> bool:
        return self._connected and self._iface is not None

    def resolved_channel(self) -> tuple[int, str]:
        """(index, name) of the private channel, looked up on the radio each time (the person may edit channels).
        Raises ChannelError when there is no safe one."""
        iface = self._iface
        if iface is None or not self._connected:
            raise ChannelError("no radio connected")
        return resolve_channel(getattr(iface.localNode, "channels", None), self.channel_name, self.channel_index)

    def _preset(self) -> str | None:
        try:
            from meshtastic.protobuf import config_pb2
            return config_pb2.Config.LoRaConfig.ModemPreset.Name(self._iface.localNode.localConfig.lora.modem_preset)
        except Exception:
            return None

    def channels(self) -> list[Channel]:
        iface = self._iface
        if iface is None or not self._connected:
            return []
        try:
            return channel_table(getattr(iface.localNode, "channels", None), self._preset())
        except Exception:
            log.debug("could not read the channels", exc_info=True)
            return []

    def default_channel(self) -> Channel | None:
        try:
            index = self.resolved_channel()[0]
        except ChannelError:
            return None
        return self.channel_by_index(index)

    def listen_channels(self) -> list[Channel]:
        return listening_channels(self.channels(), self.default_channel(), self.listen, self.show_public)

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
            info.channel_index, info.channel = self.resolved_channel()
            default = self.channel_by_index(info.channel_index)
            info.channel = default.name if default else info.channel
        except ChannelError as exc:
            info.channel_error = str(exc)
        except Exception:
            log.debug("could not read the channels", exc_info=True)
        info.channels = self.listen_channels()
        info.other_channel = self.other_channel
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
             channel: int | None = None, hop_limit: int | None = None, on_result: ResultCallback | None = None,
             to: str | None = None) -> int:
        iface = self._iface
        if iface is None or not self._connected:
            raise RuntimeError("no Meshtastic radio connected")
        if channel is None:                                    # reports never go out on a guessed or public channel
            try:
                channel = self.resolved_channel()[0]
            except ChannelError as exc:
                raise RuntimeError(f"no private channel to send on: {exc}") from exc
        kwargs: dict[str, Any] = {"destinationId": to or BROADCAST, "portNum": port, "wantAck": want_ack,
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
