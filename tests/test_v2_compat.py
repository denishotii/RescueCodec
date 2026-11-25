"""v1 compatibility and the version detection rule (first byte)."""
import itertools
import unittest

from rescuecodec.protocol.codec import confirmation_packet, encode_report, encode_text
from rescuecodec.schemas import Assistance, Breathing, Consciousness, Equipment, PersonFoundReport, Urgency
from rescuecodec.v2 import CodecError, decode, decode_any, detect_version, encode, render
from rescuecodec.v2 import rescuecodec_pb2 as pb


class DetectionTests(unittest.TestCase):
    def test_rule(self):
        self.assertEqual(detect_version(b""), 0)
        self.assertEqual(detect_version(b"\x01\x01\x06"), 1)
        for first in (0x00, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07):
            self.assertEqual(detect_version(bytes([first, 0x08])), 0, hex(first))
        for first in range(0x08, 0x100):
            self.assertEqual(detect_version(bytes([first])), 2, hex(first))

    def test_v2_never_starts_with_reserved_byte(self):
        """Field numbers start at 1, so a protobuf tag byte is always >= 0x08."""
        samples = [
            {"id": 1, "found": {}}, {"found": {}, "id": 16383}, {"track": {"points": [{"lat": 0, "lon": 0}]}},
            {"sector": {"sector": 1, "status": "cleared"}}, {"id": 1, "request": {"resource": "stretcher"}},
            {"id": 1, "hazard": {"kind": "fire"}}, {"id": 1, "mayday": {}},
            {"ack": {"ref": 1, "node": "!00000001", "status": "done"}}, {"text": {"text": "\x01"}},
            {"time": "00:00", "track": {"points": [{"lat": 0, "lon": 0, "s": 0}]}},
            {"priority": "routine", "text": {"text": "x"}}, {"pos": {"lat": 0, "lon": 0}, "text": {"text": "x"}},
        ]
        for msg in samples:
            data = encode(msg)
            self.assertGreaterEqual(data[0], 0x08, msg)
            self.assertEqual(detect_version(data), 2)

    def test_every_envelope_field_tag_is_at_least_0x08(self):
        for field in pb.Envelope.DESCRIPTOR.fields:
            for wire_type in range(6):
                self.assertGreaterEqual((field.number << 3) | wire_type, 0x08)

    def test_v1_packets_are_never_read_as_v2(self):
        data = encode_report(PersonFoundReport(person_count=1), 7)
        with self.assertRaises(CodecError) as ctx:
            decode(data)
        self.assertEqual(ctx.exception.code, "unsupported_version")
        self.assertEqual(decode_any(data)["v"], 1)


class V1DecodeTests(unittest.TestCase):
    def test_pitch_report(self):
        report = PersonFoundReport(consciousness=Consciousness.UNCONSCIOUS, equipment_needed=[Equipment.OXYGEN_MASK],
                                   responders_needed_min=2)
        data = encode_report(report, 1)
        self.assertEqual(len(data), 12)
        msg = decode_any(data)
        self.assertEqual(msg, {"v": 1, "id": 1, "found": {"consciousness": "unconscious", "needs": ["oxygen"],
                                                          "responders": 2}})
        # The same facts in v2 are 11 bytes.
        v2 = encode({k: v for k, v in msg.items() if k != "v"})
        self.assertEqual(len(v2), 11)

    def test_full_report_maps_every_field(self):
        report = PersonFoundReport(consciousness=Consciousness.CONSCIOUS, breathing=Breathing.NO,
                                   equipment_needed=[Equipment.STRETCHER, Equipment.WATER],
                                   request=Assistance.EXTRACTION, urgency=Urgency.URGENT,
                                   location=(50.69212, 7.21345), location_quality=2, person_count=3,
                                   responders_needed_min=4)
        msg = decode_any(encode_report(report, 300, 1764081120))
        self.assertEqual(msg["priority"], "urgent")
        self.assertEqual(msg["pos"], {"lat": 50.69212, "lon": 7.21345, "acc": "estimated"})
        self.assertEqual(msg["time"], "14:32")
        self.assertEqual(msg["found"], {"count": 3, "consciousness": "conscious", "breathing": "none",
                                        "needs": ["stretcher", "water", "rope"], "responders": 4})
        self.assertIn("3 persons found", render(msg, "en"))
        self.assertIn("3 Personen gefunden", render(msg, "de"))

    def test_text_and_confirmations(self):
        self.assertEqual(decode_any(encode_text("Hallo", 9)), {"v": 1, "id": 9, "text": {"text": "Hallo"}})
        self.assertEqual(decode_any(confirmation_packet(5, 1))["ack"], {"ref": 5, "status": "accepted"})
        self.assertEqual(decode_any(confirmation_packet(5, 2))["ack"], {"ref": 5, "status": "done"})

    def test_broken_v1_is_rejected_cleanly(self):
        for data in (b"\x01", b"\x01\x01\x06", b"\x01\x07\x00\x00", b"\x01\x09\x09\x00\x00\x00\x00"):
            with self.assertRaises(CodecError):
                decode_any(data)

    def test_v2_through_decode_any(self):
        msg = decode_any(encode({"id": 3, "mayday": {}}))
        self.assertEqual(msg, {"v": 2, "id": 3, "mayday": {}})


class RobustnessTests(unittest.TestCase):
    def test_short_random_payloads_never_crash(self):
        """Port 256 is shared with other private apps: junk must give CodecError, never another exception."""
        for n in (1, 2):
            for combo in itertools.product(range(256), repeat=n):
                data = bytes(combo)
                try:
                    decode_any(data)
                except CodecError:
                    pass

    def test_mutated_vectors_never_crash(self):
        base = encode({"id": 7, "pos": {"lat": 50.69212, "lon": 7.21345}, "time": "10:00",
                       "found": {"age": "child", "needs": ["stretcher"], "responders": 2}})
        for i in range(len(base)):
            for value in (0x00, 0x01, 0x07, 0x08, 0x7F, 0x80, 0xFF):
                data = base[:i] + bytes([value]) + base[i + 1:]
                for cut in (data, data[:i]):
                    try:
                        decode_any(cut)
                    except CodecError:
                        pass


if __name__ == "__main__":
    unittest.main()
