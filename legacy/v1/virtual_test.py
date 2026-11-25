
import time
import threading

from meshtastic.tcp_interface import TCPInterface
from meshtastic.protobuf import portnums_pb2
from pubsub import pub

received = threading.Event()
expected = bytes([1, 6, 1, 2, 3, 1])

node_a = TCPInterface(
    hostname="127.0.0.1",
    portNumber=4404
)

node_b = TCPInterface(
    hostname="127.0.0.1",
    portNumber=4405
)

id_a = node_a.getMyNodeInfo()["user"]["id"]
id_b = node_b.getMyNodeInfo()["user"]["id"]


def on_receive(packet, interface):
    decoded = packet.get("decoded", {})
    payload = decoded.get("payload", b"")

    if decoded.get("portnum") != "PRIVATE_APP":
        return

    print("\n--- PACKET RECEIVED ---")
    print("Sender:", packet.get("fromId"))
    print("Receiver:", packet.get("toId"))
    print("Payload:", payload.hex())
    print("Size:", len(payload), "bytes")

    if (
        interface is node_b
        and payload == expected
        and packet.get("fromId") == id_a
    ):
        received.set()


pub.subscribe(on_receive, "meshtastic.receive")

try:
    print("Node A:", id_a)
    print("Node B:", id_b)

    if id_a == id_b:
        raise RuntimeError("Both endpoints have the same node ID")

    print("\n--- TRANSMITTING ---")
    print("Payload:", expected.hex())
    print("Size:", len(expected), "bytes")

    start = time.perf_counter()

    node_a.sendData(
        expected,
        destinationId=id_b,
        portNum=portnums_pb2.PortNum.PRIVATE_APP,
        wantAck=True
    )

    if received.wait(timeout=30):
        elapsed = time.perf_counter() - start

        print("\nSUCCESS: Binary payload delivered")
        print(f"Delivery time: {elapsed:.3f} seconds")
    else:
        print("\nTIMEOUT: Delivery not confirmed")

finally:
    pub.unsubscribe(on_receive, "meshtastic.receive")
    node_a.close()
    node_b.close()
