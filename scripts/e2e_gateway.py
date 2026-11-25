#!/usr/bin/env python3
"""End-to-end test of two gateways on the Meshtastic simulator (run scripts/e2e-gateway.sh, it starts everything).

  gateway A  --tcp 127.0.0.1:4403  (SIM-FIELD)   sends a Found report
  gateway B  --tcp 127.0.0.1:4404  (SIM-BASE)    receives it, then accepts and finishes it through its API
  A sees the task go open -> accepted -> done. MQTT (JSON) and TAK CoT (UDP multicast) outputs are observed too.

Only the simulator is used. No USB or Bluetooth device is opened. Exit code 0 = every run passed.
"""
from __future__ import annotations

import argparse
import json
import random
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COT_GROUP, COT_PORT = "239.2.3.1", 16969          # not the TAK default 6969, so a real TAK app on this machine stays quiet
FOUND = {"pos": {"lat": 50.69212, "lon": 7.21345},
         "found": {"age": "child", "consciousness": "conscious", "injuries": ["hypothermia", "fracture"],
                   "needs": ["stretcher"], "responders": 2}}


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def call(base: str, method: str, path: str, body: dict | None = None, timeout: float = 10):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(base + path, data=data, method=method, headers={"content-type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, json.loads(response.read() or b"null")
    except urllib.error.HTTPError as exc:
        return exc.code, json.loads(exc.read() or b"null")


def wait(condition, what: str, timeout: float = 60, interval: float = 0.25):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        value = condition()
        if value:
            return value
        time.sleep(interval)
    raise AssertionError(f"timed out after {timeout:.0f} s: {what}")


class Gateway:
    def __init__(self, name: str, tcp: str, workdir: Path, team: str):
        self.name, self.port = name, free_port()
        self.base = f"http://127.0.0.1:{self.port}"
        self.log = workdir / f"gateway-{name}.log"
        cmd = [sys.executable, "-m", "rescuecodec.gateway", "--tcp", tcp, "--port", str(self.port), "--db", str(workdir / f"{name}.db"),
               "--team", team, "--mqtt", "127.0.0.1:1883", "--cot", "--cot-udp", f"{COT_GROUP}:{COT_PORT}", "--cot-iface", "127.0.0.1"]
        self.proc = subprocess.Popen(cmd, cwd=ROOT, stdout=open(self.log, "w"), stderr=subprocess.STDOUT)

    def get(self, path: str):
        return call(self.base, "GET", path)

    def post(self, path: str, body: dict):
        return call(self.base, "POST", path, body)

    def stop(self) -> None:
        if self.proc.poll() is None:
            self.proc.send_signal(signal.SIGTERM)
            try:
                self.proc.wait(10)
            except subprocess.TimeoutExpired:
                self.proc.kill()


class MqttWatcher:
    def __init__(self):
        import paho.mqtt.client as mqtt
        self.seen: list[tuple[str, bytes]] = []
        self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
        self.client.on_connect = lambda c, u, f, rc, p=None: c.subscribe("rescuecodec/v2/#", qos=1)
        self.client.on_message = lambda c, u, m: self.seen.append((m.topic, bytes(m.payload)))
        self.client.connect("127.0.0.1", 1883)
        self.client.loop_start()

    def json(self, topic: str) -> list[dict]:
        return [json.loads(p) for t, p in list(self.seen) if t == topic]

    def stop(self) -> None:
        self.client.loop_stop()
        self.client.disconnect()


class StreamWatcher:
    """Listens to a gateway's WebSocket /v2/stream and keeps every event."""

    def __init__(self, base: str):
        from websockets.sync.client import connect
        self.events: list[dict] = []
        self.ws = connect(base.replace("http://", "ws://") + "/v2/stream")
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def _run(self) -> None:
        try:
            for frame in self.ws:
                self.events.append(json.loads(frame))
        except Exception:
            pass

    def stop(self) -> None:
        self.ws.close()


class CotWatcher:
    def __init__(self):
        self.events: list[ET.Element] = []
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        if hasattr(socket, "SO_REUSEPORT"):
            self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
        self.sock.bind(("", COT_PORT))
        self.sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, socket.inet_aton(COT_GROUP) + socket.inet_aton("127.0.0.1"))
        self.sock.settimeout(0.5)
        self._stop = False
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def _run(self) -> None:
        while not self._stop:
            try:
                data, _ = self.sock.recvfrom(65535)
                self.events.append(ET.fromstring(data))
            except (socket.timeout, ET.ParseError):
                continue
            except OSError:
                return

    def for_uid(self, uid: str) -> list[ET.Element]:
        return [e for e in list(self.events) if e.get("uid") == uid]

    def stop(self) -> None:
        self._stop = True
        self.thread.join(2)
        self.sock.close()


def one_run(index: int, field_tcp: str, base_tcp: str) -> dict:
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix="rc-e2e-") as tmp:
        workdir = Path(tmp)
        mqtt, cot, stream = MqttWatcher(), CotWatcher(), None
        a = Gateway("A", field_tcp, workdir, "Field 1")
        b = Gateway("B", base_tcp, workdir, "Base")
        try:
            for gateway in (a, b):
                health = wait(lambda: (lambda r: r[1] if r[0] == 200 and r[1]["radio"]["connected"] else None)(safe_get(gateway, "/v2/health")),
                              f"gateway {gateway.name} connected to its radio", 60)
                assert health["radio"]["kind"] == "tcp", health
            wait(lambda: a.get("/v2/health")[1]["mqtt"]["connected"] and b.get("/v2/health")[1]["mqtt"]["connected"], "MQTT connected")
            node_a, node_b = a.get("/v2/health")[1]["radio"]["node"], b.get("/v2/health")[1]["radio"]["node"]
            assert node_a != node_b
            stream = StreamWatcher(a.base)
            wait(lambda: stream.events, "WebSocket hello")
            assert stream.events[0]["event"] == "hello" and stream.events[0]["data"]["health"]["radio"]["node"] == node_a

            # A sends a Found report. The id is random per run, so a late retransmission from an earlier run cannot confuse us.
            report_id = random.randint(100, 16000)
            status, record = a.post("/v2/messages", {"message": {"id": report_id, **FOUND}})
            assert status == 202 and record["status"] == "queued", (status, record)
            key = f"{node_a[1:]}-{report_id}"
            assert record["task_key"] == key and record["codec"]["size"] in (23, 24), record      # 24 B: the id needs 2 bytes above 127
            wait(lambda: a.get(f"/v2/messages/{record['seq']}")[1]["status"] in ("sent", "delivered"), "A hands the packet to its radio", 30)

            # B receives it over the (simulated) mesh.
            task = wait(lambda: (lambda r: r[1] if r[0] == 200 else None)(b.get(f"/v2/tasks/{key}")), "B sees the task", 60)
            assert task["state"] == "open" and task["from"] == node_a and task["pos"] == FOUND["pos"], task
            assert task["text"]["en"].startswith("Person found: child, conscious, hypothermia, fracture, needs stretcher"), task["text"]
            received = next(r for r in b.get("/v2/messages?direction=in")[1]["items"] if r["key"] == key)
            assert received["from"] == node_a and received["codec"]["hex"] == record["codec"]["hex"], received
            radio = received["radio"]
            assert radio["via"] == "tcp" and {"rssi", "snr", "hops", "packet_id", "channel"} <= set(radio), radio
            assert a.get(f"/v2/tasks/{key}")[1]["state"] == "open"

            # B accepts through its API. The Ack goes back over the mesh to A.
            status, answer = b.post(f"/v2/tasks/{key}/ack", {"status": "accepted", "eta": 15, "team": "Trupp 3"})
            assert status == 202 and answer["task"]["state"] == "accepted" and answer["record"]["type"] == "ack", (status, answer)
            seen = wait(lambda: (lambda t: t if t["state"] == "accepted" else None)(a.get(f"/v2/tasks/{key}")[1]), "A sees accepted", 60)
            ack = seen["acks"][-1]
            assert (ack["by"], ack["status"], ack["eta"], ack["team"]) == (node_b, "accepted", 15, "Trupp 3"), seen

            # ... and finishes it.
            status, _ = b.post(f"/v2/tasks/{key}/ack", {"status": "done"})
            assert status == 202
            wait(lambda: a.get(f"/v2/tasks/{key}")[1]["state"] == "done", "A sees done", 60)

            # The WebSocket of A showed the same story live.
            wait(lambda: any(e["event"] == "task" and e["data"]["key"] == key and e["data"]["state"] == "done" for e in stream.events),
                 "WebSocket task done", 10)
            live = [e["data"]["state"] for e in stream.events if e["event"] == "task" and e["data"]["key"] == key]
            assert live[0] == "open" and "accepted" in live and live[-1] == "done", live
            assert any(e["event"] == "message_status" and e["data"]["seq"] == record["seq"] and e["data"]["status"] == "sent"
                       for e in stream.events)

            # MQTT: both gateways published the messages and the task states.
            wait(lambda: any(t["key"] == key and t["state"] == "done" for t in mqtt.json("rescuecodec/v2/tasks")), "MQTT task done", 15)
            found_json = [m for m in mqtt.json("rescuecodec/v2/found") if m["key"] == key]
            assert {m["direction"] for m in found_json} == {"in", "out"}, found_json
            states = [t["state"] for t in mqtt.json("rescuecodec/v2/tasks") if t["key"] == key]
            assert {"open", "accepted", "done"} <= set(states), states
            assert any(m["task_key"] == key for m in mqtt.json("rescuecodec/v2/ack")), "no ack on MQTT"
            readable = [p.decode() for t, p in mqtt.seen if t == "rescuecodec/v2/readable"]
            assert any(line.startswith("Person found: child") for line in readable), readable

            # TAK: a spot marker that is updated in place, then expired when the task is done.
            uid = f"RescueCodec.{key}"
            wait(lambda: any("[DONE" in (e.findtext("detail/remarks") or "") for e in cot.for_uid(uid)), "CoT marker for done", 15)
            marker = cot.for_uid(uid)
            assert all(e.get("type") == "b-m-p-s-m" for e in marker), [e.get("type") for e in marker]
            assert any("[OPEN]" in e.findtext("detail/remarks") for e in marker)
            assert any("[ACCEPTED by Trupp 3, ETA 15 min]" in e.findtext("detail/remarks") for e in marker)
            point = marker[0].find("point")
            assert (point.get("lat"), point.get("lon")) == ("50.69212", "7.21345"), point.attrib
            last = marker[-1]
            assert last.get("stale") == last.get("time"), "a done task must expire its marker"
            return {"run": index, "seconds": round(time.monotonic() - started, 1), "key": key, "mqtt_messages": len(mqtt.seen),
                    "cot_events": len(marker), "radio": radio}
        except BaseException:
            for gateway in (a, b):
                print(f"--- tail of gateway {gateway.name} log ---", file=sys.stderr)
                print("\n".join(gateway.log.read_text().splitlines()[-25:]), file=sys.stderr)
            raise
        finally:
            for gateway in (a, b):
                gateway.stop()
            mqtt.stop()
            cot.stop()
            if stream:
                stream.stop()


def safe_get(gateway: Gateway, path: str):
    try:
        return gateway.get(path)
    except OSError:
        return 0, None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--runs", type=int, default=3, help="how many times in a row (default 3)")
    parser.add_argument("--field", default="127.0.0.1:4403", help="SIM-FIELD (gateway A)")
    parser.add_argument("--base", default="127.0.0.1:4404", help="SIM-BASE (gateway B)")
    args = parser.parse_args()
    results = []
    for index in range(1, args.runs + 1):
        print(f"run {index}/{args.runs} ...", flush=True)
        try:
            result = one_run(index, args.field, args.base)
        except AssertionError as exc:
            print(f"FAIL run {index}: {exc}")
            return 1
        results.append(result)
        print(f"PASS run {index}: {json.dumps(result)}", flush=True)
        if index < args.runs:
            time.sleep(3)
    print(f"E2E PASS {len(results)}/{args.runs}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
