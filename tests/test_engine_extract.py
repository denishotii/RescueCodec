"""Rule-based extractor: spec/vectors/extraction.json (conformance Level 3) and behaviour checks."""
import json
import unittest
from pathlib import Path

from rescuecodec.engine import extract
from rescuecodec.v2 import encode

VECTORS = json.loads((Path(__file__).resolve().parents[1] / "spec" / "vectors" / "extraction.json").read_text(encoding="utf-8"))


class ExtractionVectors(unittest.TestCase):
    def test_all_vectors(self):
        failed = []
        for case in VECTORS["cases"]:
            with self.subTest(case["name"]):
                r = extract(case["text"], case["lang"])
                if r.message != case["expect"] or r.unsupported != case["unsupported"]:
                    failed.append(case["name"])
                self.assertEqual(r.message, case["expect"])
                self.assertEqual(r.unsupported, case["unsupported"])
        print(f"\nextraction vectors: {len(VECTORS['cases']) - len(failed)}/{len(VECTORS['cases'])} pass")

    def test_language_is_detected_without_hint(self):
        for case in VECTORS["cases"]:
            with self.subTest(case["name"]):
                self.assertEqual(extract(case["text"]).message, case["expect"])

    def test_every_supported_result_encodes(self):
        for case in VECTORS["cases"]:
            r = extract(case["text"])
            msg = dict(r.message)
            if any(k in msg for k in ("found", "request", "hazard", "mayday")):
                msg["id"] = 1
            self.assertLessEqual(len(encode(msg)), 200, case["name"])


class Behaviour(unittest.TestCase):
    def test_empty_and_blank(self):
        for text in ("", "   ", "\n"):
            r = extract(text)
            self.assertTrue(r.unsupported)
            self.assertIsNone(r.message)
            self.assertEqual(r.reason, "empty")

    def test_unsupported_falls_back_to_text_cut_at_180_bytes(self):
        r = extract("Treffpunkt " + "ä" * 300)
        self.assertTrue(r.unsupported)
        self.assertLessEqual(len(r.message["text"]["text"].encode("utf-8")), 180)
        r.message["text"]["text"].encode("utf-8").decode("utf-8")  # valid UTF-8, no half characters

    def test_negated_found_is_not_found(self):
        self.assertTrue(extract("Person noch nicht gefunden").unsupported)
        self.assertTrue(extract("Nobody found yet, still searching").unsupported)

    def test_object_found_is_text(self):
        self.assertEqual(extract("Rucksack gefunden, keine Person").reason, "object_found")
        self.assertEqual(extract("Found a jacket near the path").reason, "object_found")

    def test_negation_flips_only_pairs(self):
        self.assertEqual(extract("Kind gefunden, nicht bewusstlos").message["found"]["consciousness"], "conscious")
        self.assertNotIn("injuries", extract("Kind gefunden, nicht unterkühlt").message["found"])
        self.assertNotIn("breathing", extract("Kind gefunden, Atmung unklar").message["found"])
        self.assertNotIn("injuries", extract("child found, maybe hypothermia").message["found"])

    def test_typos_and_inflections(self):
        self.assertEqual(extract("Kinder gefundn, 3 Kinder, bewustlos").message["found"],
                         {"count": 3, "age": "child", "consciousness": "unconscious"})
        self.assertEqual(extract("Brauchen 2 Hubschrauber").message,
                         {"request": {"resource": "helicopter", "count": 2}})

    def test_known_words_are_not_typos_of_each_other(self):
        # "verwirrt" (confused) is one letter from "verirrt" (lost, a Mayday word)
        self.assertEqual(extract("Frau gefunden, verwirrt").message["found"], {"age": "adult", "consciousness": "confused"})

    def test_breathing_none_is_critical(self):
        self.assertEqual(extract("Person found, not breathing").message["priority"], "critical")

    def test_mayday_wins(self):
        self.assertEqual(extract("Mayday, Kind gefunden").type, "mayday")

    def test_sector_needs_number_and_status(self):
        self.assertEqual(extract("Sector 3 cleared").message, {"sector": {"sector": 3, "status": "cleared"}})
        self.assertTrue(extract("Sector 3").unsupported)
        self.assertTrue(extract("Sector 3 cleared, sector 4 cleared").unsupported)

    def test_percent_gives_partial(self):
        self.assertEqual(extract("Sektor 3 zu 60 Prozent abgesucht").message,
                         {"sector": {"sector": 3, "status": "partial", "coverage": 60}})

    def test_coordinates(self):
        r = extract("Person gefunden bei 50,69212 7,21345 wach")
        self.assertEqual(r.message["pos"], {"lat": 50.69212, "lon": 7.21345, "acc": "estimated"})
        self.assertFalse(extract("Person gefunden bei 150.1 7.2 wach").message.get("pos"))

    def test_evidence_is_reported(self):
        r = extract("Kind gefunden, ansprechbar")
        self.assertIn({"field": "age", "code": "child", "words": "kind", "negated": False, "uncertain": False, "typo": False}, r.evidence)
        self.assertIn("found", r.cues)

    def test_never_raises_on_junk(self):
        for text in ("???", "1 2 3", "%" * 50, "ß" * 40, "Sektor " + "9" * 30, "gefunden " * 200, "\u0000‮", "50.1 7.2 50.1 7.2"):
            extract(text)


if __name__ == "__main__":
    unittest.main()


import pytest as _pytest


@_pytest.mark.parametrize("text", [
    "Person bewusstlos, atmet nicht", "person unconscious, not breathing",
    "Verletzter bewusstlos, atmet nicht", "Person bewusstlos",
])
def test_generic_person_noun_with_state_is_found(text):
    r = extract(text)
    assert not r.unsupported
    assert "found" in r.message


def test_generic_person_not_breathing_is_critical():
    r = extract("Person bewusstlos, atmet nicht")
    assert r.message["found"]["breathing"] == "none"
    assert r.message.get("priority") == "critical"


def test_generic_person_without_state_stays_text():
    assert extract("Person gesehen").unsupported


def test_avalanche_is_other_hazard():
    r = extract("Achtung Lawine")
    assert r.message["hazard"]["kind"] == "other"
