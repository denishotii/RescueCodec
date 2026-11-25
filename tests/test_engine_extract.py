"""Rule-based extractor: spec/vectors/extraction.json (conformance Level 3) and behaviour checks."""
import json
import unittest
from pathlib import Path

from rescuecodec.engine import extract, fit_text
from rescuecodec.v2 import CodecError, decode, encode

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

    def test_words_of_both_languages_do_not_pick_the_language(self):
        self.assertEqual(extract("Mayday, Kollege abgestürzt").lang, "de")     # "mayday" is in both lists
        self.assertEqual(extract("SOS").lang, "en")                             # nothing else: as before

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


class FitText(unittest.TestCase):
    """A long free text plus a position must still go out as one packet (same rule as TextFit.kt)."""
    POS = {"lat": 52.52001, "lon": 13.40495, "acc": "estimated"}

    def message(self, text):
        return {"id": 300, "pos": self.POS, "text": {"text": text}}      # id 300 takes 2 bytes, the most

    @staticmethod
    def bytes_of(words, n):
        """Exactly n bytes of UTF-8 from words repeated, padded with ASCII."""
        s = words * (n // len(words) + 2)
        while len(s.encode("utf-8")) > n:
            s = s[:-1]
        return s + "x" * (n - len(s.encode("utf-8")))

    def test_german_text_of_180_bytes_with_position_is_cut_to_200(self):
        text = self.bytes_of("Verletzte Person an der Brücke, Fußweg über den Bach zerstört, nördlich gehts nicht weiter. ", 180)
        self.assertEqual(len(text.encode("utf-8")), 180)
        msg = self.message(text)
        with self.assertRaises(CodecError) as cm:
            encode(msg)
        self.assertEqual(cm.exception.code, "too_large")                  # the bug: it did not fit
        fitted = fit_text(msg)
        data = encode(fitted)
        self.assertLessEqual(len(data), 200)
        cut = fitted["text"]["text"]
        self.assertTrue(cut.endswith("…"), cut)
        cut.encode("utf-8").decode("utf-8", "strict")                     # valid UTF-8
        self.assertTrue(text.startswith(cut[:-1]))
        self.assertFalse(cut[:-1][-1].isspace())
        self.assertEqual((fitted["pos"], fitted["id"]), (self.POS, 300))  # position and id stay
        self.assertEqual(decode(data)["text"]["text"], cut)

    def test_the_cut_is_the_longest_that_fits(self):
        text = self.bytes_of("Hilfe,Brücke,Fußweg,", 180)                 # no spaces: one character more is the next try
        cut = fit_text(self.message(text))["text"]["text"][:-1]
        with self.assertRaises(CodecError):
            encode(self.message(text[:len(cut) + 1] + "…"))

    def test_a_cut_never_splits_a_character(self):
        for ch in ("ü", "€", "🚑"):                                       # 2, 3 and 4 bytes
            fitted = fit_text(self.message(ch * (180 // len(ch.encode("utf-8")))))
            self.assertLessEqual(len(encode(fitted)), 200)
            self.assertRegex(fitted["text"]["text"], f"^({ch})+…$")

    def test_short_text_and_other_messages_are_unchanged(self):
        short = self.message("Hilfe am Waldweg, Bein gebrochen")
        self.assertIs(fit_text(short), short)
        found = {"id": 1, "pos": self.POS, "found": {"age": "child"}}
        self.assertIs(fit_text(found), found)
        too_long = self.message("x" * 181)                                # over 180 bytes is another error
        self.assertIs(fit_text(too_long), too_long)


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
