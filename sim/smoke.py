#!/usr/bin/env python3
"""Smoke test: SIM-FIELD broadcasts a PRIVATE_APP (256) packet and a text message
on the RescueCodec channel. SIM-BASE and SIM-RELAY must receive identical bytes.

Uses the same Python TCPInterface the gateway uses. Exit code 0 means pass.
"""
import os
import sys
import threading
import time

from pubsub import pub
from meshtastic.tcp_interface import TCPInterface

PRIVATE_APP = 256
TIMEOUT = float(os.environ.get("SMOKE_TIMEOUT", "90"))
RESEND_EVERY = 8.0

# Known bytes (looks like our v2 header 0x02 + payload) with a per-run tail so
# a stale packet from an earlier run can never satisfy this run.
PAYLOAD = bytes([0x02, 0x0A, 0x04, 0xDE, 0xAD, 0xBE, 0xEF]) + os.urandom(4)
TEXT = f"rc-smoke {PAYLOAD.hex()}"

got = {"BASE": {"data": False, "text": False}, "RELAY": {"data": False, "text": False}}
lock = threading.Lock()
ifaces = {}


def on_receive(packet, interface):
    who = next((k for k, v in ifaces.items() if v is interface), None)
    if who not in got:
        return
    dec = packet.get("decoded", {})
    port = dec.get("portnum")
    with lock:
        if port in ("PRIVATE_APP", PRIVATE_APP) and dec.get("payload") == PAYLOAD:
            got[who]["data"] = True
        elif port == "TEXT_MESSAGE_APP" and dec.get("text") == TEXT:
            got[who]["text"] = True


def connect(port):
    for _ in range(30):
        try:
            return TCPInterface("127.0.0.1", portNumber=port)
        except Exception:
            time.sleep(1)
    raise RuntimeError(f"cannot connect to :{port}")


def main():
    pub.subscribe(on_receive, "meshtastic.receive")
    ifaces["FIELD"] = connect(4403)
    ifaces["BASE"] = connect(4404)
    ifaces["RELAY"] = connect(4405)
    time.sleep(2)
    names = {k: v.getMyNodeInfo()["user"]["longName"] for k, v in ifaces.items()}
    assert names == {"FIELD": "SIM-FIELD", "BASE": "SIM-BASE", "RELAY": "SIM-RELAY"}, names

    start = time.time()
    last = 0.0
    sends = 0
    while time.time() - start < TIMEOUT:
        if time.time() - last >= RESEND_EVERY:
            ifaces["FIELD"].sendData(
                PAYLOAD, destinationId="^all", portNum=PRIVATE_APP,
                wantAck=False, channelIndex=0,
            )
            ifaces["FIELD"].sendText(TEXT, destinationId="^all", channelIndex=0)
            sends += 1
            last = time.time()
        with lock:
            done = all(all(v.values()) for v in got.values())
        if done:
            break
        time.sleep(0.25)

    for i in ifaces.values():
        try:
            i.close()
        except Exception:
            pass
    ok = all(all(v.values()) for v in got.values())
    print(f"payload={PAYLOAD.hex()} sends={sends} elapsed={time.time() - start:.1f}s")
    for who, v in got.items():
        print(f"  {who}: PRIVATE_APP bytes identical={v['data']} text={v['text']}")
    print("SMOKE PASS" if ok else "SMOKE FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
