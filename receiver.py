import time

from meshtastic.serial_interface import SerialInterface
from pubsub import pub

PORT = "/dev/cu.usbserial-0001"


def on_receive(packet, interface):
    decoded = packet.get("decoded", {})
    payload = decoded.get("payload", b"")

    print("\n" + "=" * 40)
    print("INCOMING PACKET")
    print("=" * 40)

    print("Sender:", packet.get("fromId"))
    print("Receiver:", packet.get("toId"))
    print("Packet ID:", packet.get("id"))
    print("Port:", decoded.get("portnum"))
    print("RSSI:", packet.get("rxRssi"))
    print("SNR:", packet.get("rxSnr"))
    print("Channel:", packet.get("channel"))

    if isinstance(payload, bytes):
        print("Payload size:", len(payload), "bytes")
        print("HEX:", payload.hex())

    if "text" in decoded:
        print("Text:", decoded["text"])


pub.subscribe(on_receive, "meshtastic.receive")

radio = SerialInterface(devPath=PORT)

print("RescueCodec receiver started")
print("Listening for incoming packets...")

try:
    while True:
        time.sleep(1)

except KeyboardInterrupt:
    print("\nStopping receiver...")

finally:
    radio.close()