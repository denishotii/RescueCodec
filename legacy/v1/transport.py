
import time
from meshtastic.serial_interface import SerialInterface
from meshtastic.protobuf import portnums_pb2
from pubsub import pub

PORT = "/dev/cu.usbserial-0001"


def on_receive(packet, interface):
    decoded = packet.get("decoded", {})
    port = decoded.get("portnum")

    if port not in ("TEXT_MESSAGE_APP", "PRIVATE_APP"):
        return

    payload = decoded.get("payload", b"")

    print("\n--- RECEIVED ---")
    print("Sender:", packet.get("fromId"))
    print("Port:", port)
    print("Bytes:", len(payload))
    print("HEX:", payload.hex())
    print("Text:", decoded.get("text"))


pub.subscribe(on_receive, "meshtastic.receive")

radio = SerialInterface(devPath=PORT)

print("Connected:", radio.getMyNodeInfo()["user"]["id"])

try:
    while True:
        command = input("\nCommand (test/nodes/exit): ")

        if command == "exit":
            break

        elif command == "nodes":
            for node_id, node in radio.nodes.items():
                print(
                    node_id,
                    node.get("user", {}).get("longName"),
                    node.get("lastHeard")
                )

        elif command == "test":
            payload = bytes([1, 6, 1, 2, 3, 1])

            print("Protocol version:", payload[0])
            print("Schema ID:", payload[1])
            print("Payload:", payload.hex())
            print("Size:", len(payload), "bytes")
            print("Local encoding successful")

        else:
            print("Unknown command")

finally:
    radio.close()
