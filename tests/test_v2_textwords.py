"""Compact texts: 2-byte word tokens (SPEC section 3.6) and the opt-in send rule (section 2.3, rule 6)."""
import hashlib
import random
import unittest

from rescuecodec.engine.textmode import MIN_SAVING_BYTES, choose_form, fit_packed
from rescuecodec.v2 import CodecError, decode, decode_any, encode, render
from rescuecodec.v2 import codes as C
from rescuecodec.v2 import rescuecodec_pb2 as pb
from rescuecodec.v2 import textpack, textwords
from rescuecodec.v2.codec import validate
from rescuecodec.v2.textwords import CAP, GLUE, LITERAL, UPPER, WordsError, decode_words, encode_words

D = textwords.dictionary(1)
POS = {"lat": 50.69212, "lon": 7.21345}


def tok(entry: str) -> bytes:
    return D.index[entry].to_bytes(2, "big")


def lit(text: str) -> bytes:
    data = text.encode("utf-8")
    return (LITERAL + len(data)).to_bytes(2, "big") + data


def ctl(code: int) -> bytes:
    return code.to_bytes(2, "big")


class Dictionary(unittest.TestCase):
    def test_frozen_file(self):
        data = (textwords._spec_dir() / "vocab" / "words-dict-v1.txt").read_bytes()
        self.assertEqual(hashlib.sha256(data).hexdigest(), textwords.DICTS[1][1])
        self.assertTrue(data.endswith(b"\n") and b"\r" not in data)
        self.assertEqual(len(D.entries), data.count(b"\n"))
        self.assertLess(len(D.entries), textwords.FIRST_CONTROL)

    def test_no_phone_numbers_or_addresses(self):
        for entry in D.entries:
            self.assertNotRegex(entry, r"\d{5}|@|http|www", entry)

    def test_damaged_copy_is_not_used(self):
        self.assertTrue(textwords.available(1))
        self.assertTrue(textwords.available(2))
        self.assertFalse(textwords.available(9))


class Boundaries(unittest.TestCase):
    """The three spacing rules, byte by byte (SPEC section 3.6.3)."""

    def test_literal_owns_its_leading_space(self):
        data = encode_words("We found Aylin Demir", 1)
        self.assertEqual(data, tok("We") + tok("found") + lit(" Aylin Demir"))         # 2 + 2 + 2 + 12 bytes
        self.assertEqual(decode_words(data, 1), "We found Aylin Demir")

    def test_token_after_a_literal_gets_no_implied_space(self):
        data = encode_words("Aylin Demir is safe", 1)
        self.assertEqual(data, lit("Aylin Demir ") + tok("is") + tok("safe"))
        self.assertEqual(decode_words(data, 1), "Aylin Demir is safe")
        # the same tokens without the space in the literal: the words touch, nothing is invented
        self.assertEqual(decode_words(lit("Aylin Demir") + tok("is") + tok("safe"), 1), "Aylin Demiris safe")

    def test_space_only_between_adjacent_tokens(self):
        self.assertEqual(decode_words(tok("is") + tok("safe"), 1), "is safe")
        self.assertEqual(decode_words(tok("is") + ctl(GLUE) + tok("safe"), 1), "issafe")
        self.assertEqual(decode_words(tok("safe") + tok("."), 1), "safe.")          # punctuation attaches left
        self.assertEqual(decode_words(tok("(") + tok("safe") + tok(")"), 1), "(safe)")

    def test_case_controls(self):
        self.assertEqual(decode_words(ctl(CAP) + tok("safe"), 1), "Safe")
        self.assertEqual(decode_words(ctl(UPPER) + tok("safe"), 1), "SAFE")
        self.assertEqual(encode_words("SAFE", 1), ctl(UPPER) + tok("safe"))

    def test_coordinates_typed_as_prose_keep_every_character(self):
        text = "Meet at 50.6900, -1.2500"
        data = encode_words(text, 1)
        self.assertEqual(decode_words(data, 1), text)
        self.assertEqual(data, ctl(CAP) + tok("meet") + tok("at") + tok("50") + lit(".6900, -1.2500"))

    def test_no_near_matches(self):
        for text in ("recieve the hepl", "Teh flod", "Aylin", "Müller", "DL1ABC", "Apt 4B"):
            self.assertEqual(decode_words(encode_words(text, 1), 1), text)
        self.assertNotIn("help", decode_words(encode_words("hepl", 1), 1))


class RoundTrip(unittest.TestCase):
    CASES = [
        "Hi, are you still at the station? I will be there in ten minutes.",
        "Team 3 reached Löwenburg hut. Missing hiker found at 50.6921, 7.2134, conscious, needs a stretcher.",
        "The river runs\n  under the bridge,\n\nand we wait   for morning.\n",
        "Zyxomorph flibberjab quantimeter",
        "Wir sind am Parkplatz, the road is blocked, bitte langsam fahren.",
        "Wait... what?! (Really) [yes] {ok}; done: \"fine\" - right?",
        "All safe 🙂👍 see you soon ❤️",
        "NEED WATER AND FOOD NOW",
        "line one\r\nline two\tend",
        "  hello there  ",
        " ", "\n", ".", "…", "a", "I", "x" * 300,
    ]

    def test_cases(self):
        for text in self.CASES:
            self.assertEqual(decode_words(encode_words(text, 1), 1), text, text)

    def test_random_utf8(self):
        rnd = random.Random(20261125)
        ranges = [(0x20, 0x7E), (0x09, 0x0D), (0xA0, 0x7FF), (0x800, 0xD7FF), (0xE000, 0xFFFD), (0x10000, 0x10FFFF)]
        for _ in range(3000):
            chars = []
            for _ in range(rnd.randint(1, 60)):
                lo, hi = rnd.choice(ranges)
                chars.append(chr(rnd.randint(lo, hi)))
            text = "".join(chars)
            self.assertEqual(decode_words(encode_words(text, 1), 1), text)

    def test_random_dictionary_mix(self):
        rnd = random.Random(7)
        parts = list(D.entries[:400]) + [" ", "  ", "\n", "", ",", ".", "Ä", "🙂", "X", "42"]
        for _ in range(3000):
            text = "".join(rnd.choice(parts) + rnd.choice(["", " ", " ", " "]) for _ in range(rnd.randint(1, 20)))
            if rnd.random() < 0.3:
                text = text.upper() if rnd.random() < 0.5 else text.title()
            if text:
                self.assertEqual(decode_words(encode_words(text, 1), 1), text)

    def test_shortest_stream(self):
        # The encoder may choose a literal over a token when that is shorter: "x and y" in one literal (2+7 bytes)
        # beats literal "x " + [and] + literal " y" (2+2+2+2+2 = 10 bytes).
        self.assertEqual(encode_words("x and y", 1), lit("x and y"))


class Errors(unittest.TestCase):
    def check(self, data: bytes, code: str = "malformed"):
        with self.assertRaises(WordsError) as ctx:
            decode_words(data, 1)
        self.assertEqual(ctx.exception.code, code)

    def test_malformed_streams(self):
        self.check(b"")                                          # empty
        self.check(tok("safe")[:1])                              # half a token
        self.check(lit("Aylin")[:-1])                            # literal cut off
        self.check(ctl(LITERAL))                                 # literal of 0 bytes
        self.check(ctl(0xF004) + tok("safe"))                    # undefined control
        self.check(ctl(0xF000))
        self.check(len(D.entries).to_bytes(2, "big"))            # token past the end of the dictionary
        self.check(tok("safe") + ctl(CAP))                       # control at the end
        self.check(ctl(CAP) + lit("x"))                          # control before a literal
        self.check(ctl(CAP) + ctl(UPPER) + tok("safe"))          # two case controls
        self.check(ctl(GLUE) + ctl(GLUE) + tok("safe"))
        self.check(ctl(LITERAL + 2) + b"\xc3\x28")               # not UTF-8

    def test_too_large(self):
        self.check(lit("x" * 1000) + lit("x" * 25), "too_large")
        with self.assertRaises(WordsError):
            encode_words("x" * 1025, 1)


class Codec(unittest.TestCase):
    def test_encode_decode_and_render(self):
        msg = {"id": 3, "text": {"text": "We found Aylin Demir", "words": 1}}
        data = encode(msg)
        self.assertEqual(decode(data), msg)
        self.assertEqual(render(decode(data), "en"), "Message: We found Aylin Demir.")

    def test_gps_stays_in_the_position_field(self):
        msg = {"id": 9, "priority": "urgent", "pos": POS, "text": {"text": "Found him, conscious, needs a stretcher", "words": 1}}
        env = pb.Envelope()
        env.ParseFromString(encode(msg))
        self.assertEqual((env.pos.lat, env.pos.lon), (5069212, 721345))
        self.assertNotIn("50.69", decode(encode(msg))["text"]["text"])
        self.assertEqual(decode(encode(msg))["pos"], POS)

    def test_empty_text(self):
        with self.assertRaises(CodecError) as ctx:
            encode({"text": {"text": "", "words": 1}})
        self.assertEqual((ctx.exception.code, ctx.exception.field), ("missing_field", "text.text"))

    def test_rules(self):
        for body, code, field in (({"text": "x", "words": 1, "dict": 1}, "invalid_value", "text.words"),
                                  ({"text": "x", "words": 9}, "invalid_value", "text.words"),
                                  ({"text": "x" * 1025, "words": 1}, "invalid_value", "text.text")):
            with self.assertRaises(CodecError) as ctx:
                encode({"text": body})
            self.assertEqual((ctx.exception.code, ctx.exception.field), (code, field), body)

    def wire(self, **text) -> bytes:
        env = pb.Envelope(id=7)
        for k, v in text.items():
            setattr(env.text, k, v)
        return env.SerializeToString(deterministic=True)

    def test_wire_rules(self):
        words = encode_words("is safe", 1)
        for fields, code, field in (({"words": words}, "missing_field", "text.words_dict"),
                                    ({"words_dict": 1, "text": "x"}, "invalid_value", "text.words_dict"),
                                    ({"words": words, "words_dict": 1, "text": "x"}, "invalid_value", "text.words"),
                                    ({"words": words, "words_dict": 1, "dict": 1}, "invalid_value", "text.dict"),
                                    ({"words": words[:-1], "words_dict": 1}, "malformed", "text.words")):
            with self.assertRaises(CodecError) as ctx:
                decode(self.wire(**fields))
            self.assertEqual((ctx.exception.code, ctx.exception.field), (code, field), fields)

    def test_unknown_dictionary_is_visible_not_guessed(self):
        msg = decode(self.wire(words=encode_words("is safe", 1), words_dict=9))
        self.assertEqual(msg, {"id": 7, "unknown": {"words": 9}})
        self.assertIn("dictionary this app does not have", render(msg, "en"))
        self.assertIn("Wörterbuch", render(msg, "de"))
        self.assertNotIn("safe", render(msg, "en"))

    def test_old_reader_rejects_and_never_shows_wrong_text(self):
        """A 2.0 reader does not know fields 4 and 5: it sees a Text with an empty `text` and rejects the packet."""
        env = pb.Envelope()
        env.ParseFromString(encode({"id": 3, "text": {"text": "We found Aylin Demir", "words": 1}}))
        env.text.ClearField("words")                     # what a reader without fields 4 and 5 is left with
        env.text.ClearField("words_dict")
        with self.assertRaises(CodecError) as ctx:
            validate(env)
        self.assertEqual((ctx.exception.code, ctx.exception.field), ("missing_field", "text.text"))

    def test_existing_packets_still_decode(self):
        hut = "We are at the hut, two people with us, waiting for the vehicle on the forest road"
        self.assertEqual(decode(encode({"id": 3, "text": {"text": hut, "dict": 1}}))["text"], {"text": hut, "dict": 1})
        self.assertEqual(decode(encode({"id": 3, "text": {"text": "Ok"}}))["text"], {"text": "Ok"})
        self.assertIn("found", decode(encode({"id": 7, "pos": POS, "found": {"age": "child"}})))


def old_choose(message, want_packed):
    """The send rule before compact texts (copied from 2.0), for the "setting off changes nothing" check."""
    from rescuecodec.engine.extract import fit_plain
    words = message["text"]["text"]
    plain = fit_plain(words).encode("utf-8")
    try:
        packed = encode({**{k: v for k, v in message.items() if k != "text"}, "text": {"text": words, "dict": 1}})
    except CodecError:
        packed = None
    if want_packed and packed is not None and len(packed) <= len(plain) - MIN_SAVING_BYTES:
        return packed
    return plain


class SendRule(unittest.TestCase):
    TEXTS = RoundTrip.CASES[:10] + ["Ok", "Hilfe", "We are at the hut, two people with us", "Bin unterwegs" * 10]

    def test_setting_off_is_the_old_rule(self):
        for text in self.TEXTS:
            for msg in ({"id": 3, "text": {"text": text}}, {"id": 300, "pos": POS, "text": {"text": text}}):
                for want in (True, False):
                    if len(text.encode()) <= C.MAX_TEXT_BYTES:
                        self.assertEqual(choose_form(msg, want, "x").payload, old_choose(msg, want), (text, want))

    def test_compact_sends_the_strictly_smallest(self):
        for text in self.TEXTS:
            msg = {"id": 3, "text": {"text": text}}
            off = choose_form(msg, True, "rescuecodec_only")
            on = choose_form(msg, True, "rescuecodec_only", compact=True)
            self.assertLessEqual(len(on.payload), len(off.payload))
            sizes = [s for s in (on.lean_size, on.words_size, on.packed_size) if s is not None]
            if on.form == "plain":
                self.assertTrue(all(len(on.payload) <= s for s in sizes), text)       # ties keep the normal text
            else:
                self.assertEqual(len(on.payload), min(sizes))
                self.assertLess(len(on.payload), on.plain_size)
                self.assertEqual(decode_any(on.payload)["text"]["text"], text)

    def test_never_compact_where_the_chat_does_not_pack(self):
        msg = {"id": 3, "text": {"text": RoundTrip.CASES[0]}}
        form = choose_form(msg, False, "public_channel", compact=True)
        self.assertEqual((form.form, form.packed, form.words_size), ("plain", False, None))

    def test_compact_example(self):
        text = "We are at the hut, two people with us, waiting for the vehicle on the forest road"
        form = choose_form({"id": 3, "text": {"text": text}}, True, "rescuecodec_only", compact=True)
        self.assertEqual(form.form, "lean")
        self.assertEqual(form.payload[:2], b"\x02\x02")
        self.assertLess(form.lean_size, form.words_size)
        self.assertLess(form.words_size, form.packed_size)
        self.assertLess(form.lean_size, form.plain_size)

    def test_long_text_on_a_packing_chat(self):
        text = ("Sector 4 fully searched, nothing found. Moving to sector 5 along the river, two people, "
                "radio check every 30 minutes. ") * 3                       # about 330 bytes
        self.assertGreater(len(text.encode()), C.MAX_TEXT_BYTES)
        form = choose_form({"id": 3, "text": {"text": text}}, True, "x", compact=True)
        self.assertTrue(form.packed)
        self.assertFalse(form.cut)
        self.assertEqual(decode_any(form.payload)["text"]["text"], text)

    def test_too_long_is_cut_to_fit_one_packet(self):
        rnd = random.Random(3)
        text = "".join(rnd.choice("qwrtzpsdfghjklxcvbnm ") for _ in range(380))    # does not compress
        form = choose_form({"id": 3, "text": {"text": text}}, True, "x", compact=True)
        self.assertTrue(form.cut)
        self.assertTrue(form.text.endswith("…"))
        self.assertTrue(text.startswith(form.text[:-1]))
        self.assertLessEqual(len(form.payload), C.MAX_ENCODE_BYTES)
        over = "Sector 4 searched. " * 30                                           # 570 bytes, over 400
        cut = fit_packed(over, lambda w: True)
        self.assertLessEqual(len(cut.encode()), C.MAX_PACKED_TEXT_BYTES)
        self.assertTrue(cut.endswith("…"))


class Vectors(unittest.TestCase):
    """spec/vectors/codec.json, the compact sections (what Kotlin and TypeScript run too)."""

    @classmethod
    def setUpClass(cls):
        import json
        cls.v = json.loads((textwords._spec_dir() / "vectors" / "codec.json").read_text(encoding="utf-8"))

    def test_compact_text(self):
        for case in self.v["compact_text"]:
            with self.subTest(case["name"]):
                self.assertEqual(encode(case["input"]).hex(), case["hex"])
                self.assertEqual(decode(bytes.fromhex(case["hex"])), case["decoded"])
                self.assertEqual(encode_words(case["text"], 1).hex(), case["words"])
                self.assertEqual(decode_words(bytes.fromhex(case["words"]), 1), case["text"])
                self.assertEqual(render(case["decoded"], "en"), case["render"]["en"])
                self.assertEqual(render(case["decoded"], "de"), case["render"]["de"])

    def test_streams(self):
        for case in self.v["word_streams"]:
            self.assertEqual(encode_words(case["text"], 1).hex(), case["words"], case["name"])
            self.assertEqual(decode_words(bytes.fromhex(case["words"]), 1), case["text"], case["name"])
        for case in self.v["word_streams_invalid"]:
            with self.assertRaises(WordsError) as ctx:
                decode_words(bytes.fromhex(case["words"]), 1)
            self.assertEqual(ctx.exception.code, case["error"], case["name"])

    def test_dictionary_hash(self):
        for number, info in self.v["word_dicts"].items():
            data = (textwords._spec_dir() / info["file"]).read_bytes()
            self.assertEqual(hashlib.sha256(data).hexdigest(), info["sha256"])
            self.assertEqual(len(textwords.dictionary(int(number)).entries), info["entries"])


if __name__ == "__main__":
    unittest.main()
