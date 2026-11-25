"""The spec files agree with each other: proto, code tables, vocabulary, languages, extraction vectors."""
import json
import unittest
from pathlib import Path

import yaml

from rescuecodec.v2 import canonical, codes as C, render
from rescuecodec.v2 import rescuecodec_pb2 as pb

ROOT = Path(__file__).resolve().parents[1]
SPEC = ROOT / "spec"
VOCAB = yaml.safe_load((SPEC / "vocab" / "sar.yaml").read_text(encoding="utf-8"))
VOCAB_FIELDS = {
    "priority": C.PRIORITY, "acc": C.ACCURACY, "consciousness": C.CONSCIOUSNESS, "breathing": C.BREATHING,
    "mobility": C.MOBILITY, "age": C.AGE, "injuries": C.INJURIES, "needs": C.NEEDS,
    "sector.status": C.SECTOR_STATUS, "request.resource": C.RESOURCE, "request.urgency": C.URGENCY,
    "hazard.kind": C.HAZARD, "hazard.severity": C.SEVERITY, "mayday.reason": C.MAYDAY_REASON,
    "ack.status": C.ACK_STATUS,
}


class ProtoTableTests(unittest.TestCase):
    def test_enum_tables_match_proto(self):
        for enum_name, (table, prefix) in C.PROTO_ENUMS.items():
            with self.subTest(enum_name):
                enum = pb.DESCRIPTOR.enum_types_by_name[enum_name]
                proto = {v.name[len(prefix):].lower(): v.number for v in enum.values if v.number != 0}
                if enum_name == "Accuracy":
                    proto["gps"] = 0
                self.assertEqual(proto, table)

    def test_flag_tables_match_proto(self):
        for enum_name, (table, prefix) in C.PROTO_FLAGS.items():
            with self.subTest(enum_name):
                enum = pb.DESCRIPTOR.enum_types_by_name[enum_name]
                proto = {v.name[len(prefix):].lower(): v.number.bit_length() - 1 for v in enum.values if v.number}
                self.assertEqual(proto, table)
                for v in enum.values:
                    if v.number:
                        self.assertEqual(v.number & (v.number - 1), 0, f"{v.name} must be a single bit")

    def test_body_field_numbers_are_frozen(self):
        fields = {f.name: f.number for f in pb.Envelope.DESCRIPTOR.fields}
        self.assertEqual(fields, {"id": 1, "priority": 2, "pos": 3, "time": 4, "found": 6, "track": 7, "sector": 8,
                                  "request": 9, "hazard": 10, "mayday": 11, "ack": 12, "text": 13})
        self.assertEqual(tuple(f.name for f in pb.Envelope.DESCRIPTOR.oneofs_by_name["body"].fields), C.BODY_TYPES)


class VocabTests(unittest.TestCase):
    def test_every_code_is_in_the_vocabulary_with_the_same_id(self):
        self.assertEqual(set(VOCAB["fields"]), set(VOCAB_FIELDS))
        for field, table in VOCAB_FIELDS.items():
            with self.subTest(field):
                values = VOCAB["fields"][field]["values"]
                self.assertEqual({v["code"]: v["id"] for v in values}, table)
                for v in values:
                    self.assertTrue(v["meaning"])
                    self.assertIsInstance(v["en"], list)
                    self.assertIsInstance(v["de"], list)
                    for word in v["en"] + v["de"]:
                        self.assertEqual(str(word), str(word).lower(), f"{field}.{v['code']}: {word!r} must be lowercase")

    def test_every_message_type_is_listed(self):
        self.assertEqual(tuple(VOCAB["messages"]), C.BODY_TYPES)


class LanguageTests(unittest.TestCase):
    def setUp(self):
        self.langs = {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in (SPEC / "i18n").glob("*.json")}

    def test_languages_present_and_marked(self):
        self.assertEqual(set(self.langs), {"en", "de", "uk", "tr", "ar"})
        for code in ("en", "de"):
            self.assertEqual(self.langs[code]["status"], "reviewed")
        for code in ("uk", "tr", "ar"):
            self.assertEqual(self.langs[code]["status"], "machine-draft")
            self.assertIn("MACHINE DRAFT", self.langs[code]["warning"])

    def test_same_keys_everywhere(self):
        en = self.langs["en"]
        for code, data in self.langs.items():
            for section in ("punct", "head", "part", "value"):
                with self.subTest(code=code, section=section):
                    self.assertEqual(set(data[section]), set(en[section]))

    def test_every_code_has_a_word(self):
        groups = {"priority": C.PRIORITY, "consciousness": C.CONSCIOUSNESS, "breathing": C.BREATHING,
                  "mobility": C.MOBILITY, "age": C.AGE, "injuries": C.INJURIES, "needs": C.NEEDS,
                  "sector": C.SECTOR_STATUS, "resource": C.RESOURCE, "urgency": C.URGENCY, "hazard": C.HAZARD,
                  "severity": C.SEVERITY, "mayday": C.MAYDAY_REASON}
        keys = set(self.langs["en"]["value"])
        for group, table in groups.items():
            for code in table:
                self.assertIn(f"{group}.{code}", keys)
        for acc in ("gps_poor", "estimated", "area"):
            self.assertIn(f"acc.{acc}", keys)

    def test_every_language_renders_every_vector(self):
        vectors = json.loads((SPEC / "vectors" / "codec.json").read_text(encoding="utf-8"))
        for code in self.langs:
            for case in vectors["valid"] + vectors["decode_only"] + vectors["v1"]:
                with self.subTest(lang=code, case=case["name"]):
                    text = render(case["decoded"], code)
                    self.assertTrue(text)
                    self.assertNotIn("{", text)


class ExtractionVectorTests(unittest.TestCase):
    def test_extraction_cases(self):
        data = json.loads((SPEC / "vectors" / "extraction.json").read_text(encoding="utf-8"))
        cases = data["cases"]
        self.assertGreaterEqual(len(cases), 40)
        self.assertGreaterEqual(sum(c["unsupported"] for c in cases), 5)
        self.assertGreaterEqual(sum(c["lang"] == "de" for c in cases), 15)
        self.assertGreaterEqual(sum(c["lang"] == "en" for c in cases), 15)
        self.assertEqual(len({c["name"] for c in cases}), len(cases))
        for case in cases:
            with self.subTest(case["name"]):
                expect = dict(case["expect"])
                kind = next(k for k in expect if k in C.BODY_TYPES)
                if case["unsupported"]:
                    self.assertEqual(kind, "text")
                    self.assertTrue(case["reason"])
                if "id" in expect:
                    self.fail("extraction never sets an id")
                if kind in C.ID_REQUIRED:
                    expect["id"] = 1
                normal = canonical(expect)
                if kind in C.ID_REQUIRED:
                    normal.pop("id")
                    expect.pop("id")
                self.assertEqual(normal, expect, "expect must already be canonical (bit order, no zero values)")
                if "priority" in expect:
                    self.assertNotEqual(expect["priority"], C.DEFAULT_PRIORITY[kind],
                                        "priority is only set when it differs from the type default")


if __name__ == "__main__":
    unittest.main()
