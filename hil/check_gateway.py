#!/usr/bin/env python3
"""Check a RUNNING gateway against real hardware. Stdlib only. It never opens a serial port or Bluetooth itself:
start the gateway first (`rescuecodec-gateway --serial auto`), then run this.

    python3 hil/check_gateway.py                  # step 1: health and channel only, transmits nothing
    python3 hil/check_gateway.py --send           # also send one test Found and watch its status (transmits once)
    python3 hil/check_gateway.py --wait-in 60     # wait up to 60 s for a report from a phone, then Accept and Done it
    python3 hil/check_gateway.py --token SECRET   # when the gateway runs with --token

Exit code 0 only if every step that ran passed. Each line starts with PASS, FAIL or WARN.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request

FOUND = {"pos": {"lat": 50.94, "lon": 6.98}, "found": {"age": "adult", "consciousness": "conscious", "needs": ["warmth"]}}


class Gateway:
    def __init__(self, base: str, token: str | None):
        self.base, self.token = base.rstrip("/"), token

    def call(self, method: str, path: str, body: dict | None = None):
        request = urllib.request.Request(self.base + path, method=method, data=json.dumps(body).encode() if body is not None else None)
        request.add_header("Content-Type", "application/json")
        if self.token:
            request.add_header("Authorization", f"Bearer {self.token}")
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as exc:
            try:
                return exc.code, json.load(exc)
            except ValueError:
                return exc.code, {}
        except OSError as exc:
            return 0, {"error": {"message": str(exc)}}


results: list[bool] = []


def report(ok: bool | None, text: str) -> None:
    label = "WARN" if ok is None else "PASS" if ok else "FAIL"
    if ok is not None:
        results.append(ok)
    print(f"{label}  {text}")


def step_health(gw: Gateway, want_kind: str | None) -> dict | None:
    status, health = gw.call("GET", "/v2/health")
    if status != 200:
        report(False, f"gateway not reachable at {gw.base} ({health.get('error', {}).get('message', status)})")
        return None
    radio = health["radio"]
    report(radio["connected"], f"radio connected (kind {radio['kind']}, node {radio['node']}, firmware {radio.get('firmware')})")
    if want_kind:
        report(radio["kind"] == want_kind, f"radio kind is {want_kind}")
    fw = radio.get("firmware") or ""
    report(True if fw.startswith(("2.7", "2.8")) else None, f"firmware {fw or '?'} (stock 2.7 or 2.8 expected)")
    report(radio.get("channel_error") is None and radio.get("channel_index") is not None,
           f"private channel found: index {radio.get('channel_index')} name {radio.get('channel')!r}"
           + (f" PROBLEM: {radio.get('channel_error')}" if radio.get("channel_error") else ""))
    report(radio.get("region") == "EU_868", f"region {radio.get('region')} (EU_868 expected), preset {radio.get('preset')}")
    other = radio.get("other_channel", 0)
    report(None if other else True, f"port 256 packets from other channels dropped so far: {other}")
    return health


def step_send(gw: Gateway) -> None:
    status, record = gw.call("POST", "/v2/messages", {"message": FOUND})
    if status != 202:
        report(False, f"send refused: {status} {record.get('error', {}).get('message')}")
        return
    seq = record["seq"]
    report(True, f"queued seq {seq}, {record['codec']['size']} bytes: {record['codec']['hex']}")
    end = time.monotonic() + 40
    seen = record["status"]
    while time.monotonic() < end and seen in ("queued", "sent"):
        time.sleep(1)
        status, record = gw.call("GET", f"/v2/messages/{seq}")
        seen = record.get("status", seen)
    report(seen in ("sent", "delivered", "failed"), f"final status {seen} ({record.get('error')})")
    if seen == "delivered":
        report(True, "a neighbouring radio repeated it (this is not proof that a team received it)")
    else:
        report(None, "no neighbour repeated it. Normal with one radio alone. The Accept from the phone is the real proof.")


def step_roundtrip(gw: Gateway, wait_s: int) -> None:
    print(f"... waiting up to {wait_s} s for a report from a phone (send a Found from the app)")
    end = time.monotonic() + wait_s
    seen: set[str] = {t["key"] for t in gw.call("GET", "/v2/tasks")[1].get("items", [])}
    task = None
    while time.monotonic() < end and task is None:
        time.sleep(1)
        for item in gw.call("GET", "/v2/tasks")[1].get("items", []):
            if item["key"] not in seen and item["state"] == "open":
                task = item
    if task is None:
        report(False, "no new report arrived")
        return
    status, records = gw.call("GET", "/v2/messages?direction=in&limit=1000")
    record = next((r for r in reversed(records.get("items", [])) if r.get("task_key") == task["key"]), {})
    radio = record.get("radio") or {}
    report(True, f"received {task['key']}: {task['text']['en']}")
    report(True, f"bytes on air {record.get('codec', {}).get('size')} hex {record.get('codec', {}).get('hex')} rssi {radio.get('rssi')} snr {radio.get('snr')} hops {radio.get('hops')}")
    for state in ("accepted", "done"):
        status, answer = gw.call("POST", f"/v2/tasks/{task['key']}/ack", {"status": state, "team": "Base", **({"eta": 10} if state == "accepted" else {})})
        report(status == 202, f"{state} sent ({status} {answer.get('error', {}).get('message', '')}). Check that the phone shows it within about 10 s")
        time.sleep(4)


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--url", default="http://127.0.0.1:8000")
    p.add_argument("--token")
    p.add_argument("--kind", choices=["serial", "tcp"], help="expected radio kind")
    p.add_argument("--send", action="store_true", help="transmit one test Found on the radio")
    p.add_argument("--wait-in", type=int, metavar="SECONDS", help="wait for a report from a phone, then Accept and Done it (transmits two Acks)")
    args = p.parse_args()
    gw = Gateway(args.url, args.token)
    if step_health(gw, args.kind) is None:
        return 1
    if args.send:
        step_send(gw)
    if args.wait_in:
        step_roundtrip(gw, args.wait_in)
    print("ALL PASSED" if all(results) else "SOMETHING FAILED")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
