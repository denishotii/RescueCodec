"""Hands gateway events to connectors on a worker thread, so a slow broker or TAK server never blocks the radio."""
from __future__ import annotations

import logging
import queue
import threading
from collections.abc import Callable
from typing import Any

from rescuecodec.gateway.events import EventBus

log = logging.getLogger("rescuecodec.gateway.hub")

Handler = Callable[[str, Any], None]


class ConnectorHub:
    EVENTS = ("message", "task")

    def __init__(self, bus: EventBus, handlers: list[Handler] | None = None):
        self.handlers: list[Handler] = list(handlers or [])
        self._starters: list[Callable[[], None]] = []
        self._stoppers: list[Callable[[], None]] = []
        self._queue: queue.Queue[tuple[str, Any] | None] = queue.Queue(maxsize=5000)
        self._thread = threading.Thread(target=self._run, name="connector-hub", daemon=True)
        self._unsubscribe = bus.subscribe(self._on_event)
        self._started = False

    def add(self, handler: Handler, *, start: Callable[[], None] | None = None, stop: Callable[[], None] | None = None) -> None:
        """Add a connector. `start` and `stop` run when the gateway starts and stops."""
        self.handlers.append(handler)
        if start:
            self._starters.append(start)
        if stop:
            self._stoppers.append(stop)

    def start(self) -> None:
        if not self._started:
            self._started = True
            for starter in self._starters:
                starter()
            self._thread.start()

    def stop(self) -> None:
        self._unsubscribe()
        if self._started:
            self._queue.put(None)
            self._thread.join(timeout=3)
            for stopper in self._stoppers:
                try:
                    stopper()
                except Exception:
                    log.exception("connector did not stop cleanly")

    def _on_event(self, event: str, data: Any) -> None:
        if event in self.EVENTS and self.handlers:
            try:
                self._queue.put_nowait((event, data))
            except queue.Full:
                log.warning("connector queue is full, dropping %s", event)

    def _run(self) -> None:
        while True:
            item = self._queue.get()
            if item is None:
                return
            for handler in self.handlers:
                try:
                    handler(*item)
                except Exception:
                    log.exception("connector failed")
