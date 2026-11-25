"""Shared helpers for the gateway tests: a gateway on the in-memory emulator, polling, and a fake Meshtastic interface."""
from __future__ import annotations

import time
from contextlib import contextmanager
from typing import Any

from fastapi.testclient import TestClient

from rescuecodec.gateway.app import create_app
from rescuecodec.gateway.config import GatewayConfig
from rescuecodec.gateway.radio import EmulatorRadio
from rescuecodec.gateway.service import GatewayService
from rescuecodec.gateway.store import Store
from rescuecodec.v2 import encode

PEER = "!0000e002"
FOUND = {"pos": {"lat": 50.69212, "lon": 7.21345},
         "found": {"age": "child", "consciousness": "conscious", "injuries": ["hypothermia", "fracture"],
                   "needs": ["stretcher"], "responders": 2}}


def wait_for(condition, timeout: float = 5.0, interval: float = 0.02):
    """Poll until `condition()` is truthy and return its value, or fail after `timeout` seconds."""
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        value = condition()
        if value:
            return value
        time.sleep(interval)
    raise AssertionError("timed out waiting for a condition")


class Gateway:
    def __init__(self, service: GatewayService, radio: EmulatorRadio, client: TestClient):
        self.service, self.radio, self.client = service, radio, client

    def hear(self, message: dict[str, Any], sender: str = PEER) -> None:
        """The radio hears a RescueCodec packet from `sender`."""
        self.radio.inject(sender, encode(message))

    def post(self, path: str, **body):
        return self.client.post(path, json=body)


@contextmanager
def gateway(db: str = ":memory:", static_dir=None, **config):
    cfg = GatewayConfig(emulator=True, db_path=db, tx_per_minute=config.pop("tx_per_minute", 0), **config)
    radio = EmulatorRadio()
    service = GatewayService(cfg, radio, Store(db))
    app = create_app(service, static_dir=static_dir, token=cfg.token, cors_origins=cfg.cors_origins)
    with TestClient(app) as client:
        yield Gateway(service, radio, client)
