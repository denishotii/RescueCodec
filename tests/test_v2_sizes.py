"""Size limits from spec/SPEC.md ("Sizes")."""
import json
import unittest
from pathlib import Path

from rescuecodec.v2 import encode
from rescuecodec.v2.codes import MAX_ENCODE_BYTES

ROOT = Path(__file__).resolve().parents[1]
VECTORS = {c["name"]: c for c in json.loads((ROOT / "spec" / "vectors" / "codec.json").read_text())["valid"]}
POS = {"lat": 50.69212, "lon": 7.21345}


class SizeTests(unittest.TestCase):
    def test_typical_found_with_gps_is_at_most_25_bytes(self):
        self.assertLessEqual(VECTORS["found-typical-gps"]["size"], 25)

    def test_found_with_gps_and_time_and_large_id(self):
        msg = {"id": 16383, "pos": POS, "time": "23:59",
               "found": {"age": "adult", "consciousness": "unconscious", "breathing": "normal",
                         "injuries": ["hypothermia", "head"], "needs": ["stretcher", "medic"], "responders": 4}}
        self.assertLessEqual(len(encode(msg)), 30)

    def test_pitch_sentence_matches_v1(self):
        # v1 measured 12 bytes for "unconscious, oxygen mask, 2 helpers".
        self.assertLessEqual(VECTORS["found-pitch"]["size"], 12)

    def test_every_non_text_non_track_type_with_gps_fits_25_bytes(self):
        for name in ("request-searchers", "request-dog-team", "hazard-rockfall", "hazard-hunting", "mayday-sos",
                     "mayday-injured", "sector-partial", "ack-accepted", "found-senior-walks", "found-group"):
            with self.subTest(name):
                self.assertLessEqual(VECTORS[name]["size"], 25)

    def test_ack_is_small(self):
        self.assertLessEqual(VECTORS["ack-done"]["size"], 12)

    def test_track_batching(self):
        ten = VECTORS["track-10"]["size"]
        self.assertLessEqual(ten, 60)
        # One point costs about 13 bytes alone; ten points in one packet are far below 10 x 13.
        self.assertLess(ten, 10 * VECTORS["track-1"]["size"] / 2)
        self.assertLessEqual(VECTORS["track-16-max"]["size"], 80)

    def test_nothing_valid_exceeds_the_limit(self):
        for name, case in VECTORS.items():
            with self.subTest(name):
                self.assertLessEqual(case["size"], MAX_ENCODE_BYTES)


if __name__ == "__main__":
    unittest.main()
