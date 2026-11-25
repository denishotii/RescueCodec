"""TAK connector: Cursor on Target (CoT) events for ATAK, WinTAK and iTAK.

  found, request, hazard   map marker (b-m-p-s-m) with the readable line in the remarks
  sector                   map marker, green cleared, yellow partial, red blocked
  mayday                   emergency alert (b-a-o-tbl, "911 Alert"); a cancel (b-a-o-can) when someone marks it done
  track                    the sender as a friendly unit (a-f-G-U-C) at its last point

The shapes follow the ATAK-CIV examples (takcot/examples/Marker - Spot.cot) and ATAK's own 911 alert and cancel messages
(EmergencyManager.java). Accept and Done change the remarks of the same marker (same uid), so TAK updates it in place.
A done task gets a stale time of "now" and TAK removes the marker.
Events go out as UDP multicast (default 239.2.3.1:6969, the TAK default) and, if configured, over TCP to a TAK server.
"""
from __future__ import annotations

import errno
import logging
import queue
import socket
import threading
import time
import xml.etree.ElementTree as ET
from collections.abc import Callable
from datetime import datetime, timedelta, timezone
from typing import Any

log = logging.getLogger("rescuecodec.gateway.cot")

TYPE_MARKER = "b-m-p-s-m"        # spot map marker
TYPE_ALERT = "b-a-o-tbl"         # 911 alert
TYPE_ALERT_CANCEL = "b-a-o-can"
TYPE_UNIT = "a-f-G-U-C"          # friendly ground unit, civilian
UNKNOWN = "9999999.0"            # the CoT value for "not known" (hae, le, ce)
CE_BY_ACC = {"gps": 10.0, "gps_poor": 50.0, "estimated": 100.0, "area": 1000.0}   # metres, our reading of the accuracy code
TASK_TYPES = ("found", "request", "hazard", "mayday")


def argb(a: int, r: int, g: int, b: int) -> str:
    """CoT colors are signed 32-bit ARGB integers (ATAK style)."""
    value = (a << 24) | (r << 16) | (g << 8) | b
    return str(value - (1 << 32) if value >= 1 << 31 else value)


RED, ORANGE, YELLOW, GREEN, BLUE = (argb(255, 255, 0, 0), argb(255, 255, 165, 0), argb(255, 255, 255, 0),
                                    argb(255, 0, 200, 0), argb(255, 30, 100, 255))


def cot_time(moment: datetime) -> str:
    """2026-11-25T14:32:05.120Z (UTC, milliseconds)."""
    moment = moment.astimezone(timezone.utc)
    return moment.strftime("%Y-%m-%dT%H:%M:%S.") + f"{moment.microsecond // 1000:03d}Z"


def build_event(*, uid: str, type_: str, lat: float, lon: float, now: datetime, stale: datetime, callsign: str,
                remarks: str, ce: float = 10.0, how: str = "h-g-i-g-o", color: str | None = None,
                emergency: tuple[str, bool] | None = None, link_uid: str | None = None) -> bytes:
    """One CoT event as XML bytes. `emergency` is (text, cancel): an alert (cancel False) or its cancel (True).
    `color` makes a colored spot marker. `link_uid` names the unit that reported it."""
    event = ET.Element("event", {"version": "2.0", "uid": uid, "type": type_, "time": cot_time(now), "start": cot_time(now),
                                 "stale": cot_time(stale), "how": how})
    ET.SubElement(event, "point", {"lat": f"{lat:.5f}", "lon": f"{lon:.5f}", "hae": UNKNOWN, "ce": f"{ce:.1f}", "le": UNKNOWN})
    detail = ET.SubElement(event, "detail")
    if emergency is not None and emergency[1]:        # ATAK's cancel message carries only the emergency element
        ET.SubElement(detail, "emergency", {"cancel": "true"}).text = emergency[0]
        return ET.tostring(event, encoding="unicode").encode("utf-8")
    if color is not None:
        ET.SubElement(detail, "status", {"readiness": "true"})
    if link_uid:
        ET.SubElement(detail, "link", {"uid": link_uid, "type": TYPE_UNIT, "relation": "p-p"})
    ET.SubElement(detail, "contact", {"callsign": callsign + ("-Alert" if emergency is not None else "")})
    if emergency is not None:
        ET.SubElement(detail, "emergency", {"type": "911 Alert"}).text = emergency[0]
    ET.SubElement(detail, "remarks", {"source": "RescueCodec"}).text = remarks
    if color is not None:
        ET.SubElement(detail, "color", {"argb": color})
        ET.SubElement(detail, "usericon", {"iconsetpath": f"COT_MAPPING_SPOTMAP/{type_}/{color}"})
    return ET.tostring(event, encoding="unicode").encode("utf-8")


# ---------------------------------------------------------------- building events from gateway data

def _state_note(task: dict[str, Any]) -> str:
    state = task.get("state", "open")
    if state == "open":
        return "OPEN"
    last = next((a for a in reversed(task.get("acks", [])) if a["status"] == state), None)
    who = f" by {last['team']}" if last and last.get("team") else ""
    eta = f", ETA {last['eta']} min" if last and last.get("eta") else ""
    return f"{state.upper()}{who}{eta}"


def _channel_note(item: dict[str, Any]) -> str:
    name = (item.get("channel") or {}).get("name")
    return f" (channel {name})" if name else ""


def task_events(task: dict[str, Any], *, now: datetime, stale_min: int = 360,
                fallback_pos: Callable[[str], dict[str, Any] | None] = lambda node: None) -> list[bytes]:
    """CoT events for a Task object (found, request, hazard, mayday). Empty if there is no position."""
    if task.get("type") not in TASK_TYPES:
        return []
    pos = task.get("pos") or fallback_pos(task["from"])
    if not pos:
        return []
    ce = CE_BY_ACC.get(pos.get("acc", "gps"), 10.0) if task.get("pos") else CE_BY_ACC["area"]
    done = task["state"] == "done"
    stale = now if done else now + timedelta(minutes=stale_min)
    uid = f"RescueCodec.{task['key']}"
    label = {"found": "FOUND", "request": "REQUEST", "hazard": "HAZARD", "mayday": "MAYDAY"}[task["type"]]
    remarks = f"{task['text']['en']} [{_state_note(task)}]{_channel_note(task)}"
    callsign = f"{label} {task['key']}"
    if task["type"] == "mayday":
        cancel = done
        if cancel:
            stale = now + timedelta(seconds=10)      # like ATAK's own cancel: not stale yet, so TAK acts on it
        return [build_event(uid=uid, type_=TYPE_ALERT_CANCEL if cancel else TYPE_ALERT, lat=pos["lat"], lon=pos["lon"],
                            now=now, stale=stale, callsign=callsign, remarks=remarks, ce=ce,
                            emergency=(callsign, cancel), link_uid=f"RescueCodec.node-{task['from'][1:]}")]
    color = {"found": RED, "hazard": ORANGE, "request": BLUE}[task["type"]]
    return [build_event(uid=uid, type_=TYPE_MARKER, lat=pos["lat"], lon=pos["lon"], now=now, stale=stale,
                        callsign=callsign, remarks=remarks, ce=ce, color=color)]


def message_events(record: dict[str, Any], *, now: datetime, stale_min: int = 360) -> list[bytes]:
    """CoT events for a MessageRecord that is not a task: sector (marker) and track (unit at the last point)."""
    message = record["message"]
    if record["type"] == "sector" and message.get("pos"):
        pos, body = message["pos"], message["sector"]
        color = {"cleared": GREEN, "partial": YELLOW, "blocked": RED}.get(body["status"], YELLOW)
        uid = f"RescueCodec.sector-{record['from'][1:]}-{body['sector']}"
        return [build_event(uid=uid, type_=TYPE_MARKER, lat=pos["lat"], lon=pos["lon"], now=now,
                            stale=now + timedelta(minutes=stale_min), callsign=f"SECTOR {body['sector']}",
                            remarks=record["text"]["en"] + _channel_note(record), ce=CE_BY_ACC.get(pos.get("acc", "gps"), 10.0),
                            color=color)]
    if record["type"] == "track" and message["track"]["points"]:
        last = message["track"]["points"][-1]
        return [build_event(uid=f"RescueCodec.track-{record['from'][1:]}", type_=TYPE_UNIT, lat=last["lat"], lon=last["lon"],
                            now=now, stale=now + timedelta(minutes=10), callsign=f"Team {record['from']}",
                            remarks=record["text"]["en"] + _channel_note(record), how="m-g")]
    return []


# ---------------------------------------------------------------- sending

class UdpMulticastSink:
    def __init__(self, group: str = "239.2.3.1", port: int = 6969, iface: str | None = None, ttl: int = 1):
        self.group, self.port, self.iface, self.ttl = group, port, iface, ttl
        self._sock: socket.socket | None = None
        self._last_warning = 0.0

    def _socket(self) -> socket.socket:
        if self._sock is None:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, self.ttl)
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_LOOP, 1)
            if self.iface:
                sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(self.iface))
            self._sock = sock
        return self._sock

    def send(self, data: bytes) -> bool:
        try:
            self._socket().sendto(data, (self.group, self.port))
            return True
        except OSError as exc:
            if exc.errno in (errno.ENETUNREACH, errno.EHOSTUNREACH, errno.ENETDOWN) and self.iface is None:
                # no network (Wi-Fi off in the field): fall back to this computer so a local TAK app still sees it
                log.info("no multicast route, using the loopback interface")
                self.iface = "127.0.0.1"
                self.close()
                return self.send(data)
            if time.monotonic() - self._last_warning > 60:
                self._last_warning = time.monotonic()
                log.warning("CoT multicast send failed: %s", exc)
            return False

    def close(self) -> None:
        if self._sock is not None:
            self._sock.close()
            self._sock = None


class TcpSink:
    """Keeps one TCP connection to a TAK server, reconnects when it drops. Never blocks the caller."""

    def __init__(self, host: str, port: int, retry_s: float = 5.0):
        self.host, self.port, self.retry_s = host, port, retry_s
        self._queue: queue.Queue[bytes | None] = queue.Queue(maxsize=500)
        self._thread = threading.Thread(target=self._run, name="cot-tcp", daemon=True)
        self.connected = False
        self._started = False

    def send(self, data: bytes) -> bool:
        if not self._started:
            self._started = True
            self._thread.start()
        try:
            self._queue.put_nowait(data)
            return True
        except queue.Full:
            return False

    def _run(self) -> None:
        sock: socket.socket | None = None
        while True:
            item = self._queue.get()
            if item is None:
                break
            while True:
                try:
                    if sock is None:
                        sock = socket.create_connection((self.host, self.port), timeout=5)
                        self.connected = True
                        log.info("connected to the TAK server %s:%s", self.host, self.port)
                    sock.sendall(item)
                    break
                except OSError as exc:
                    self.connected = False
                    if sock is not None:
                        sock.close()
                        sock = None
                    log.warning("TAK server %s:%s not reachable (%s), retrying", self.host, self.port, exc)
                    time.sleep(self.retry_s)
        if sock is not None:
            sock.close()

    def close(self) -> None:
        self._queue.put(None)


class CotConnector:
    """Listens to gateway events and sends CoT. `sinks` is a list of objects with `send(bytes)`."""

    def __init__(self, sinks: list[Any], *, stale_min: int = 360,
                 fallback_pos: Callable[[str], dict[str, Any] | None] = lambda node: None):
        self.sinks, self.stale_min, self.fallback_pos = sinks, stale_min, fallback_pos
        self.sent = 0

    def handle(self, event: str, data: Any) -> None:
        now = datetime.now(timezone.utc)
        if event == "task":
            events = task_events(data, now=now, stale_min=self.stale_min, fallback_pos=self.fallback_pos)
        elif event == "message":
            events = message_events(data, now=now, stale_min=self.stale_min)
        else:
            return
        for xml in events:
            for sink in self.sinks:
                if sink.send(xml):
                    self.sent += 1

    def close(self) -> None:
        for sink in self.sinks:
            if hasattr(sink, "close"):
                sink.close()
