#!/usr/bin/env python3
"""Sends a few demo reports from SIM-BASE (TCP 4404) so the Android app on the emulator, which is
connected to SIM-FIELD (4403), has something to show: found persons, a hazard, a request.

    .venv/bin/python sim/demo_feed.py            # found, hazard, request, found
    .venv/bin/python sim/demo_feed.py --mayday   # also a Mayday
    .venv/bin/python sim/demo_feed.py --only mayday
    .venv/bin/python sim/demo_feed.py --answer   # wait for the app's report, answer Accept (Trupp 3, 15 min)

Simulator only. It never touches a real radio (it connects to 127.0.0.1).
"""
import argparse
import sys
import time
from pathlib import Path

from meshtastic.tcp_interface import TCPInterface
from pubsub import pub

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from rescuecodec.v2.codec import decode, encode  # noqa: E402

PRIVATE_APP = 256

DEMO = {
    "found1": {"id": 31, "pos": {"lat": 50.69212, "lon": 7.21345},
               "found": {"age": "child", "consciousness": "conscious", "mobility": "cannot_walk",
                         "injuries": ["hypothermia"], "needs": ["stretcher", "warmth"]}},
    "hazard": {"id": 32, "pos": {"lat": 50.6975, "lon": 7.2051},
               "hazard": {"kind": "rockfall", "severity": "high", "radius": 50}},
    "request": {"id": 33, "request": {"resource": "stretcher", "count": 2, "urgency": "soon"}},
    "found2": {"id": 34, "priority": "critical", "pos": {"lat": 50.7011, "lon": 7.1987},
               "found": {"age": "adult", "consciousness": "unconscious", "breathing": "difficult",
                         "injuries": ["head", "bleeding"], "needs": ["medic", "helicopter"]}},
    "mayday": {"id": 35, "pos": {"lat": 50.6931, "lon": 7.2203},
               "mayday": {"reason": "injured", "persons": 2}},
    "sector": {"sector": {"sector": 3, "status": "partial", "coverage": 60}},
}


def answer(iface, timeout):
    """Waits for a Found report from the app and answers Accept, like a team in the field would."""
    seen = []

    def on_receive(packet, interface):
        if interface is not iface or packet.get("from") == iface.getMyNodeInfo()["num"]:
            return
        payload = bytes(packet.get("decoded", {}).get("payload", b""))
        try:
            msg = decode(payload)
        except Exception:
            return
        if "found" in msg and msg.get("id"):
            seen.append((packet.get("fromId") or "!%08x" % packet["from"], msg["id"]))

    pub.subscribe(on_receive, "meshtastic.receive")
    end = time.time() + timeout
    while time.time() < end and not seen:
        time.sleep(0.3)
    if not seen:
        print("no report from the app", flush=True)
        return 1
    sender, ref = seen[0]
    ack = {"ack": {"ref": ref, "node": sender, "status": "accepted", "eta": 15, "team": "Trupp 3"}}
    time.sleep(1)
    iface.sendData(encode(ack), destinationId="^all", portNum=PRIVATE_APP, wantAck=False, channelIndex=0)
    print(f"answered Accept for {sender} #{ref}", flush=True)
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=4404)
    ap.add_argument("--mayday", action="store_true", help="add a Mayday at the end")
    ap.add_argument("--only", help="send one message by name: " + ", ".join(DEMO))
    ap.add_argument("--answer", action="store_true", help="wait for the app's Found report and answer Accept")
    ap.add_argument("--wait", type=float, default=40, help="seconds to wait with --answer")
    ap.add_argument("--gap", type=float, default=3.0, help="seconds between messages")
    args = ap.parse_args()
    names = [args.only] if args.only else ["found1", "hazard", "request", "found2", "sector"] + (["mayday"] if args.mayday else [])
    iface = TCPInterface("127.0.0.1", portNumber=args.port)
    time.sleep(2)
    if args.answer:
        try:
            return answer(iface, args.wait)
        finally:
            iface.close()
    try:
        for n in names:
            data = encode(DEMO[n])
            iface.sendData(data, destinationId="^all", portNum=PRIVATE_APP, wantAck=False, channelIndex=0)
            print(f"sent {n}: {len(data)} bytes", flush=True)
            time.sleep(args.gap)
    finally:
        iface.close()


if __name__ == "__main__":
    sys.exit(main() or 0)
