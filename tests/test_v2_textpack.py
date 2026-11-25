"""Packed texts: raw DEFLATE with the shared dictionary (SPEC section 3.5), and the send rule (section 2.3)."""
import hashlib
import unittest
import zlib

from rescuecodec.engine.textmode import MIN_SAVING_BYTES, choose_form, wants_packed
from rescuecodec.v2 import CodecError, decode, encode, render
from rescuecodec.v2 import textpack

HUT = "We are at the hut, two people with us, waiting for the vehicle on the forest road"


class Pack(unittest.TestCase):
    def test_round_trip_and_size(self):
        data = textpack.pack(HUT)
        self.assertEqual(textpack.unpack(data, 1), HUT)
        self.assertEqual((len(HUT), len(data)), (81, 34))          # the number in SPEC section 3.5

    def test_the_dictionary_is_what_makes_it_small(self):
        comp = zlib.compressobj(9, zlib.DEFLATED, -15)
        without = comp.compress(HUT.encode()) + comp.flush()
        self.assertGreater(len(without), 60)
        self.assertLess(len(textpack.pack(HUT)), 40)

    def test_umlauts_and_other_scripts(self):
        for text in ("Brücke über den Bach ist gesperrt", "Допомога потрібна", "🙂 ok"):
            self.assertEqual(textpack.unpack(textpack.pack(text), 1), text)

    def test_dictionary_file_is_frozen(self):
        data = textpack.dictionary(1)
        self.assertEqual(hashlib.sha256(data).hexdigest(), textpack.DICTS[1][1])
        self.assertLess(len(data), 16 * 1024)
        data.decode("utf-8")                                          # plain UTF-8 text

    def test_unpacking_stops_at_1024_bytes(self):
        comp = zlib.compressobj(9, zlib.DEFLATED, -15)
        bomb = comp.compress(b"A" * 1_000_000) + comp.flush()         # 1 MB in about 1 KB
        with self.assertRaises(textpack.PackError) as ctx:
            textpack.unpack(bomb, 1)
        self.assertEqual(ctx.exception.code, "too_large")


class Codec(unittest.TestCase):
    def test_encode_and_decode_a_packed_text(self):
        msg = {"id": 3, "text": {"text": HUT, "dict": 1}}
        data = encode(msg)
        self.assertEqual(len(data), 42)
        self.assertEqual(decode(data), msg)
        self.assertLess(len(data), len(encode({"id": 3, "text": {"text": HUT}})))

    def test_readable_line_shows_the_words(self):
        decoded = decode(encode({"text": {"text": "Bin unterwegs", "dict": 1}}))
        self.assertEqual(render(decoded, "de"), "Nachricht: Bin unterwegs.")

    def test_a_packed_text_may_be_longer_than_180_bytes(self):
        words = "Sektor 4 komplett abgesucht, nichts gefunden. " * 6
        self.assertGreater(len(words.encode()), 180)
        self.assertEqual(decode(encode({"text": {"text": words, "dict": 1}}))["text"]["text"], words)
        with self.assertRaises(CodecError):
            encode({"text": {"text": words}})


class Rule(unittest.TestCase):
    def test_wants_packed(self):
        self.assertEqual(wants_packed("compressed", public=True, direct=False), (False, "public_channel"))
        self.assertEqual(wants_packed("normal", public=False, direct=False), (False, "mode_normal"))
        self.assertEqual(wants_packed("compressed", public=False, direct=False, meshtastic_users=3), (True, "mode_compressed"))
        self.assertEqual(wants_packed("auto", public=False, direct=True, peer_rescuecodec=True), (True, "peer_rescuecodec"))
        self.assertEqual(wants_packed("auto", public=False, direct=True), (False, "peer_unknown"))
        self.assertEqual(wants_packed("auto", public=False, direct=False, meshtastic_users=1), (False, "meshtastic_users"))
        self.assertEqual(wants_packed("auto", public=False, direct=False, rescuecodec_nodes=1), (True, "rescuecodec_only"))
        self.assertEqual(wants_packed("auto", public=False, direct=False), (False, "nobody_known"))
        self.assertEqual(wants_packed("auto", public=False, direct=False, meshtastic_users=1, rescuecodec_nodes=2), (False, "meshtastic_users"))

    def test_packed_when_it_saves_three_bytes(self):
        form = choose_form({"id": 3, "text": {"text": HUT}}, True, "rescuecodec_only")
        self.assertTrue(form.packed)
        self.assertEqual((form.plain_size, form.packed_size), (81, 42))
        self.assertEqual(form.message["text"], {"text": HUT, "dict": 1})

    def test_short_text_stays_normal(self):
        form = choose_form({"id": 3, "text": {"text": "Ok"}}, True, "rescuecodec_only")
        self.assertFalse(form.packed)
        self.assertEqual((form.payload, form.reason), (b"Ok", "no_saving"))

    def test_position_counts_against_the_saving(self):
        words = "Bin unterwegs, Ankunft in ca. 10 Minuten"
        pos = {"lat": 50.69212, "lon": 7.21345}
        without = choose_form({"id": 3, "text": {"text": words}}, True)
        with_pos = choose_form({"id": 3, "pos": pos, "text": {"text": words}}, True)
        self.assertTrue(without.packed)
        self.assertEqual(with_pos.packed, with_pos.packed_size <= with_pos.plain_size - MIN_SAVING_BYTES)
        self.assertEqual(with_pos.packed_size - without.packed_size, 11)     # the position costs 11 bytes

    def test_normal_text_drops_id_and_position_but_keeps_priority(self):
        form = choose_form({"id": 3, "priority": "urgent", "pos": {"lat": 1.0, "lon": 2.0}, "text": {"text": HUT}}, False,
                           "mode_normal")
        self.assertEqual(form.message, {"priority": "urgent", "text": {"text": HUT}})
        self.assertEqual(form.payload, HUT.encode())


if __name__ == "__main__":
    unittest.main()
