"""Conformance: the Python reference must pass every case in spec/vectors/codec.json."""
import json
import shutil
import subprocess
import unittest
from pathlib import Path

from rescuecodec.v2 import CodecError, decode, decode_any, encode, render

ROOT = Path(__file__).resolve().parents[1]
VECTORS = json.loads((ROOT / "spec" / "vectors" / "codec.json").read_text(encoding="utf-8"))


class VectorTests(unittest.TestCase):
    def test_enough_cases(self):
        total = sum(len(VECTORS[k]) for k in ("valid", "decode_only", "invalid_encode", "invalid_decode", "v1"))
        self.assertGreaterEqual(total, 40)
        self.assertGreaterEqual(len(VECTORS["valid"]), 30)
        self.assertGreaterEqual(len(VECTORS["invalid_encode"]) + len(VECTORS["invalid_decode"]), 20)
        names = [c["name"] for k in ("valid", "decode_only", "invalid_encode", "invalid_decode", "v1") for c in VECTORS[k]]
        self.assertEqual(len(names), len(set(names)), "case names must be unique")

    def test_valid_encode_gives_exact_bytes(self):
        for case in VECTORS["valid"]:
            with self.subTest(case["name"]):
                data = encode(case["input"])
                self.assertEqual(data.hex(), case["hex"])
                self.assertEqual(len(data), case["size"])

    def test_valid_decode_and_roundtrip(self):
        for case in VECTORS["valid"]:
            with self.subTest(case["name"]):
                decoded = decode(bytes.fromhex(case["hex"]))
                self.assertEqual(decoded, case["decoded"])
                self.assertEqual(encode(decoded).hex(), case["hex"], "decode then encode must give the same bytes")

    def test_valid_decode_survives_json(self):
        # Libraries in other languages see the vectors only as JSON text.
        for case in VECTORS["valid"]:
            with self.subTest(case["name"]):
                again = json.loads(json.dumps(decode(bytes.fromhex(case["hex"]))))
                self.assertEqual(encode(again).hex(), case["hex"])

    def test_rendered_text(self):
        for case in VECTORS["valid"]:
            with self.subTest(case["name"]):
                self.assertEqual(render(case["decoded"], "en"), case["text"]["en"])
                self.assertEqual(render(case["decoded"], "de"), case["text"]["de"])

    def test_decode_only(self):
        for case in VECTORS["decode_only"]:
            with self.subTest(case["name"]):
                self.assertEqual(decode(bytes.fromhex(case["hex"])), case["decoded"])

    def test_invalid_encode_is_rejected(self):
        for case in VECTORS["invalid_encode"]:
            with self.subTest(case["name"]):
                with self.assertRaises(CodecError) as ctx:
                    encode(case["input"])
                self.assertEqual(ctx.exception.code, case["error"])
                self.assertEqual(ctx.exception.field, case["field"])

    def test_invalid_decode_is_rejected(self):
        for case in VECTORS["invalid_decode"]:
            with self.subTest(case["name"]):
                with self.assertRaises(CodecError) as ctx:
                    decode(bytes.fromhex(case["hex"]))
                self.assertEqual(ctx.exception.code, case["error"])
                self.assertEqual(ctx.exception.field, case["field"])

    def test_v1_cases(self):
        for case in VECTORS["v1"]:
            with self.subTest(case["name"]):
                self.assertEqual(decode_any(bytes.fromhex(case["hex"])), case["decoded"])

    def test_packed_text(self):
        from rescuecodec.v2 import textpack
        self.assertGreaterEqual(len(VECTORS["packed_text"]), 5)
        for case in VECTORS["packed_text"]:
            with self.subTest(case["name"]):
                self.assertEqual(textpack.unpack(bytes.fromhex(case["deflate"]), case["dict"]), case["text"])
                self.assertEqual(decode(bytes.fromhex(case["hex"])), case["decoded"])
                self.assertEqual(decode(encode(case["decoded"])), case["decoded"])
                self.assertLess(len(bytes.fromhex(case["deflate"])), case["text_size"])

    def test_text_dictionary_hash(self):
        import hashlib
        for number, entry in VECTORS["text_dicts"].items():
            data = (ROOT / "spec" / entry["file"]).read_bytes()
            self.assertEqual(hashlib.sha256(data).hexdigest(), entry["sha256"], f"dictionary {number} changed")
            self.assertEqual(len(data), entry["size"])
            self.assertLess(len(data), 16 * 1024)

    def test_every_body_type_is_covered(self):
        kinds = {next(k for k in c["input"] if k in ("found", "track", "sector", "request", "hazard", "mayday",
                                                      "ack", "text")) for c in VECTORS["valid"]}
        self.assertEqual(kinds, {"found", "track", "sector", "request", "hazard", "mayday", "ack", "text"})

    @unittest.skipUnless(shutil.which("protoc"), "protoc not installed")
    def test_protoc_parses_every_valid_vector(self):
        """Independent check: the C++ protobuf parser in protoc reads every vector as an Envelope."""
        for case in VECTORS["valid"] + VECTORS["decode_only"]:
            with self.subTest(case["name"]):
                out = subprocess.run(
                    ["protoc", "-I", str(ROOT / "spec"), "--decode=rescuecodec.v2.Envelope",
                     "rescuecodec/v2/rescuecodec.proto"],
                    input=bytes.fromhex(case["hex"]), capture_output=True, cwd=ROOT, check=False)
                self.assertEqual(out.returncode, 0, out.stderr.decode())


if __name__ == "__main__":
    unittest.main()
