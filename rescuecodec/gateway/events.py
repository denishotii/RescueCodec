"""A tiny thread-safe event bus. Events are `(name, data)`; data is plain JSON-able data."""
from __future__ import annotations

import logging
import threading
from collections.abc import Callable
from typing import Any

log = logging.getLogger("rescuecodec.gateway.events")

Listener = Callable[[str, Any], None]


class EventBus:
    def __init__(self) -> None:
        self._listeners: list[Listener] = []
        self._lock = threading.Lock()

    def subscribe(self, listener: Listener) -> Callable[[], None]:
        """Add a listener. Returns a function that removes it again."""
        with self._lock:
            self._listeners.append(listener)

        def unsubscribe() -> None:
            with self._lock:
                if listener in self._listeners:
                    self._listeners.remove(listener)
        return unsubscribe

    def publish(self, event: str, data: Any) -> None:
        with self._lock:
            listeners = list(self._listeners)
        for listener in listeners:
            try:
                listener(event, data)
            except Exception:  # a broken listener must never stop the radio thread
                log.exception("event listener failed for %s", event)
