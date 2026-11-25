"""Domain frames (SPEC section 3.8, first byte 0x03): vectors, round trips, errors, the send rule."""
import hashlib
import importlib.util
import json
import os
import random
import shutil
import tempfile
import unittest
from pathlib import Path

import yaml

from rescuecodec.engine.textmode import choose_form
from rescuecodec.v2 import (CodecError, decode_any, decode_domain, detect_version, encode, encode_compact,
                            encode_domain, is_domain_frame, render, textwords)
from rescuecodec.v2 import textdomain as T

ROOT = Path(__file__).resolve().parents[1]
VECTORS = json.loads((ROOT / "spec" / "vectors" / "domain.json").read_text(encoding="utf-8"))
MEDICAL = "Patient is conscious, bleeding from the leg, apply a tourniquet."


class Table(unittest.TestCase):
    def test_vocabularies_match_domains_yaml_and_the_files(self):
        table = yaml.safe_load((ROOT / "spec" / "vocab" / "domains.yaml").read_text(encoding="utf-8"))
        self.assertEqual(table["reserved_codes"], T.RESERVED)
        self.assertEqual(table["general_dictionary"]["version"], T.GENERAL_DICT)
        self.assertEqual(table["general_dictionary"]["sha256"], textwords.DICTS[T.GENERAL_DICT][1])
        rows = {v["id"]: v for v in table["vocabularies"]}
        self.assertEqual(set(rows), set(T.VOCABS))
        for vid, v in T.VOCABS.items():
            row = rows[vid]
            self.assertEqual((v.domain, v.label, v.short, v.version, v.bits, v.entries, v.file, v.sha256),
                             (row["domain"], row["label"], row["short"], row["version"], row["bits"], row["entries"],
                              row["file"], row["sha256"]))
            self.assertTrue(T.available(vid))
            self.assertEqual(len(T.vocabulary(vid).entries), (1 << v.bits) - T.RESERVED)

    def test_vectors_list_the_same_vocabularies(self):
        for row in VECTORS["vocabularies"]:
            v = T.VOCABS[row["id"]]
            data = (ROOT / "spec" / "vocab" / row["file"]).read_bytes()
            self.assertEqual(hashlib.sha256(data).hexdigest(), row["sha256"])
            self.assertEqual((v.domain, v.bits, v.entries), (row["domain"], row["bits"], row["entries"]))

    def test_settings(self):
        self.assertEqual(T.SETTINGS, ("auto", "general", "emergency", "sar", "fire", "hiking", "medical"))
        self.assertEqual(T.candidates("auto"), (1, 2, 3, 4, 5))
        self.assertEqual(T.candidates("general"), ())
        self.assertEqual(T.candidates("sar"), (2,))
        with self.assertRaises(ValueError):
            T.candidates("SAR")
        for bad in (0, 6, 255, True, "x", None):
            with self.assertRaises(KeyError):
                T.vocab_id(bad)


class Vectors(unittest.TestCase):
    """spec/vectors/domain.json: what Kotlin and TypeScript run too."""

    def test_vectors_are_up_to_date(self):
        spec = importlib.util.spec_from_file_location("gen_domain_vectors", ROOT / "spec" / "tools" / "gen_domain_vectors.py")
        gen = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(gen)
        self.assertEqual(gen.dumps(gen.build()), (ROOT / "spec" / "vectors" / "domain.json").read_text(encoding="utf-8"),
                         "run spec/tools/gen_domain_vectors.py")

    def test_frames(self):
        for case in VECTORS["frames"]:
            with self.subTest(case["name"]):
                data = encode_domain(case["text"], case["vocab"])
                self.assertEqual(data.hex(), case["hex"])
                self.assertEqual(encode_domain(case["text"], case["vocab_id"]), data)
                self.assertEqual(decode_domain(data), case["decoded"])
                self.assertEqual(decode_any(data), {"v": 2, **case["decoded"]})
                trace: list[str] = []
                T.decode_stream(data[2:], case["vocab"], trace)
                self.assertEqual(trace, case["trace"])

    def test_streams_and_random(self):
        for case in VECTORS["streams"] + VECTORS["random"]:
            with self.subTest(case["name"]):
                self.assertEqual(T.encode_stream(case["text"], case["vocab"]).hex(), case["stream"])
                self.assertEqual(T.decode_stream(bytes.fromhex(case["stream"]), case["vocab"]), case["text"])

    def test_invalid(self):
        for case in VECTORS["invalid_decode"]:
            with self.subTest(case["name"]):
                with self.assertRaises(CodecError) as ctx:
                    decode_domain(bytes.fromhex(case["hex"]))
                self.assertEqual(ctx.exception.code, case["error"])
                with self.assertRaises(CodecError):
                    decode_any(bytes.fromhex(case["hex"]))
        for case in VECTORS["invalid_encode"]:
            with self.subTest(case["name"]):
                with self.assertRaises(CodecError) as ctx:
                    encode_domain(case["text"], case["vocab"])
                self.assertEqual(ctx.exception.code, case["error"])

    def test_unknown_vocabulary_is_visible(self):
        for case in VECTORS["unknown"]:
            with self.subTest(case["name"]):
                msg = decode_domain(bytes.fromhex(case["hex"]))
                self.assertEqual(msg, case["decoded"])
                self.assertEqual(render(msg, "en"), case["render"]["en"])
                self.assertIn("unsupported vocabulary", render(msg, "en"))

    def test_choice(self):
        for case in VECTORS["choice"]:
            with self.subTest(case["name"]):
                form = choose_form({"id": 3, "text": {"text": case["text"]}}, True, "x", compact=True,
                                   vocab=case["setting"])
                self.assertEqual((form.form, form.vocab, len(form.payload), form.payload.hex()),
                                 (case["form"], case["vocab"], case["size"], case["hex"]))
                self.assertEqual((form.plain_size, form.lean_size, form.words_size, form.domain_sizes or {}),
                                 (case["sizes"]["plain"], case["sizes"]["lean"], case["sizes"]["words"],
                                  case["sizes"]["domain"]))


def random_text(rnd: random.Random, vocab: str) -> str:
    """Random UTF-8: vocabulary entries, words of word dictionary 2, any code point, odd spaces and case."""
    dd, d2 = T.vocabulary(vocab), textwords.dictionary(2)
    parts = []
    for _ in range(rnd.randint(0, 30)):
        r = rnd.random()
        if r < 0.35:
            w = rnd.choice(dd.entries)
        elif r < 0.55:
            w = rnd.choice(d2.entries)
        elif r < 0.7:
            w = rnd.choice(textwords.SPLIT + "-'/#")
        elif r < 0.85:
            w = "".join(chr(rnd.choice([rnd.randint(0x20, 0x7E), rnd.randint(0xA0, 0x2FFF),
                                        rnd.randint(0x1F300, 0x1F6FF), rnd.randint(0xE000, 0xFFFD)]))
                        for _ in range(rnd.randint(1, 6)))
        else:
            w = str(rnd.randint(0, 10 ** rnd.randint(1, 8)))
        if rnd.random() < 0.2:
            w = w[:1].upper() + w[1:]
        elif rnd.random() < 0.1:
            w = w.upper()
        parts.append(w)
        parts.append(rnd.choice([" "] * 10 + ["", "  ", "\n", "\t", "\r\n", "  "]))
    return "".join(parts)


class RoundTrip(unittest.TestCase):
    def test_random_utf8_round_trip_for_every_vocabulary(self):
        rnd = random.Random(7)
        for vocab in T.CURRENT:
            for i in range(400):
                text = random_text(rnd, vocab)[:rnd.randint(0, 600)]
                stream = T.encode_stream(text, vocab)
                if not text:
                    self.assertEqual(stream, b"")
                    continue
                self.assertEqual(T.decode_stream(stream, vocab), text, (vocab, text))
                raw = len(text.encode("utf-8"))
                self.assertLessEqual(len(stream), raw + 3 * (raw // 255 + 1), (vocab, text))   # never worse than LIT
                try:
                    data = encode_domain(text, vocab)
                except CodecError as exc:
                    self.assertEqual(exc.code, "too_large")
                    self.assertGreater(2 + len(stream), 200)
                    continue
                self.assertEqual(decode_any(data), {"v": 2, "text": {"text": text, "vocab": T.CURRENT[vocab]}})

    def test_random_bytes_never_crash(self):
        rnd = random.Random(11)
        for _ in range(3000):
            data = bytes([3, rnd.choice([1, 2, 3, 4, 5, rnd.randint(0, 255)])]) + rnd.randbytes(rnd.randint(0, 60))
            try:
                msg = decode_any(data)
            except CodecError as exc:
                self.assertIn(exc.code, ("malformed", "too_large"))
                continue
            self.assertEqual(set(msg) - {"v"}, {"text"} if "text" in msg else {"unknown"})

    def test_every_short_payload(self):
        for a in range(256):
            for data in (bytes([3, a]), *(bytes([3, 2, a, b]) for b in range(0, 256, 17))):
                try:
                    decode_any(data)
                except CodecError as exc:
                    self.assertIn(exc.code, ("malformed", "too_large"))

    def test_a_cut_frame_is_an_error_or_a_shorter_prefix(self):
        """The frame has no length: a cut on a code boundary with zero padding reads as a shorter text, like 3.7."""
        rnd = random.Random(5)
        for vocab in T.CURRENT:
            for _ in range(40):
                text = random_text(rnd, vocab)[:150] or "x"
                try:
                    data = encode_domain(text, vocab)
                except CodecError:
                    continue
                for cut in range(2, len(data)):
                    try:
                        got = decode_domain(data[:cut])["text"]["text"]
                    except CodecError as exc:
                        self.assertEqual(exc.code, "malformed")
                        continue
                    self.assertTrue(text.startswith(got) and got != text, (text, cut, got))

    def test_vocabulary_words_cost_10_bits(self):
        self.assertEqual(encode_domain("Apply a tourniquet.", "medical").hex(), "0305e300609c00")
        self.assertEqual(len(encode_domain("tourniquet", "medical")), 4)       # 10 bits -> 2 bytes + 2
        self.assertEqual(len(encode_domain("ridge", "fire")), 6)               # ESC16: 26 bits -> 4 bytes + 2
        self.assertEqual(len(encode_domain("tourniquet", "fire")), 15)         # not in dictionary 2: LIT, 98 bits

    def test_never_a_near_match(self):
        for text in ("tourniquets", "Tourniquet!", "TOURNIQUEt", "tourniquet ", "touniquet", "Café"):
            self.assertEqual(decode_domain(encode_domain(text, "medical"))["text"]["text"], text)


class Codec(unittest.TestCase):
    def test_first_byte(self):
        data = encode_domain("Apply a tourniquet.", "medical")
        self.assertTrue(is_domain_frame(data))
        self.assertFalse(is_domain_frame(encode_compact("hello")))
        self.assertEqual(detect_version(data), 0)                    # not an Envelope: decode() rejects it
        for first in (0x04, 0x05, 0x06, 0x07):
            with self.assertRaises(CodecError) as ctx:
                decode_any(bytes([first, 1, 0]))
            self.assertEqual(ctx.exception.code, "unsupported_version")

    def test_vocab_is_not_an_envelope_field(self):
        with self.assertRaises(CodecError) as ctx:
            encode({"id": 1, "text": {"text": "fire", "vocab": 3}})
        self.assertEqual((ctx.exception.code, ctx.exception.field), ("invalid_value", "text.vocab"))

    def test_render(self):
        msg = decode_any(encode_domain("Apply a tourniquet.", "medical"))
        self.assertEqual(render(msg, "en"), "Message: Apply a tourniquet.")

    def test_damaged_copy_is_unsupported_not_words(self):
        """A vocabulary file that fails the SHA-256 check decodes as "unsupported vocabulary", never as words."""
        data = encode_domain("Apply a tourniquet.", "medical")
        with tempfile.TemporaryDirectory() as tmp:
            spec = Path(tmp) / "spec"
            shutil.copytree(ROOT / "spec" / "vocab", spec / "vocab")
            path = spec / "vocab" / T.VOCABS[5].file
            lines = path.read_text(encoding="ascii").split("\n")
            lines[39], lines[40] = lines[40], lines[39]                     # two entries swapped
            path.write_text("\n".join(lines), encoding="ascii")
            old = os.environ.get("RESCUECODEC_SPEC_DIR")
            os.environ["RESCUECODEC_SPEC_DIR"] = str(spec)
            T.vocabulary.cache_clear()
            textwords.dictionary.cache_clear()
            try:
                self.assertFalse(T.available(5))
                self.assertTrue(T.available(2))
                self.assertEqual(decode_any(data), {"v": 2, "unknown": {"vocab": 5}})
                with self.assertRaises(CodecError):
                    encode_domain("Apply a tourniquet.", "medical")
            finally:
                if old is None:
                    os.environ.pop("RESCUECODEC_SPEC_DIR", None)
                else:
                    os.environ["RESCUECODEC_SPEC_DIR"] = old
                T.vocabulary.cache_clear()
                textwords.dictionary.cache_clear()
        self.assertTrue(T.available(5))


class SendRule(unittest.TestCase):
    def form(self, text, vocab="auto", **extra):
        return choose_form({"id": 3, **extra, "text": {"text": text}}, True, "x", compact=True, vocab=vocab)

    def test_domain_frame_wins_and_keeps_priority_locally(self):
        form = self.form(MEDICAL, priority="urgent")
        self.assertEqual((form.form, form.vocab, len(form.payload)), ("domain", "medical", 19))
        self.assertEqual(form.message, {"priority": "urgent", "text": {"text": MEDICAL, "vocab": 5}})
        self.assertEqual(form.payload[:2], b"\x03\x05")

    def test_never_larger_than_plain_or_the_general_forms(self):
        rnd = random.Random(3)
        for vocab in T.SETTINGS:
            for _ in range(30):
                text = random_text(rnd, "sar")[:300].strip() or "x"
                form = self.form(text, vocab)
                sizes = [s for s in (form.lean_size, form.words_size, form.packed_size, form.domain_size)
                         if s is not None]
                if form.form == "plain":
                    self.assertTrue(all(len(form.payload) <= s for s in sizes) or form.cut, text)
                else:
                    self.assertEqual(len(form.payload), min(sizes), text)
                    self.assertTrue(len(form.payload) < form.plain_size or form.cut, text)
                if vocab == "general":
                    self.assertIsNone(form.domain_sizes)
                elif vocab != "auto" and form.domain_sizes:
                    self.assertEqual(set(form.domain_sizes), {vocab})

    def test_pinned_vocabulary_tries_only_that_one(self):
        self.assertEqual(self.form(MEDICAL, "sar").form, "lean")
        self.assertEqual((self.form(MEDICAL, "medical").form, self.form(MEDICAL, "medical").vocab), ("domain", "medical"))
        self.assertEqual(self.form(MEDICAL, "general").form, "lean")
        with self.assertRaises(ValueError):
            self.form(MEDICAL, "sars")

    def test_a_position_keeps_the_envelope(self):
        form = self.form(MEDICAL, pos={"lat": 50.69212, "lon": 7.21345})
        self.assertEqual(form.form, "words")
        self.assertIsNone(form.domain_sizes)

    def test_compact_off_never_sends_a_domain_frame(self):
        form = choose_form({"id": 3, "text": {"text": MEDICAL}}, True, "x")
        self.assertEqual(form.form, "deflate")


if __name__ == "__main__":
    unittest.main()
