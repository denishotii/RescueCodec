#!/usr/bin/env python3
"""Peer for the Android end-to-end test (android/app/src/androidTest, SimulatorE2ETest).

It listens on SIM-BASE (TCP 4404) like a command post would, and checks the app on the Android
emulator, which is connected to SIM-FIELD (4403):

 1. The app sends a Found report (sim/e2e/found.json). We must receive PRIVATE_APP (256) bytes that are
    identical to what the Python reference codec makes from the same JSON.
 2. We answer with an Ack "accepted" (ETA, team label), later "done". The app must mark the task.
 3. We send our own Found report. The test taps Accept in the app. We must receive an Ack with the
    right ref and node.

Exit code 0 means pass. Prints one line per step. The test side asserts task states.
"""
import argparse
import json
import sys
import threading
import time
from pathlib import Path

from meshtastic.tcp_interface import TCPInterface
from pubsub import pub

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from rescuecodec.v2.codec import decode, encode  # noqa: E402

PRIVATE_APP = 256
OWN_FOUND = {
    "id": 99,
    "pos": {"lat": 50.7, "lon": 7.2},
    "found": {"age": "adult", "consciousness": "confused", "needs": ["warmth"]},
}


def log(msg):
    print(f"[peer {time.strftime('%H:%M:%S')}] {msg}", flush=True)


class Peer:
    def __init__(self, port):
        self.lock = threading.Lock()
        self.packets = []  # (fromId, payload bytes)
        pub.subscribe(self.on_receive, "meshtastic.receive")
        self.iface = None
        for _ in range(30):
            try:
                self.iface = TCPInterface("127.0.0.1", portNumber=port)
                break
            except Exception:
                time.sleep(1)
        if self.iface is None:
            raise RuntimeError(f"cannot connect to :{port}")
        time.sleep(2)
        info = self.iface.getMyNodeInfo()
        self.my_num = info["num"]
        self.my_id = info["user"]["id"]
        log(f"connected to {info['user']['longName']} {self.my_id}")

    def on_receive(self, packet, interface):
        if interface is not self.iface:
            return
        dec = packet.get("decoded", {})
        if dec.get("portnum") not in ("PRIVATE_APP", PRIVATE_APP):
            return
        if packet.get("from") == self.my_num:
            return
        with self.lock:
            self.packets.append((packet.get("fromId") or "!%08x" % packet["from"], bytes(dec.get("payload", b""))))

    def wait_for(self, predicate, timeout):
        end = time.time() + timeout
        seen = 0
        while time.time() < end:
            with self.lock:
                batch = self.packets[seen:]
                seen = len(self.packets)
            for item in batch:
                if predicate(*item):
                    return item
            time.sleep(0.2)
        return None

    def send(self, message):
        self.iface.sendData(encode(message), destinationId="^all", portNum=PRIVATE_APP, wantAck=False, channelIndex=0)

    def close(self):
        try:
            self.iface.close()
        except Exception:
            pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=4404)
    ap.add_argument("--timeout", type=float, default=240)
    ap.add_argument("--ready-file", default="")
    args = ap.parse_args()

    report = json.loads((ROOT / "sim/e2e/found.json").read_text())
    expected = encode(report)
    peer = Peer(args.port)
    try:
        if args.ready_file:
            Path(args.ready_file).write_text("ready\n")
        log(f"waiting for the app's Found report, expecting {expected.hex()}")

        # Step 1: identical bytes.
        hit = peer.wait_for(lambda frm, payload: payload[:1] != b"" and decode_ok(payload), args.timeout)
        if hit is None:
            log("FAIL: no report from the app")
            return 1
        sender, payload = hit
        if payload != expected:
            log(f"FAIL: bytes differ\n  got      {payload.hex()}\n  expected {expected.hex()}")
            return 1
        log(f"step 1 ok: {len(payload)} identical bytes from {sender}")

        # Step 2: Accept, then Done. Repeats are sent on purpose: the app must ignore duplicates.
        accepted = {"ack": {"ref": report["id"], "node": sender, "status": "accepted", "eta": 15, "team": "Trupp 3"}}
        for _ in range(2):
            peer.send(accepted)
            time.sleep(1.5)
        log("step 2a: sent Ack accepted (twice)")
        time.sleep(6)
        peer.send({"ack": {"ref": report["id"], "node": sender, "status": "done"}})
        log("step 2b: sent Ack done")

        # Step 3: our own report, the app answers Accept.
        time.sleep(3)
        got = None
        for attempt in range(4):
            peer.send(OWN_FOUND)
            log(f"step 3: sent our Found id {OWN_FOUND['id']} (try {attempt + 1})")
            got = peer.wait_for(lambda frm, p: is_ack_for(p, OWN_FOUND["id"], peer.my_id), 25)
            if got:
                break
        if got is None:
            log("FAIL: no Accept from the app")
            return 1
        ack = decode(got[1])["ack"]
        if ack.get("status") != "accepted":
            log(f"FAIL: unexpected status {ack}")
            return 1
        log(f"step 3 ok: Ack {ack} from {got[0]}")
        log("E2E PEER PASS")
        return 0
    finally:
        peer.close()


def decode_ok(payload):
    try:
        return "found" in decode(payload)
    except Exception:
        return False


def is_ack_for(payload, ref, node):
    try:
        ack = decode(payload).get("ack")
    except Exception:
        return False
    return bool(ack) and ack.get("ref") == ref and ack.get("node") == node


if __name__ == "__main__":
    sys.exit(main())
