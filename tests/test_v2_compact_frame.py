"""Compact frame (SPEC section 3.7): 0x02, word dictionary version, word stream. Word dictionary 2. Send rule 6."""
import hashlib
import json
import random
import unittest

from rescuecodec.engine.textmode import choose_form
from rescuecodec.v2 import CodecError, decode, decode_any, decode_compact, detect_version, encode_compact, render
from rescuecodec.v2 import codes as C
from rescuecodec.v2 import textwords

D2 = textwords.dictionary(2)
POS = {"lat": 50.69212, "lon": 7.21345}
VECTORS = json.loads((textwords._spec_dir() / "vectors" / "codec.json").read_text(encoding="utf-8"))


class Frame(unittest.TestCase):
    def test_hello_world_is_six_bytes(self):
        data = encode_compact("hello world")
        self.assertEqual(data, bytes([0x02, 0x02]) + D2.index["hello"].to_bytes(2, "big")
                         + D2.index["world"].to_bytes(2, "big"))
        self.assertEqual(decode_any(data), {"v": 2, "text": {"text": "hello world", "words": 2}})

    def test_common_words_cost_two_bytes(self):
        for word in ("hello", "world", "we", "need", "water", "please", "bridge", "flooded", "stretcher", "tomorrow"):
            self.assertIn(word, D2.index, word)
        for word in ("I", "We", "The", "Please", "Hello"):     # capitalised forms are entries of their own
            self.assertIn(word, D2.index, word)
            self.assertEqual(len(encode_compact(word)), 4, word)

    def test_dictionary_1_still_decodes_in_a_frame(self):
        stream = textwords.encode_words("is safe.", 1)
        self.assertEqual(decode_compact(bytes([2, 1]) + stream), {"text": {"text": "is safe.", "words": 1}})

    def test_unknown_dictionary_is_visible(self):
        msg = decode_any(bytes.fromhex("020900100011"))
        self.assertEqual(msg, {"v": 2, "unknown": {"words": 9}})
        self.assertIn("dictionary this app does not have", render(msg, "en"))

    def test_errors_never_plausible_text(self):
        good = encode_compact("We found Aylin Demir, she is conscious")
        for cut in range(1, len(good)):
            try:
                msg = decode_compact(good[:cut])
            except CodecError as exc:
                self.assertIn(exc.code, ("malformed",))
                continue
            # a cut that ends on a unit boundary is a shorter valid stream: it must be a prefix, never other words
            self.assertTrue("We found Aylin Demir, she is conscious".startswith(msg["text"]["text"]), cut)

    def test_old_readers_reject_it(self):
        """A 2.0 / 2.1 reader treats 0x02 as a reserved version: unsupported_version, an unreadable-message line."""
        data = encode_compact("hello world")
        self.assertEqual(detect_version(data), 0)          # not an Envelope
        with self.assertRaises(CodecError) as ctx:
            decode(data)                                   # the Envelope decoder (what an old reader has)
        self.assertEqual(ctx.exception.code, "unsupported_version")

    def test_round_trip_random(self):
        rnd = random.Random(20261125)
        parts = list(D2.entries[:3000]) + [" ", "  ", "\n", ",", ".", "Ä", "🙂", "X", "42", "Aylin", "-1.2500"]
        ranges = [(0x20, 0x7E), (0x09, 0x0D), (0xA0, 0x7FF), (0x10000, 0x1F64F)]
        for i in range(3000):
            if i % 3 == 0:
                text = "".join(chr(rnd.randint(*rnd.choice(ranges))) for _ in range(rnd.randint(1, 50)))
            else:
                text = "".join(rnd.choice(parts) + rnd.choice(["", " ", " ", " "]) for _ in range(rnd.randint(1, 25)))
                if rnd.random() < 0.3:
                    text = text.upper() if rnd.random() < 0.5 else text.title()
            if not text:
                continue
            try:
                data = encode_compact(text)
            except CodecError as exc:
                self.assertEqual(exc.code, "too_large")
                continue
            self.assertLessEqual(len(data), C.MAX_ENCODE_BYTES)
            self.assertEqual(decode_compact(data), {"text": {"text": text, "words": 2}}, text)


class Vectors(unittest.TestCase):
    """spec/vectors/codec.json compact_frames (what Kotlin and TypeScript run too)."""

    def test_frames(self):
        for case in VECTORS["compact_frames"]:
            with self.subTest(case["name"]):
                if "text" in case:
                    self.assertEqual(encode_compact(case["text"], case["dict"]).hex(), case["hex"])
                data = bytes.fromhex(case["hex"])
                self.assertEqual(len(data), case["size"])
                self.assertEqual(decode_compact(data), case["decoded"])
                self.assertEqual(decode_any(data), {"v": 2, **case["decoded"]})
                self.assertEqual(render(case["decoded"], "en"), case["render"]["en"])
                self.assertEqual(render(case["decoded"], "de"), case["render"]["de"])

    def test_invalid(self):
        for case in VECTORS["compact_frames_invalid"]:
            with self.subTest(case["name"]):
                if "text" in case:
                    with self.assertRaises(CodecError) as ctx:
                        encode_compact(case["text"])
                    self.assertEqual(ctx.exception.code, case["error"])
                if "hex" in case:
                    with self.assertRaises(CodecError) as ctx:
                        decode_any(bytes.fromhex(case["hex"]))
                    self.assertEqual(ctx.exception.code, case["error"])

    def test_dictionary_2_frozen(self):
        info = VECTORS["word_dicts"]["2"]
        data = (textwords._spec_dir() / info["file"]).read_bytes()
        self.assertEqual(hashlib.sha256(data).hexdigest(), textwords.DICTS[2][1])
        self.assertEqual((len(D2.entries), info["entries"]), (textwords.FIRST_CONTROL, textwords.FIRST_CONTROL))
        self.assertTrue(data.endswith(b"\n") and b"\r" not in data)
        for entry in D2.entries:
            self.assertNotRegex(entry, r"\d{5}|@|http|www", entry)


class SendRule(unittest.TestCase):
    def test_hello_world_goes_compact(self):
        form = choose_form({"id": 3, "text": {"text": "hello world"}}, True, "rescuecodec_only", compact=True)
        self.assertEqual((form.form, len(form.payload), form.plain_size), ("lean", 6, 11))
        self.assertEqual(form.message, {"text": {"text": "hello world", "words": 2}})

    def test_off_or_not_packing_keeps_the_old_rule(self):
        self.assertEqual(choose_form({"id": 3, "text": {"text": "hello world"}}, True, "x").form, "plain")
        self.assertEqual(choose_form({"id": 3, "text": {"text": "hello world"}}, False, "x", compact=True).form, "plain")

    def test_tie_keeps_plain(self):
        # "Ok" is 2 bytes as a normal text; a frame is at least 4
        form = choose_form({"id": 3, "text": {"text": "Ok"}}, True, "x", compact=True)
        self.assertEqual(form.form, "plain")
        rnd = random.Random(5)
        for _ in range(300):
            text = "".join(rnd.choice("qxzj7 ") for _ in range(rnd.randint(1, 12))).strip() or "q"
            form = choose_form({"id": 3, "text": {"text": text}}, True, "x", compact=True)
            if form.form == "plain":
                self.assertLessEqual(form.plain_size, min(s for s in (form.lean_size, form.words_size,
                                                                       form.packed_size) if s is not None))
            else:
                self.assertLess(len(form.payload), form.plain_size)

    def test_position_keeps_the_envelope(self):
        form = choose_form({"id": 3, "pos": POS, "text": {"text": "We are at the hut, two people with us, waiting"}},
                           True, "x", compact=True)
        self.assertIsNone(form.lean_size)
        self.assertEqual(form.form, "words")
        self.assertEqual(decode(form.payload)["pos"], POS)

    def test_priority_is_kept_locally(self):
        form = choose_form({"id": 3, "priority": "urgent", "text": {"text": "we need water now"}}, True, "x",
                           compact=True, vocab="general")
        self.assertEqual(form.form, "lean")
        self.assertEqual(form.message["priority"], "urgent")
        form = choose_form({"id": 3, "priority": "urgent", "text": {"text": "we need water now"}}, True, "x",
                           compact=True)                 # a team vocabulary saves a byte here: a domain frame
        self.assertEqual((form.form, form.message["priority"]), ("domain", "urgent"))


if __name__ == "__main__":
    unittest.main()
