"""The gateway core: radio in, store, tasks, send queue, events. No HTTP in here (see app.py).

Threads: the radio adapter calls `_on_packet` from its own thread; a sender thread pushes the send queue to the
radio; a ticker thread watches delivery timeouts and publishes stats. One re-entrant lock protects all state.
Events are published to the bus while the lock is held, so every listener sees them in order. Listeners must be quick
(they only hand the event to a queue).
"""
from __future__ import annotations

import logging
import math
import threading
import time
from collections import deque
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

from rescuecodec.encoding.airtime import PRESETS, message_airtime_seconds
from rescuecodec.engine.extract import fit_plain, fit_text
from rescuecodec.engine.sendqueue import BUDGET_LIMIT, Deduper, SendQueue
from rescuecodec.engine.tasks import InvalidTransition, Task, TaskBoard, TASK_TYPES, task_key
from rescuecodec.gateway import __version__
from rescuecodec.gateway.config import GatewayConfig
from rescuecodec.gateway.connectors.geojson import feature_collection
from rescuecodec.gateway.events import EventBus
from rescuecodec.gateway.radio.base import BROADCAST, PORT_TEXT_MESSAGE, TEXT_COPY_PREFIX, RadioAdapter, RadioPacket
from rescuecodec.gateway.store import Store
from rescuecodec.v2 import codes as C
from rescuecodec.v2.codec import (CodecError, decode, effective_priority, encode, message_type, node_from_json,
                                  resolve_time)
from rescuecodec.v2.compat import decode_any
from rescuecodec.v2.render import render

log = logging.getLogger("rescuecodec.gateway")

NO_NODE = "!00000000"
PRIORITY_LEVEL = {"routine": 1, "normal": 2, "urgent": 3, "critical": 4}
#: `codec.version` of a record that is a plain Meshtastic text (port 1), not a RescueCodec packet. `codec.hex` then
#: holds the UTF-8 bytes of the text (SPEC section 2.1).
PLAIN_TEXT_VERSION = 0
PLAIN_DEDUPE_S = 24 * 3600          # a plain text is a repeat when (sender, packet id) came in during the last 24 h


class ApiError(Exception):
    """An error with an HTTP status and the JSON body of openapi.yaml (`Error`)."""

    def __init__(self, status: int, code: str, message: str, field: str | None = None, retry_after_s: int | None = None):
        super().__init__(message)
        self.status, self.code, self.message, self.field, self.retry_after_s = status, code, message, field, retry_after_s

    def body(self) -> dict[str, Any]:
        error: dict[str, Any] = {"code": self.code, "message": self.message, "field": self.field}
        if self.retry_after_s is not None:
            error["retry_after_s"] = self.retry_after_s
        return {"error": error}


def codec_error(exc: CodecError) -> ApiError:
    """CodecError -> 400 with the plain message (CodecError's own text carries the code and field)."""
    message = exc.args[0] if exc.args else str(exc)
    prefix, suffix = f"{exc.code}: ", f" ({exc.field})" if exc.field else ""
    if message.startswith(prefix):
        message = message[len(prefix):]
    if suffix and message.endswith(suffix):
        message = message[:-len(suffix)]
    return ApiError(400, exc.code, message, exc.field)


def iso(moment: datetime) -> str:
    return moment.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_iso(text: str) -> datetime:
    return datetime.strptime(text, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)


def public_message(message: dict[str, Any]) -> dict[str, Any]:
    """Decoded message for the API: v2 needs no "v" key, a v1 message keeps "v": 1."""
    out = dict(message)
    if out.get("v") == 2:
        del out["v"]
    return out


def readable(message: dict[str, Any]) -> dict[str, str]:
    try:
        return {"en": render(message, "en"), "de": render(message, "de")}
    except Exception:
        log.exception("could not render a message")
        return {"en": "", "de": ""}


def metric(value: Any, unit: str, source: str, note: str | None = None) -> dict[str, Any]:
    out: dict[str, Any] = {"value": value, "unit": unit, "source": source}
    if note:
        out["note"] = note
    return out


def is_plain(record: dict[str, Any]) -> bool:
    """True for a plain Meshtastic text record (port 1)."""
    return record.get("codec", {}).get("version") == PLAIN_TEXT_VERSION


@dataclass
class _Out:
    record_seq: int
    want_ack: bool
    port: int


class GatewayService:
    def __init__(self, config: GatewayConfig, radio: RadioAdapter, store: Store | None = None, bus: EventBus | None = None):
        self.config = config
        self.radio = radio
        self.store = store or Store(config.db_path)
        self.bus = bus or EventBus()
        self.lock = threading.RLock()
        self.board = TaskBoard()
        self.queue = SendQueue(preset=config.preset if config.preset else "LONG_FAST")
        self.deduper = Deduper()
        self.started = datetime.now(timezone.utc)
        self.mqtt_connected = False

        self._task_seq: dict[str, int] = {}            # task key -> seq of the record that created it
        self._queued: dict[int, _Out] = {}             # send queue item seq -> record
        self._awaiting: dict[int, float] = {}          # record seq -> deadline (monotonic) for the Meshtastic answer
        self._plain: deque[str] = deque(maxlen=50)     # readable copies waiting for the text port (--text-over-air)
        self._texts_seen: dict[tuple[str, Any], float] = {}  # plain texts heard: (sender, packet id) -> unix time
        self._heard: dict[str, dict[str, Any]] = {}    # node -> last rssi / snr / hops / time heard
        self._counters = {"in": 0, "out": 0, "bytes_in": 0, "bytes_out": 0, "text_bytes": 0, "airtime_s": 0.0,
                          "rejected": 0, "duplicates": 0}
        self._by_type: dict[str, dict[str, int]] = {}
        self._tx_times: deque[float] = deque()         # monotonic times of the last API sends (rate limit)
        self._last_rssi: int | None = None
        self._last_snr: float | None = None
        self._stats_since = datetime.now(timezone.utc)

        self._stop = threading.Event()
        self._wake = threading.Event()
        self._threads: list[threading.Thread] = []

    # ------------------------------------------------------------------ lifecycle
    def start(self) -> None:
        self._replay()
        self.radio.on_packet = self._on_packet
        self.radio.on_connection = self._on_connection
        self.radio.on_node = self._on_node
        self._stop.clear()
        for name, target in (("sender", self._sender_loop), ("ticker", self._ticker_loop)):
            thread = threading.Thread(target=target, name=f"gateway-{name}", daemon=True)
            thread.start()
            self._threads.append(thread)
        self.radio.start()

    def stop(self) -> None:
        self._stop.set()
        self._wake.set()
        self.radio.stop()
        for thread in self._threads:
            thread.join(timeout=3)
        self._threads.clear()
        self.store.close()

    def _replay(self) -> None:
        """Rebuild tasks, the dedupe memory and the id counter from the database after a restart."""
        seq = 0
        while True:
            rows = self.store.list(since_seq=seq, limit=500)
            if not rows:
                break
            for record in rows:
                seq = record["seq"]
                if record["direction"] == "out" and record["status"] == "queued":
                    self.store.update_status(seq, "failed", "gateway_restarted")
                    record["status"] = "failed"
                when = parse_iso(record["time"])
                message = dict(record["message"])
                if record["direction"] == "in" and is_plain(record):
                    self._text_seen_before(record["from"], (record.get("radio") or {}).get("packet_id"),
                                           bytes.fromhex(record["codec"]["hex"]), when.timestamp())
                elif record["direction"] == "in":
                    self.deduper.seen(record["from"], message, bytes.fromhex(record["codec"]["hex"]), when.timestamp())
                self._apply_to_board(record, message, when)
                self._restore_stats(record, when)

    def _restore_stats(self, record: dict[str, Any], when: datetime) -> None:
        """Count a stored record again after a restart, so the stats and the airtime budget do not start from zero.
        `since` then names the first stored message."""
        if self._counters["in"] + self._counters["out"] == 0:
            self._stats_since = when
        self._count(record)
        if record["direction"] == "out" and record["status"] in ("sent", "delivered", "failed"):
            airtime = message_airtime_seconds(record["codec"]["size"], self.queue.preset)
            self._counters["airtime_s"] += airtime
            age = (datetime.now(timezone.utc) - when).total_seconds()
            if 0 <= age < self.queue.budget.window_s:
                self.queue.budget.record(time.monotonic() - age, airtime)

    # ------------------------------------------------------------------ small helpers
    @property
    def own_node(self) -> str | None:
        return self.radio.info().node

    def _publish(self, event: str, data: Any) -> None:
        self.bus.publish(event, data)

    def _build_record(self, direction: str, sender: str, message: dict[str, Any], payload: bytes, now: datetime,
                      status: str, radio: dict[str, Any] | None) -> dict[str, Any]:
        """A MessageRecord without `seq`."""
        kind = message_type(message)
        public = public_message(message)
        key = task_key(sender, message["id"]) if message.get("id") else None
        if kind == "ack":
            ack = message["ack"]
            tkey = task_key(ack["node"], ack["ref"]) if ack.get("node") else None
        elif kind in TASK_TYPES:
            tkey = key
        else:
            tkey = None
        event_time = None
        try:
            if message.get("v1_timestamp"):
                event_time = iso(datetime.fromtimestamp(message["v1_timestamp"], timezone.utc))
            elif message.get("time"):
                event_time = iso(resolve_time(message["time"], now))
        except Exception:
            event_time = None
        priority = effective_priority(message)
        if priority not in PRIORITY_LEVEL:
            priority = "normal"
        return {"key": key, "direction": direction, "from": sender, "type": kind, "priority": priority, "status": status,
                "error": None, "time": iso(now), "event_time": event_time,
                "codec": {"version": 1 if message.get("v") == 1 else 2, "size": len(payload), "hex": payload.hex()},
                "radio": radio, "message": public, "text": readable(message), "task_key": tkey}

    def _plain_record(self, direction: str, sender: str, text: str, payload: bytes, now: datetime, status: str,
                      radio: dict[str, Any] | None, priority: str | None = None) -> dict[str, Any]:
        """A MessageRecord without `seq` for a plain Meshtastic text: type text, codec.version 0, no id, no task."""
        message: dict[str, Any] = {"text": {"text": text}}
        if priority in PRIORITY_LEVEL and priority != "normal":
            message = {"priority": priority, **message}
        return {"key": None, "direction": direction, "from": sender, "type": "text",
                "priority": priority if priority in PRIORITY_LEVEL else "normal", "status": status, "error": None,
                "time": iso(now), "event_time": None,
                "codec": {"version": PLAIN_TEXT_VERSION, "size": len(payload), "hex": payload.hex()},
                "radio": radio, "message": message, "text": readable(message), "task_key": None}

    def _copy_line(self, record: dict[str, Any]) -> str:
        """The readable copy of a RescueCodec record for stock apps, marked so RescueCodec receivers skip it."""
        return TEXT_COPY_PREFIX + record["text"][self.config.text_lang if self.config.text_lang in ("en", "de") else "en"]

    def _text_seen_before(self, sender: str, packet_id: Any, payload: bytes, now: float) -> bool:
        """Dedupe for plain texts: the same (sender, Meshtastic packet id) within 24 h. Without a packet id: the
        same (sender, bytes) within 10 minutes. Remembers the text and returns False when it is new."""
        ttl = PLAIN_DEDUPE_S if packet_id else 600
        key = (sender, packet_id) if packet_id else (sender, bytes(payload))
        if len(self._texts_seen) > 1000:
            self._texts_seen = {k: t for k, t in self._texts_seen.items() if now - t < PLAIN_DEDUPE_S}
        seen = self._texts_seen.get(key)
        if seen is not None and now - seen < ttl:
            return True
        self._texts_seen[key] = now
        return False

    def _apply_to_board(self, record: dict[str, Any], message: dict[str, Any], when: datetime) -> Task | None:
        """Feed a record to the task board. Returns the task if it is a real (not placeholder) task that changed."""
        kind = record["type"]
        if kind not in TASK_TYPES and kind != "ack":
            return None
        task = self.board.on_message(record["from"], message, when)
        if task is None:
            return None
        if kind == "ack":
            task.acks[-1]["seq"] = record["seq"]
        else:
            self._task_seq.setdefault(task.key, record["seq"])
        return task if task.type else None

    def _count(self, record: dict[str, Any]) -> None:
        direction = record["direction"]
        self._counters[direction] += 1
        self._counters["bytes_" + direction] += record["codec"]["size"]
        # A plain text is text already: it counts as its own size, so it never shows a saving it did not make.
        self._counters["text_bytes"] += record["codec"]["size"] if is_plain(record) else len(record["text"]["en"].encode("utf-8"))
        slot = self._by_type.setdefault(record["type"], {"count": 0, "bytes": 0})
        slot["count"] += 1
        slot["bytes"] += record["codec"]["size"]

    # ------------------------------------------------------------------ receiving
    def _on_packet(self, packet: RadioPacket) -> None:
        if packet.port == PORT_TEXT_MESSAGE:
            self._on_text(packet)
            return
        if packet.port != self.config.port_num:
            return
        try:
            message = decode_any(packet.payload)
        except CodecError as exc:
            self._counters["rejected"] += 1
            log.info("ignored a packet from %s that is not valid RescueCodec (%s)", packet.sender, exc)
            return
        now = datetime.now(timezone.utc)
        with self.lock:
            if self.deduper.seen(packet.sender, message, packet.payload, now.timestamp()):
                self._counters["duplicates"] += 1
                log.debug("duplicate from %s dropped", packet.sender)
                return
            radio = {"packet_id": packet.packet_id, "rssi": packet.rssi, "snr": packet.snr, "hops": packet.hops,
                     "channel": packet.channel, "via": packet.via}
            record = self.store.insert(self._build_record("in", packet.sender, message, packet.payload, now, "received", radio))
            self._count(record)
            self._heard[packet.sender] = {"rssi": packet.rssi, "snr": packet.snr, "hops": packet.hops, "time": now}
            if packet.rssi is not None:
                self._last_rssi = packet.rssi
            if packet.snr is not None:
                self._last_snr = packet.snr
            task = self._apply_to_board(record, message, now)
            self._publish("message", record)
            if task:
                self._publish("task", self._task_json(task))
            if self.config.text_over_air in ("in", "both"):
                self._plain.append(self._copy_line(record))
                self._wake.set()
        log.info("received %s from %s (%d bytes, rssi %s, snr %s, hops %s)", record["type"], packet.sender,
                 record["codec"]["size"], packet.rssi, packet.snr, packet.hops)

    def _on_text(self, packet: RadioPacket) -> None:
        """A plain Meshtastic text (port 1), for example from the official app. The radio adapter passes only texts on
        the team channel and direct messages to us. Stored as a normal `text` record with codec.version 0."""
        own = self.own_node
        if packet.to != BROADCAST and packet.to != own:        # a direct message to another node: not ours
            return
        try:
            text = packet.payload.decode("utf-8")
        except UnicodeDecodeError:
            self._counters["rejected"] += 1
            log.info("ignored a text from %s that is not UTF-8", packet.sender)
            return
        if not text.strip():
            return
        if text.startswith(TEXT_COPY_PREFIX):
            # A gateway's readable copy of a RescueCodec message (--text-over-air). We have the message from port 256.
            self._counters["duplicates"] += 1
            log.debug("readable copy from %s dropped", packet.sender)
            return
        now = datetime.now(timezone.utc)
        with self.lock:
            if self._text_seen_before(packet.sender, packet.packet_id, packet.payload, now.timestamp()):
                self._counters["duplicates"] += 1
                log.debug("duplicate text from %s dropped", packet.sender)
                return
            radio = {"packet_id": packet.packet_id, "rssi": packet.rssi, "snr": packet.snr, "hops": packet.hops,
                     "channel": packet.channel, "via": packet.via}
            record = self.store.insert(self._plain_record("in", packet.sender, text, packet.payload, now, "received", radio))
            self._count(record)
            self._heard[packet.sender] = {"rssi": packet.rssi, "snr": packet.snr, "hops": packet.hops, "time": now}
            if packet.rssi is not None:
                self._last_rssi = packet.rssi
            if packet.snr is not None:
                self._last_snr = packet.snr
            self._publish("message", record)
        log.info("received a Meshtastic text from %s (%d bytes, %s, rssi %s, snr %s, hops %s)", packet.sender,
                 len(packet.payload), "direct" if packet.to != BROADCAST else f"channel {packet.channel}",
                 packet.rssi, packet.snr, packet.hops)

    def _on_connection(self, connected: bool) -> None:
        if connected:
            info = self.radio.info()
            if info.preset in PRESETS:
                self.queue.preset = info.preset
        self._publish("health", self.health())
        self._wake.set()

    def _on_node(self, node: dict[str, Any]) -> None:
        self._publish("node", self._node_json(node, self.own_node))

    def set_mqtt_state(self, connected: bool) -> None:
        self.mqtt_connected = connected
        self._publish("health", self.health())

    # ------------------------------------------------------------------ sending
    def _retry_after_s(self, kind: str, airtime: float, now: float) -> int:
        """Seconds until the airtime budget has room for this message (estimate)."""
        budget = self.queue.budget
        limit = (BUDGET_LIMIT.get(kind) or 1.0) * budget.budget_s
        entries = sorted(budget._sent)           # (time, seconds), oldest first
        used = sum(seconds for t, seconds in entries if t > now - budget.window_s)
        for t, seconds in entries:
            if t <= now - budget.window_s:
                continue
            used -= seconds
            if used + airtime <= limit + 1e-9:
                return max(1, math.ceil(t + budget.window_s - now))
        return max(1, math.ceil(budget.window_s))

    def _check_tx_rate(self, now: float) -> None:
        """At most `tx_per_minute` messages from the API per minute. Protects the radio and the airtime from a runaway client."""
        limit = self.config.tx_per_minute
        if limit <= 0:
            return
        while self._tx_times and self._tx_times[0] <= now - 60:
            self._tx_times.popleft()
        if len(self._tx_times) >= limit:
            retry = max(1, math.ceil(self._tx_times[0] + 60 - now))
            raise ApiError(429, "rate_limited", f"more than {limit} messages in one minute, send again in {retry} s", None, retry)
        self._tx_times.append(now)

    def _queue_message(self, message: dict[str, Any], *, want_ack: bool, dry_run: bool = False) -> dict[str, Any]:
        """Validate, assign an id, queue and record an outgoing message. Raises ApiError."""
        if not isinstance(message, dict):
            raise ApiError(400, "bad_request", "message must be a JSON object", "message")
        message = {k: v for k, v in message.items() if k != "v"}
        try:
            kind = message_type(message)
        except CodecError as exc:
            raise codec_error(exc) from exc
        # A message with only a text goes out as a plain Meshtastic text (port 1) when --text-as-meshtastic is on, so
        # the official app and radio screens show it. Only the words go out (SPEC section 2.1).
        plain = kind == "text" and self.config.text_as_meshtastic
        with self.lock:
            assigned = None
            # Every message except an Ack gets an id, so a second "Hilfe" is not dropped as a repeat of the first
            # (SPEC section 14). It costs 1 to 2 bytes. Acks are repeats by design and carry none.
            if kind != "ack" and not message.get("id") and not plain:
                assigned = self.store.peek_message_id()
                message["id"] = assigned
            words = message.get("text", {}).get("text") if plain and isinstance(message.get("text"), dict) else None
            message = dict(fit_text(message))      # a long text plus position and id is cut to fit, like on the phone
            try:
                payload = encode(message)           # also checks a text that goes out plain: bad fields stay an error
                canonical = decode(payload)
            except CodecError as exc:
                raise codec_error(exc) from exc
            now = datetime.now(timezone.utc)
            sender = self.own_node or NO_NODE
            port = self.config.port_num
            if plain:                               # the words only, at most 200 bytes, no id, position or time
                words = fit_plain(words)
                payload, port = words.encode("utf-8"), PORT_TEXT_MESSAGE
                canonical = {"text": {"text": words}}
                if message.get("priority"):
                    canonical = {"priority": message["priority"], **canonical}

            def build(status: str) -> dict[str, Any]:
                if plain:
                    return self._plain_record("out", sender, words, payload, now, status, None,
                                              effective_priority(canonical))
                return self._build_record("out", sender, canonical, payload, now, status, None)

            if dry_run:
                return {"seq": 0, **build("dry_run")}
            if not self.radio.connected:
                raise ApiError(503, "radio_unavailable", "no Meshtastic radio connected")
            channel_error = self.radio.info().channel_error
            if channel_error:
                raise ApiError(503, "radio_unavailable", f"no private channel to send on: {channel_error}")
            pending = self.store.find_pending_hex(payload.hex())
            if pending is not None:                       # the same bytes already wait in the queue
                return pending
            self._check_tx_rate(time.monotonic())
            airtime = message_airtime_seconds(len(payload), self.queue.preset)
            clock = time.monotonic()
            if kind != "mayday" and not self.queue.budget.allows(kind, airtime, clock):
                retry = self._retry_after_s(kind, airtime, clock)
                minutes = max(1, math.ceil(retry / 60))
                raise ApiError(429, "airtime_limit", f"airtime budget used, send again in {minutes} min", None, retry)
            item = self.queue.put(canonical, payload=payload)
            if item is None:                              # cannot happen after the check above
                raise ApiError(409, "bad_request", "the same message is already queued")
            record = self.store.insert(build("queued"))
            self._queued[item.seq] = _Out(record["seq"], want_ack, port)
            if assigned:
                self.store.commit_message_id(assigned)
            self._count(record)
            task = self._apply_to_board(record, canonical, now)
            self._publish("message", record)
            if task:
                self._publish("task", self._task_json(task))
            if self.config.text_over_air in ("out", "both") and not plain:     # a plain text needs no copy
                self._plain.append(self._copy_line(record))
            self._wake.set()
            return record

    def send_message(self, message: Any, want_ack: bool = True, dry_run: bool = False) -> dict[str, Any]:
        return self._queue_message(message, want_ack=want_ack, dry_run=dry_run)

    def send_text(self, text: Any, priority: str | None = None, also_plain: bool = False) -> dict[str, Any]:
        if not isinstance(text, str):
            raise ApiError(400, "bad_request", "text must be a string", "text")
        message: dict[str, Any] = {"text": {"text": text}}
        if priority is not None:
            message["priority"] = priority
        record = self._queue_message(message, want_ack=True)
        if also_plain and not is_plain(record):          # sent as a plain text already: no second copy
            with self.lock:
                self._plain.append(TEXT_COPY_PREFIX + text)
                self._wake.set()
        return record

    def ack_task(self, key: str, status: Any, eta: Any = None, team: Any = None) -> tuple[dict[str, Any], dict[str, Any]]:
        """Accept, decline or finish a task. Returns (task JSON, record)."""
        if status not in C.ACK_STATUS:
            raise ApiError(400, "invalid_value", "status must be accepted, declined or done", "status")
        if eta is not None and (isinstance(eta, bool) or not isinstance(eta, int) or not 1 <= eta <= C.MAX_ETA_MIN):
            raise ApiError(400, "invalid_value", f"eta must be 1..{C.MAX_ETA_MIN} minutes", "eta")
        if team is not None and not isinstance(team, str):
            raise ApiError(400, "invalid_value", "team must be a string", "team")
        with self.lock:
            task = self.board.get(key)
            if task is None or task.type is None:
                raise ApiError(404, "not_found", f"no task {key}")
            if not self.radio.connected:
                raise ApiError(503, "radio_unavailable", "no Meshtastic radio connected")
            old_state, old_updated = task.state, task.updated
            try:
                ack = self.board.transition(key, status, eta=eta or 0, team=team if team is not None else self.config.team)
            except InvalidTransition as exc:
                raise ApiError(409, "invalid_transition", str(exc), "status") from exc
            try:
                record = self._queue_message(ack, want_ack=True)
            except Exception:
                task.state, task.updated = old_state, old_updated
                raise
            task.state = self.board.state_of(task)    # also right when the same Ack already waited in the queue
            return self._task_json(task), record

    def _sender_loop(self) -> None:
        next_ok = 0.0
        while not self._stop.is_set():
            self._wake.wait(timeout=0.5)
            self._wake.clear()
            while not self._stop.is_set() and self.radio.connected:
                pause = next_ok - time.monotonic()
                if pause > 0:
                    self._stop.wait(min(pause, 0.5))
                    continue
                with self.lock:
                    item = self.queue.pop(time.monotonic())
                    plain = None if item else self._pop_plain()
                if item is None and plain is None:
                    break
                try:
                    airtime = self._transmit(item) if item else self._transmit_plain(plain)
                except Exception:
                    log.exception("sending failed")
                    continue
                next_ok = time.monotonic() + min(max(airtime, 0.2), 3.0)

    def _pop_plain(self) -> str | None:
        if not self._plain:
            return None
        air = message_airtime_seconds(len(fit_plain(self._plain[0]).encode("utf-8")), self.queue.preset)
        now = time.monotonic()
        if not self.queue.budget.allows("text", air, now):
            return None
        text = fit_plain(self._plain.popleft())
        self.queue.budget.record(now, air)
        return text

    def _transmit(self, item) -> float:
        with self.lock:
            out = self._queued.pop(item.seq, None)
            if out is None:
                return 0.0
            record_seq = out.record_seq
            record = self.store.update_status(record_seq, "sent", None)
            self._counters["airtime_s"] += item.airtime_s
            if record:
                self._publish("message_status", {"seq": record_seq, "status": "sent", "error": None, "updated": iso(datetime.now(timezone.utc))})
            if out.want_ack:
                self._awaiting[record_seq] = time.monotonic() + self.config.ack_timeout_s
        callback = (lambda ok, reason: self._on_delivery(record_seq, ok, reason)) if out.want_ack else None
        try:
            self.radio.send(item.payload, port=out.port, want_ack=out.want_ack,
                            priority=item.meshtastic_priority, channel=self.config.channel_index,
                            hop_limit=self.config.hop_limit, on_result=callback)
        except Exception as exc:
            self._awaiting.pop(record_seq, None)
            self._finish(record_seq, "failed", "radio_error")
            log.warning("radio refused the packet: %s", exc)
        return item.airtime_s

    def _transmit_plain(self, text: str) -> float:
        self.radio.send(text.encode("utf-8"), port=PORT_TEXT_MESSAGE, want_ack=False, channel=self.config.channel_index,
                        hop_limit=self.config.hop_limit)
        airtime = message_airtime_seconds(len(text.encode("utf-8")), self.queue.preset)
        with self.lock:
            self._counters["airtime_s"] += airtime
        return airtime

    def _on_delivery(self, record_seq: int, delivered: bool, reason: str | None) -> None:
        with self.lock:
            if self._awaiting.pop(record_seq, None) is None:
                return
            self._finish(record_seq, "delivered" if delivered else "failed", None if delivered else (reason or "no_ack"))

    def _finish(self, record_seq: int, status: str, error: str | None) -> None:
        with self.lock:
            record = self.store.update_status(record_seq, status, error)
            if record:
                self._publish("message_status", {"seq": record_seq, "status": status, "error": error,
                                                 "updated": iso(datetime.now(timezone.utc))})

    def _ticker_loop(self) -> None:
        last_stats = time.monotonic()
        while not self._stop.wait(1.0):
            now = time.monotonic()
            with self.lock:
                for seq, deadline in list(self._awaiting.items()):
                    if now >= deadline:
                        del self._awaiting[seq]
                        self._finish(seq, "failed", "no_ack")
            if now - last_stats >= 10:
                last_stats = now
                self._publish("stats", self.stats())

    # ------------------------------------------------------------------ reading
    def _task_json(self, task: Task) -> dict[str, Any]:
        message = task.message or {}
        acks = [{"by": a["by"], "status": a["status"], "eta": a.get("eta"), "team": a.get("team"),
                 "time": iso(a["time"]), "seq": a.get("seq", 0)} for a in task.acks]
        return {"key": task.key, "from": task.sender, "id": task.id, "type": task.type, "priority": task.priority,
                "state": task.state, "created": iso(task.created), "updated": iso(task.updated),
                "pos": message.get("pos"), "text": readable(message), "message_seq": self._task_seq.get(task.key, 0),
                "acks": acks}

    def list_tasks(self, states: set[str] | None = None, types: set[str] | None = None) -> list[dict[str, Any]]:
        with self.lock:
            rows = [t for t in self.board.list(states or None) if t.type and (not types or t.type in types)]
            rows.sort(key=lambda t: (-PRIORITY_LEVEL.get(t.priority or "normal", 2), t.created))
            return [self._task_json(t) for t in rows]

    def get_task(self, key: str) -> dict[str, Any]:
        with self.lock:
            task = self.board.get(key)
            if task is None or task.type is None:
                raise ApiError(404, "not_found", f"no task {key}")
            return self._task_json(task)

    def list_messages(self, since_seq: int = 0, types: list[str] | None = None, direction: str | None = None,
                      limit: int = 100) -> dict[str, Any]:
        items = self.store.list(since_seq, types, direction, limit)
        return {"items": items, "next_since_seq": items[-1]["seq"] if items else since_seq}

    def get_message(self, seq: int) -> dict[str, Any]:
        record = self.store.get(seq)
        if record is None:
            raise ApiError(404, "not_found", f"no message {seq}")
        return record

    def health(self) -> dict[str, Any]:
        info = self.radio.info()
        return {"status": "ok" if info.connected else "degraded", "version": __version__, "spec_version": C.SPEC_VERSION,
                "uptime_s": int((datetime.now(timezone.utc) - self.started).total_seconds()),
                "radio": info.as_health(self.config.port_num), "mqtt": {"connected": self.mqtt_connected}}

    def stats(self) -> dict[str, Any]:
        with self.lock:
            c = self._counters
            total = c["bytes_in"] + c["bytes_out"]
            used = self.queue.budget.used_s(time.monotonic())
            return {
                "since": iso(self._stats_since),
                "messages_in": metric(c["in"], "count", "measured"),
                "messages_out": metric(c["out"], "count", "measured"),
                "bytes_in": metric(c["bytes_in"], "B", "measured"),
                "bytes_out": metric(c["bytes_out"], "B", "measured"),
                "text_equivalent_bytes": metric(c["text_bytes"], "B", "measured", "UTF-8 length of text.en for the same messages"),
                "size_ratio": metric(round(c["text_bytes"] / total, 2) if total else None, "x", "measured"),
                "airtime_out_s": metric(round(c["airtime_s"], 2), "s", "estimated",
                                        f"{self.queue.preset}, +23 B Meshtastic overhead per packet"),
                "airtime_last_hour_pct": metric(round(100 * used / self.queue.budget.window_s, 3), "%", "estimated"),
                "airtime_limit_pct": metric(100 * self.queue.budget.duty_cycle, "%", "measured", "EU 869.4-869.65 MHz rule"),
                "last_rssi": metric(self._last_rssi, "dBm", "measured"),
                "last_snr": metric(self._last_snr, "dB", "measured"),
                "by_type": {k: dict(v) for k, v in self._by_type.items()},
            }

    def _node_json(self, node: dict[str, Any], own: str | None) -> dict[str, Any]:
        heard = self._heard.get(node["node"], {})
        last = node.get("last_heard")
        if heard.get("time") and (not last or heard["time"].timestamp() > last):
            last = heard["time"].timestamp()
        return {"node": node["node"], "num": node["num"], "short_name": node.get("short_name"),
                "long_name": node.get("long_name"), "hw_model": node.get("hw_model"), "role": node.get("role"),
                "last_heard": iso(datetime.fromtimestamp(last, timezone.utc)) if last else None,
                "rssi": heard.get("rssi"), "snr": heard.get("snr") if heard.get("snr") is not None else node.get("snr"),
                "hops_away": heard["hops"] if heard.get("hops") is not None else node.get("hops_away"),
                "battery_pct": node.get("battery_pct"), "pos": node.get("pos"),
                "is_gateway": node["node"] == own}

    def list_nodes(self) -> list[dict[str, Any]]:
        with self.lock:
            known = {n["node"]: n for n in self.radio.nodes()}
            for sender in self._heard:
                known.setdefault(sender, {"node": sender, "num": node_from_json(sender)})
            own = self.own_node
            rows = [self._node_json(n, own) for n in known.values()]
        rows.sort(key=lambda n: n["last_heard"] or "", reverse=True)
        return rows

    def node_position(self, node: str) -> dict[str, Any] | None:
        """Last known position of a node from the radio's node list (for connectors, if a message has no GPS)."""
        for known in self.radio.nodes():
            if known["node"] == node and known.get("pos"):
                return known["pos"]
        return None

    def geojson(self, since_seq: int = 0, types: list[str] | None = None) -> dict[str, Any]:
        records: list[dict[str, Any]] = []
        seq = since_seq
        while True:
            rows = self.store.list(seq, types, None, 1000)
            if not rows:
                break
            records += rows
            seq = rows[-1]["seq"]
        with self.lock:
            def state(key: str | None) -> str | None:
                task = self.board.get(key) if key else None
                return task.state if task and task.type else None
            return feature_collection(records, state)

    # ------------------------------------------------------------------ tools
    def encode_preview(self, message: Any) -> dict[str, Any]:
        if not isinstance(message, dict):
            raise ApiError(400, "bad_request", "message must be a JSON object", "message")
        try:
            payload = encode({k: v for k, v in message.items() if k != "v"})
            canonical = decode(payload)
        except CodecError as exc:
            raise codec_error(exc) from exc
        text = readable(canonical)
        text_bytes = len(text["en"].encode("utf-8"))
        preset = self.queue.preset
        return {"hex": payload.hex(), "size": len(payload), "message": canonical, "text": text, "text_size": text_bytes,
                "airtime_s": metric(round(message_airtime_seconds(len(payload), preset), 2), "s", "estimated",
                                    f"{preset}, {len(payload)} B payload + 23 B Meshtastic overhead"),
                "text_airtime_s": metric(round(message_airtime_seconds(text_bytes, preset), 2), "s", "estimated",
                                         "Same report as plain English text")}

    @staticmethod
    def decode_hex(value: Any) -> dict[str, Any]:
        if not isinstance(value, str):
            raise ApiError(400, "bad_request", "hex must be a string", "hex")
        try:
            data = bytes.fromhex(value)
        except ValueError:
            raise ApiError(400, "bad_request", "hex must be pairs of hexadecimal digits", "hex") from None
        try:
            message = decode_any(data)
        except CodecError as exc:
            raise codec_error(exc) from exc
        return {"v": message.get("v", 2), "size": len(data), "message": public_message(message), "text": readable(message)}

