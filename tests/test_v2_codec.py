"""Library behaviour beyond the vectors: helpers, priorities, time, robustness of the JSON side."""
import unittest
from datetime import datetime, timezone

from rescuecodec.v2 import (CodecError, canonical, decode, effective_priority, encode, message_type, render,
                            resolve_time)
from rescuecodec.v2.codes import DEFAULT_PRIORITY, MESHTASTIC_PRIORITY


class HelperTests(unittest.TestCase):
    def test_effective_priority(self):
        self.assertEqual(effective_priority({"id": 1, "mayday": {}}), "critical")
        self.assertEqual(effective_priority({"track": {"points": []}}), "routine")
        self.assertEqual(effective_priority({"priority": "routine", "id": 1, "found": {}}), "routine")
        for kind, prio in DEFAULT_PRIORITY.items():
            self.assertIn(prio, MESHTASTIC_PRIORITY, kind)

    def test_message_type(self):
        self.assertEqual(message_type({"id": 1, "found": {}}), "found")
        self.assertEqual(message_type(decode(bytes.fromhex("08077200"))), "unknown")
        with self.assertRaises(CodecError):
            message_type({"id": 1})

    def test_resolve_time(self):
        now = datetime(2026, 11, 25, 0, 5, tzinfo=timezone.utc)
        self.assertEqual(resolve_time("23:58", now), datetime(2026, 11, 24, 23, 58, tzinfo=timezone.utc))
        self.assertEqual(resolve_time("00:10", now), datetime(2026, 11, 25, 0, 10, tzinfo=timezone.utc))
        self.assertEqual(resolve_time("00:20", now), datetime(2026, 11, 24, 0, 20, tzinfo=timezone.utc))
        with self.assertRaises(CodecError):
            resolve_time("25:00", now)

    def test_canonical_sorts_flags_and_rounds(self):
        msg = {"id": 1, "pos": {"lat": 50.692124, "lon": 7.213456}, "found": {"needs": ["medic", "stretcher", "medic"], "count": 0}}
        self.assertEqual(canonical(msg), {"id": 1, "pos": {"lat": 50.69212, "lon": 7.21346},
                                          "found": {"needs": ["stretcher", "medic"]}})

    def test_rounding_is_half_away_from_zero(self):
        self.assertEqual(canonical({"id": 1, "pos": {"lat": 0.000005, "lon": -0.000005}, "found": {}})["pos"],
                         {"lat": 0.00001, "lon": -0.00001})

    def test_huge_numbers_give_codec_errors(self):
        for msg in ({"id": 1, "pos": {"lat": 1e10, "lon": 0}, "found": {}},
                    {"id": 2 ** 40, "found": {}},
                    {"id": 1, "pos": {"lat": float("nan"), "lon": 0}, "found": {}},
                    {"time": "10:00", "track": {"points": [{"lat": 0, "lon": 0, "s": 2 ** 40}]}},
                    {"id": 1, "found": {"injuries": "hypothermia"}},
                    {"id": True, "found": {}},
                    "not an object"):
            with self.assertRaises(CodecError, msg=str(msg)):
                encode(msg)

    def test_unknown_values_can_be_relayed(self):
        data = bytes.fromhex("4204" + "0803" + "1009")  # sector 3, status 9 from a newer version
        msg = decode(data)
        self.assertEqual(msg["sector"]["status"], "unknown_9")
        self.assertEqual(encode(msg), data)
        self.assertIn("9", render(msg, "en"))

    def test_error_carries_code_and_field(self):
        with self.assertRaises(CodecError) as ctx:
            encode({"id": 1, "pos": {"lat": 91, "lon": 0}, "found": {}})
        self.assertEqual((ctx.exception.code, ctx.exception.field), ("invalid_value", "pos.lat"))
        self.assertIsInstance(ctx.exception, ValueError)


if __name__ == "__main__":
    unittest.main()
