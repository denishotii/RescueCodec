"""Gateway: domain frames (SPEC section 3.8) with --vocabulary, the health fields, previews and receiving."""
import unittest

from gateway_fixtures import gateway, wait_for
from rescuecodec.gateway.cli import build_parser, config_from_args
from rescuecodec.gateway.config import GatewayConfig
from rescuecodec.gateway.radio.base import PORT_PRIVATE_APP
from rescuecodec.v2 import decode_any, encode_domain

MEDICAL = "Patient is conscious, bleeding from the leg, apply a tourniquet."
SAR = "Missing person found at 50.69212, 7.21345."


def packing(gw, mode: str = "compressed") -> None:
    r = gw.client.put("/v2/channels/RescueCodec/mode", json={"mode": mode})
    assert r.status_code == 200, r.text


class Config(unittest.TestCase):
    def test_flag(self):
        self.assertEqual(config_from_args(build_parser().parse_args(["--emulator"])).vocabulary, "auto")
        args = build_parser().parse_args(["--emulator", "--vocabulary", "sar"])
        self.assertEqual(config_from_args(args).vocabulary, "sar")
        with self.assertRaises(SystemExit):
            build_parser().parse_args(["--emulator", "--vocabulary", "sars"])
        with self.assertRaises(ValueError):
            GatewayConfig(emulator=True, vocabulary="SAR").validate()

    def test_health_shows_the_setting_and_every_vocabulary(self):
        with gateway() as gw:
            body = gw.client.get("/v2/health").json()
            self.assertEqual(body["vocabulary"], "auto")
            self.assertEqual([(v["id"], v["domain"], v["short"], v["bits"], v["available"]) for v in body["vocabularies"]],
                             [(1, "emergency", "Emergency", 10, True), (2, "sar", "S&R", 10, True),
                              (3, "fire", "Fire", 10, True), (4, "hiking", "Hiking", 10, True),
                              (5, "medical", "Medical", 10, True)])
        with gateway(compact_text=True, vocabulary="medical") as gw:
            self.assertEqual(gw.client.get("/v2/health").json()["vocabulary"], "medical")


class Send(unittest.TestCase):
    def test_auto_sends_the_smallest_domain_frame(self):
        with gateway(compact_text=True) as gw:
            packing(gw)
            record = gw.post("/v2/text", text=MEDICAL).json()
            codec = record["codec"]
            self.assertEqual((codec["form"], codec["vocab"], codec["vocab_id"], codec["vocab_label"], codec["size"]),
                             ("domain", "medical", 5, "Medical", 19))
            self.assertTrue(codec["packed"])
            self.assertEqual(record["message"]["text"], {"text": MEDICAL, "vocab": 5})
            self.assertEqual(record["text"]["en"], f"Message: {MEDICAL}")
            self.assertIsNone(record["key"])                          # no id: the packet id finds repeats
            sent = wait_for(lambda: gw.radio.sent)[0]
            self.assertEqual((sent["port"], sent["payload"][:2]), (PORT_PRIVATE_APP, b"\x03\x05"))
            self.assertEqual(decode_any(sent["payload"])["text"]["text"], MEDICAL)
            self.assertEqual(len(sent["payload"]), 19)

    def test_pinned_and_general(self):
        with gateway(compact_text=True, vocabulary="sar") as gw:
            packing(gw)
            self.assertEqual(gw.post("/v2/text", text=MEDICAL).json()["codec"]["form"], "lean")   # medical not tried
            record = gw.post("/v2/text", text=SAR).json()
            self.assertEqual((record["codec"]["form"], record["codec"]["vocab"]), ("domain", "sar"))
        with gateway(compact_text=True, vocabulary="general") as gw:
            packing(gw)
            self.assertEqual(gw.post("/v2/text", text=MEDICAL).json()["codec"]["form"], "lean")

    def test_compact_off_or_a_chat_that_does_not_pack_never_sends_one(self):
        with gateway() as gw:
            packing(gw)
            self.assertEqual(gw.post("/v2/text", text=MEDICAL).json()["codec"]["form"], "deflate")
        with gateway(compact_text=True) as gw:
            packing(gw, "normal")
            self.assertEqual(gw.post("/v2/text", text=MEDICAL).json()["codec"]["version"], 0)

    def test_a_vocab_from_the_client_is_ignored(self):
        with gateway(compact_text=True, vocabulary="general") as gw:
            packing(gw)
            record = gw.post("/v2/messages", message={"text": {"text": MEDICAL, "vocab": 5}}).json()
            self.assertEqual(record["codec"]["form"], "lean")

    def test_dry_run_and_extract_preview_report_form_and_vocabulary(self):
        with gateway(compact_text=True) as gw:
            packing(gw)
            dry = gw.post("/v2/messages", message={"text": {"text": MEDICAL}}, dry_run=True).json()
            self.assertEqual((dry["status"], dry["codec"]["form"], dry["codec"]["vocab"]), ("dry_run", "domain", "medical"))
            body = gw.post("/v2/extract", text=MEDICAL, channel="RescueCodec").json()
            self.assertEqual(body["type"], "found")                  # a report: no text form
            body = gw.post("/v2/extract", text="Check airway and breathing.", channel="RescueCodec").json()
            self.assertEqual((body["type"], body["form"], body["vocab"], body["vocab_label"], body["coded_bytes"]),
                             ("text", "domain", "medical", "Medical", 9))
            record = gw.post("/v2/text", text="Check airway and breathing.").json()
            self.assertEqual((record["codec"]["size"], record["codec"]["vocab"]), (9, "medical"))
            body = gw.post("/v2/extract", text="hello world", channel="RescueCodec").json()
            self.assertEqual((body["form"], body["vocab"], body["vocab_label"]), ("lean", None, None))


class Receive(unittest.TestCase):
    """Receiving works whatever --compact-text and --vocabulary say: every vocabulary is bundled."""

    def test_a_domain_frame_is_shown_as_its_words_and_repeats_go_by_packet_id(self):
        data = encode_domain(SAR, "sar")
        with gateway(vocabulary="general") as gw:
            gw.radio.inject("!0000e002", data, channel=0, packet_id=77)
            gw.radio.inject("!0000e002", data, channel=0, packet_id=77)       # the mesh delivered it twice
            gw.radio.inject("!0000e002", data, channel=0, packet_id=78)       # the same words sent again
            items = wait_for(lambda: len(gw.client.get("/v2/messages").json()["items"]) >= 2
                             and gw.client.get("/v2/messages").json()["items"])
            self.assertEqual(len(items), 2)
            item = items[0]
            self.assertEqual((item["type"], item["codec"]["form"], item["codec"]["vocab"], item["codec"]["vocab_label"]),
                             ("text", "domain", "sar", "S&R"))
            self.assertEqual(item["message"]["text"], {"text": SAR, "vocab": 2})
            self.assertEqual(item["text"]["en"], f"Message: {SAR}")
            self.assertEqual(gw.service._counters["duplicates"], 1)

    def test_an_unknown_vocabulary_is_visible(self):
        with gateway() as gw:
            gw.radio.inject("!0000e002", bytes.fromhex("0309fe41f1f43900b79000"), channel=0)
            item = wait_for(lambda: gw.client.get("/v2/messages").json()["items"])[0]
            self.assertEqual((item["type"], item["message"]["unknown"]), ("unknown", {"vocab": 9}))
            self.assertEqual((item["codec"]["form"], item["codec"]["vocab"], item["codec"]["vocab_id"]),
                             ("domain", None, 9))
            self.assertIn("unsupported vocabulary 9", item["text"]["en"])

    def test_a_broken_domain_frame_is_rejected(self):
        data = encode_domain(SAR, "sar")
        with gateway() as gw:
            gw.radio.inject("!0000e002", data[:-4], channel=0)                # cut inside the literal
            gw.radio.inject("!0000e002", bytes.fromhex("0305ffc0"), channel=0)   # reserved code
            gw.hear({"id": 8, "text": {"text": "after"}})
            items = wait_for(lambda: gw.client.get("/v2/messages").json()["items"])
            self.assertEqual([i["message"]["text"]["text"] for i in items], ["after"])
            self.assertEqual(gw.service._counters["rejected"], 2)

    def test_decode_endpoint(self):
        with gateway() as gw:
            body = gw.post("/v2/decode", hex=encode_domain("Apply a tourniquet.", "medical").hex()).json()
            self.assertEqual((body["v"], body["size"], body["message"]), (2, 7, {"text": {"text": "Apply a tourniquet.",
                                                                                         "vocab": 5}}))
            self.assertEqual(gw.post("/v2/decode", hex="0305ffc0").json()["error"]["code"], "malformed")


if __name__ == "__main__":
    unittest.main()
