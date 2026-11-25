"""Gateway: compact texts (SPEC section 3.6) with --compact-text, the text limits, and receiving compact texts."""
import random
import unittest

from gateway_fixtures import gateway, wait_for
from rescuecodec.gateway.radio.base import PORT_PRIVATE_APP, PORT_TEXT_MESSAGE
from rescuecodec.v2 import decode, decode_any, encode, encode_compact
from rescuecodec.v2 import rescuecodec_pb2 as pb
from rescuecodec.v2.textwords import encode_words

HUT = "We are at the hut, two people with us, waiting for the vehicle on the forest road"
POS = {"lat": 50.69212, "lon": 7.21345}


def packing(gw, mode: str = "compressed") -> None:
    r = gw.client.put("/v2/channels/RescueCodec/mode", json={"mode": mode})
    assert r.status_code == 200, r.text


class Send(unittest.TestCase):
    def test_health_shows_the_flag_and_the_limits(self):
        with gateway() as gw:
            body = gw.client.get("/v2/health").json()
            self.assertEqual((body["compact_text"], body["text_limits"]), (False, {"text": 180, "packed": 400}))
        with gateway(compact_text=True) as gw:
            self.assertTrue(gw.client.get("/v2/health").json()["compact_text"])

    def test_off_keeps_deflate(self):
        with gateway() as gw:
            packing(gw)
            record = gw.post("/v2/text", text=HUT).json()
            self.assertEqual((record["codec"]["form"], record["message"]["text"]), ("deflate", {"text": HUT, "dict": 1}))

    def test_on_sends_a_compact_frame_and_the_bytes_on_air_decode_exactly(self):
        with gateway(compact_text=True) as gw:
            packing(gw)
            record = gw.post("/v2/text", text=HUT).json()
            self.assertEqual(record["codec"]["form"], "lean")
            self.assertEqual(record["message"]["text"], {"text": HUT, "words": 2})
            self.assertIsNone(record["key"])                          # no id: the packet id finds repeats
            sent = wait_for(lambda: gw.radio.sent)[0]
            self.assertEqual(sent["port"], PORT_PRIVATE_APP)
            self.assertEqual(sent["payload"][:2], b"\x02\x02")
            self.assertEqual(decode_any(sent["payload"])["text"]["text"], HUT)
            self.assertEqual(len(sent["payload"]), record["codec"]["size"])
            self.assertLess(len(sent["payload"]), len(HUT.encode()) // 2)

    def test_hello_world_is_six_bytes_on_air(self):
        with gateway(compact_text=True) as gw:
            packing(gw)
            record = gw.post("/v2/text", text="hello world").json()
            self.assertEqual((record["codec"]["form"], record["codec"]["size"]), ("lean", 6))
            sent = wait_for(lambda: gw.radio.sent)[0]
            self.assertEqual((sent["port"], len(sent["payload"])), (PORT_PRIVATE_APP, 6))
            record = gw.post("/v2/text", text="Ok").json()           # 2 bytes as a normal text: smaller, stays plain
            self.assertEqual(record["codec"]["version"], 0)

    def test_on_never_goes_compact_where_the_chat_does_not_pack(self):
        with gateway(compact_text=True) as gw:
            packing(gw, "normal")
            record = gw.post("/v2/text", text=HUT).json()
            self.assertEqual(record["codec"]["version"], 0)
            self.assertEqual(wait_for(lambda: gw.radio.sent)[0]["port"], PORT_TEXT_MESSAGE)

    def test_limits(self):
        with gateway(compact_text=True) as gw:
            packing(gw, "normal")
            r = gw.post("/v2/text", text="x" * 181)
            self.assertEqual((r.status_code, r.json()["error"]["field"]), (400, "text.text"))
            packing(gw)
            long = ("Sector 4 fully searched, nothing found. Moving to sector 5 along the river, two people, "
                    "radio check every 30 minutes. ") * 3
            record = gw.post("/v2/text", text=long.strip()).json()
            self.assertEqual(record["message"]["text"]["text"], long.strip())      # 330 bytes, whole
            self.assertEqual(gw.post("/v2/text", text="x" * 401).status_code, 400)

    def test_too_long_for_one_packet_is_cut(self):
        rnd = random.Random(3)
        text = "".join(rnd.choice("qwrtzpsdfghjklxcvbnm ") for _ in range(380))
        with gateway(compact_text=True) as gw:
            packing(gw)
            record = gw.post("/v2/text", text=text).json()
            words = record["message"]["text"]["text"]
            self.assertTrue(words.endswith("…") and text.startswith(words[:-1]))
            self.assertLessEqual(record["codec"]["size"], 200)

    def test_gps_goes_in_the_position_field_and_on_the_map(self):
        with gateway(compact_text=True) as gw:
            packing(gw)
            record = gw.post("/v2/messages", message={"pos": POS, "text": {"text": HUT}}).json()
            self.assertEqual(record["codec"]["form"], "words")          # a position keeps the Envelope
            env = pb.Envelope()
            env.ParseFromString(bytes.fromhex(record["codec"]["hex"]))
            self.assertEqual((env.pos.lat, env.pos.lon), (5069212, 721345))
            self.assertEqual(record["message"]["pos"], POS)
            features = gw.client.get("/v2/export.geojson").json()["features"]
            self.assertEqual(features[0]["geometry"]["coordinates"], [POS["lon"], POS["lat"]])

    def test_extract_preview_sizes_the_text_for_the_chat(self):
        with gateway(compact_text=True) as gw:
            packing(gw)
            body = gw.post("/v2/extract", text=HUT, channel="RescueCodec").json()
            self.assertEqual((body["send_as"], body["max_text_bytes"], body["form"], body["cut"]),
                             ("compressed", 400, "lean", False))
            record = gw.post("/v2/text", text=HUT).json()
            self.assertEqual(body["coded_bytes"], record["codec"]["size"])
            packing(gw, "normal")
            body = gw.post("/v2/extract", text=HUT, channel="RescueCodec").json()
            self.assertEqual((body["max_text_bytes"], body["form"]), (180, "plain"))


class Receive(unittest.TestCase):
    """Receiving works whatever --compact-text says."""

    def test_a_compact_text_is_shown_as_its_words(self):
        text = "We found Aylin Demir.\nMeet at 50.6900, -1.2500 🙂"
        with gateway() as gw:
            gw.hear({"id": 5, "text": {"text": text, "words": 1}})
            item = wait_for(lambda: gw.client.get("/v2/messages").json()["items"])[0]
            self.assertEqual((item["type"], item["codec"]["form"]), ("text", "words"))
            self.assertEqual(item["message"]["text"]["text"], text)
            self.assertEqual(item["text"]["en"], f"Message: {text}.")      # the readable line ends with "."

    def test_an_unknown_word_dictionary_is_visible(self):
        env = pb.Envelope(id=6)
        env.text.words = encode_words("is safe", 1)
        env.text.words_dict = 9
        with gateway() as gw:
            gw.radio.inject("!0000e002", env.SerializeToString())
            item = wait_for(lambda: gw.client.get("/v2/messages").json()["items"])[0]
            self.assertEqual(item["type"], "unknown")
            self.assertEqual(item["message"]["unknown"], {"words": 9})
            self.assertIn("dictionary this app does not have", item["text"]["en"])
            self.assertNotIn("safe", item["text"]["en"])

    def test_a_compact_frame_is_received_and_repeats_go_by_packet_id(self):
        text = "We found Aylin Demir.\nMeet at 50.6900, -1.2500 🙂"
        data = encode_compact(text)
        with gateway() as gw:                                         # receiving works with the setting off
            gw.radio.inject("!0000e002", data, channel=0, packet_id=77)
            gw.radio.inject("!0000e002", data, channel=0, packet_id=77)       # the mesh delivered it twice
            gw.radio.inject("!0000e002", data, channel=0, packet_id=78)       # the same words sent again
            items = wait_for(lambda: len(gw.client.get("/v2/messages").json()["items"]) >= 2
                             and gw.client.get("/v2/messages").json()["items"])
            self.assertEqual(len(items), 2)
            item = items[0]
            self.assertEqual((item["type"], item["codec"]["form"], item["codec"]["size"]), ("text", "lean", len(data)))
            self.assertEqual(item["message"]["text"], {"text": text, "words": 2})
            self.assertEqual(item["text"]["en"], f"Message: {text}.")
            self.assertEqual(gw.service._counters["duplicates"], 1)

    def test_a_compact_frame_with_an_unknown_dictionary_is_visible(self):
        with gateway() as gw:
            gw.radio.inject("!0000e002", bytes.fromhex("020900100011"), channel=0)
            item = wait_for(lambda: gw.client.get("/v2/messages").json()["items"])[0]
            self.assertEqual((item["type"], item["message"]["unknown"]), ("unknown", {"words": 9}))
            self.assertIn("dictionary this app does not have", item["text"]["en"])

    def test_a_truncated_compact_frame_is_rejected(self):
        data = encode_compact("We found Aylin Demir")
        with gateway() as gw:
            gw.radio.inject("!0000e002", data[:-3], channel=0)
            gw.hear({"id": 8, "text": {"text": "after"}})
            items = wait_for(lambda: gw.client.get("/v2/messages").json()["items"])
            self.assertEqual([i["message"]["text"]["text"] for i in items], ["after"])
            self.assertEqual(gw.service._counters["rejected"], 1)

    def test_a_truncated_compact_packet_is_rejected(self):
        data = encode({"id": 7, "text": {"text": "We found Aylin Demir", "words": 1}})
        with gateway() as gw:
            gw.radio.inject("!0000e002", data[:-3])
            gw.hear({"id": 8, "text": {"text": "after"}})
            items = wait_for(lambda: gw.client.get("/v2/messages").json()["items"])
            self.assertEqual([i["message"]["text"]["text"] for i in items], ["after"])
            self.assertEqual(gw.service._counters["rejected"], 1)


if __name__ == "__main__":
    unittest.main()
