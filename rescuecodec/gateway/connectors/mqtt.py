"""MQTT connector (JSON, like Meshtastic's own MQTT). Needs `paho-mqtt` (pip install 'rescuecodec[mqtt]').

Topics (prefix `rescuecodec/v2` by default):
  <prefix>/messages     every MessageRecord, received and sent
  <prefix>/<type>       the same record on a per-type topic: found, track, sector, request, hazard, mayday, ack, text
  <prefix>/channels/<name>  the same record on a per-channel topic, for example rescuecodec/v2/channels/draeger-srs
  <prefix>/tasks        a Task object whenever a task is created or changes
  <prefix>/readable     the plain English line of each message (for dashboards and chat bots)
  <prefix>/status       "online" or "offline" (retained, last will)
Payloads are the JSON of spec/api/openapi.yaml. QoS 1, not retained (except status).
"""
from __future__ import annotations

import json
import logging
import threading
from collections.abc import Callable
from typing import Any

log = logging.getLogger("rescuecodec.gateway.mqtt")


def topic_part(name: str) -> str:
    """A channel name as one MQTT topic level: no '/', '+', '#' or spaces."""
    return "".join("_" if c in "/+# " else c for c in name) or "_"


class MqttConnector:
    def __init__(self, host: str, port: int = 1883, *, prefix: str = "rescuecodec/v2", username: str | None = None,
                 password: str | None = None, client_id: str | None = None, client_factory: Callable[..., Any] | None = None,
                 on_state: Callable[[bool], None] | None = None):
        self.host, self.port, self.prefix = host, port, prefix.rstrip("/")
        self.connected = False
        self._on_state = on_state or (lambda connected: None)
        self._lock = threading.Lock()
        if client_factory is None:
            import paho.mqtt.client as mqtt
            client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=client_id or "")
        else:
            client = client_factory()
        self.client = client
        if username:
            client.username_pw_set(username, password)
        client.will_set(f"{self.prefix}/status", "offline", qos=1, retain=True)
        client.on_connect = self._on_connect
        client.on_disconnect = self._on_disconnect
        client.reconnect_delay_set(min_delay=1, max_delay=30)
        if hasattr(client, "max_queued_messages_set"):
            client.max_queued_messages_set(2000)

    def start(self) -> None:
        try:
            self.client.connect_async(self.host, self.port, keepalive=30)
            self.client.loop_start()
        except Exception:
            log.exception("MQTT start failed")

    def stop(self) -> None:
        try:
            if self.connected:
                self.client.publish(f"{self.prefix}/status", "offline", qos=1, retain=True)
            self.client.loop_stop()
            self.client.disconnect()
        except Exception:
            log.debug("MQTT stop failed", exc_info=True)
        self._set_connected(False)

    # paho 2 callbacks (VERSION2 signatures)
    def _on_connect(self, client, userdata, flags, reason_code, properties=None) -> None:
        failed = getattr(reason_code, "is_failure", False)
        if failed:
            log.warning("MQTT connect refused: %s", reason_code)
            return
        log.info("MQTT connected to %s:%s", self.host, self.port)
        client.publish(f"{self.prefix}/status", "online", qos=1, retain=True)
        self._set_connected(True)

    def _on_disconnect(self, client, userdata, *args) -> None:
        log.warning("MQTT disconnected")
        self._set_connected(False)

    def _set_connected(self, value: bool) -> None:
        with self._lock:
            changed = self.connected != value
            self.connected = value
        if changed:
            self._on_state(value)

    def publish(self, topic: str, payload: Any, *, retain: bool = False) -> None:
        body = payload if isinstance(payload, str) else json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
        self.client.publish(f"{self.prefix}/{topic}", body.encode("utf-8"), qos=1, retain=retain)

    def handle(self, event: str, data: Any) -> None:
        """Gateway event listener."""
        if event == "message":
            self.publish("messages", data)
            self.publish(data["type"], data)
            name = (data.get("channel") or {}).get("name")
            if name:
                self.publish(f"channels/{topic_part(name)}", data)
            self.publish("readable", data["text"]["en"])
        elif event == "task":
            if data.get("type"):
                self.publish("tasks", data)
