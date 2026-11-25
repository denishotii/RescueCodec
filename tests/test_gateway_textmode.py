"""Packed texts in the gateway: who runs RescueCodec, the text mode per chat, and the send rule (SPEC section 2.3)."""
import tempfile
import time
import unittest
from pathlib import Path

from gateway_fixtures import PEER, gateway, wait_for
from rescuecodec.engine.textmode import MEMORY_S
from rescuecodec.gateway.cli import build_parser, config_from_args
from rescuecodec.gateway.radio.base import PORT_PRIVATE_APP, PORT_TEXT_MESSAGE
from rescuecodec.gateway.store import Store
from rescuecodec.v2 import decode, encode
from test_gateway_channels import DRAEGER, LONGFAST, RESCUECODEC
from test_gateway_channels import gateway as base_gateway

HUT = "We are at the hut, two people with us, waiting for the vehicle on the forest road"
STOCK = "!0000e003"         # a node with the official Meshtastic app


def last_sent(gw):
    return wait_for(lambda: gw.radio.sent)[-1]


class Auto(unittest.TestCase):
    def test_a_channel_where_nobody_is_known_gets_normal_text(self):
        with gateway() as gw:
            mode = gw.client.get("/v2/channels/RescueCodec/mode").json()
            self.assertEqual((mode["send_as"], mode["reason"]), ("normal", "nobody_known"))
            record = gw.post("/v2/text", text=HUT).json()
            self.assertEqual(record["codec"]["version"], 0)
            self.assertEqual(last_sent(gw)["port"], PORT_TEXT_MESSAGE)

    def test_a_channel_with_only_rescuecodec_users_gets_packed_text(self):
        with gateway() as gw:
            gw.hear({"id": 1, "sector": {"sector": 3, "status": "cleared"}})       # a RescueCodec node on the channel
            wait_for(lambda: len(gw.client.get("/v2/messages").json()["items"]) == 1)
            record = gw.post("/v2/text", text=HUT).json()
            self.assertEqual((record["codec"]["version"], record["codec"]["packed"]), (2, True))
            self.assertEqual(record["codec"]["size"], 42)
            self.assertEqual(record["message"]["text"], {"text": HUT, "dict": 1})
            self.assertEqual(record["text"]["en"], f"Message: {HUT}.")
            sent = last_sent(gw)
            self.assertEqual(sent["port"], PORT_PRIVATE_APP)
            self.assertEqual(decode(sent["payload"])["text"]["text"], HUT)

    def test_a_meshtastic_user_on_the_channel_makes_it_normal_text(self):
        with gateway() as gw:
            gw.radio.inject_text(STOCK, "Hallo, ich hab nur die normale App")
            mode = gw.client.get("/v2/channels/RescueCodec/mode").json()
            self.assertEqual((mode["mode"], mode["send_as"], mode["reason"], mode["meshtastic_users"]),
                             ("auto", "normal", "meshtastic_users", [STOCK]))
            record = gw.post("/v2/text", text=HUT).json()
            self.assertEqual(record["codec"]["version"], 0)
            self.assertEqual(last_sent(gw)["port"], PORT_TEXT_MESSAGE)

    def test_a_node_that_sends_rescuecodec_is_no_meshtastic_user(self):
        with gateway() as gw:
            gw.radio.inject_text(PEER, "Hallo")                        # chat as a normal text first
            self.assertEqual(gw.client.get("/v2/channels/RescueCodec/mode").json()["send_as"], "normal")
            gw.hear({"id": 1, "sector": {"sector": 3, "status": "cleared"}})       # then a RescueCodec packet
            wait_for(lambda: len(gw.client.get("/v2/messages").json()["items"]) == 2)
            mode = gw.client.get("/v2/channels/RescueCodec/mode").json()
            self.assertEqual((mode["send_as"], mode["meshtastic_users"], mode["rescuecodec_nodes"]),
                             ("compressed", [], [PEER]))

    def test_short_texts_stay_normal(self):
        with gateway() as gw:
            record = gw.post("/v2/text", text="Ok").json()
            self.assertEqual(record["codec"]["version"], 0)

    def test_nodes_are_forgotten_after_24_hours(self):
        store = Store(":memory:")
        now = time.time()
        store.node_seen(STOCK, "RescueCodec", PORT_TEXT_MESSAGE, now - MEMORY_S - 60, MEMORY_S)
        self.assertEqual(store.nodes_on_port(PORT_TEXT_MESSAGE, now - MEMORY_S), {})
        store.node_seen(PEER, "RescueCodec", PORT_PRIVATE_APP, now, MEMORY_S)
        self.assertEqual(list(store.nodes_on_port(PORT_PRIVATE_APP, now - MEMORY_S)), [PEER])
        self.assertEqual(store._db.execute("SELECT count(*) FROM nodes_seen").fetchone()[0], 1)    # old row is gone


class DirectMessages(unittest.TestCase):
    def test_a_direct_message_to_a_rescuecodec_node_is_packed(self):
        with gateway() as gw:
            gw.hear({"id": 1, "sector": {"sector": 3, "status": "cleared"}})
            wait_for(lambda: gw.client.get("/v2/messages").json()["items"])
            self.assertEqual(gw.client.get(f"/v2/nodes/{PEER}/mode").json()["send_as"], "compressed")
            record = gw.post("/v2/text", text=HUT, to=PEER).json()
            self.assertEqual((record["codec"]["version"], record["to"]), (2, PEER))
            sent = last_sent(gw)
            self.assertEqual((sent["port"], sent["to"]), (PORT_PRIVATE_APP, PEER))
            nodes = {n["node"]: n for n in gw.client.get("/v2/nodes").json()["items"]}
            self.assertTrue(nodes[PEER]["rescuecodec"])

    def test_a_direct_message_to_anyone_else_is_normal_text(self):
        with gateway() as gw:
            record = gw.post("/v2/text", text=HUT, to=STOCK).json()
            self.assertEqual(record["codec"]["version"], 0)
            self.assertEqual((last_sent(gw)["port"], last_sent(gw)["to"]), (PORT_TEXT_MESSAGE, STOCK))


class Modes(unittest.TestCase):
    def test_set_and_read_a_channel_mode(self):
        with gateway() as gw:
            c = gw.client
            self.assertEqual(c.get("/v2/channels/RescueCodec/mode").json()["own_mode"], False)
            put = c.put("/v2/channels/rescuecodec/mode", json={"mode": "normal"})
            self.assertEqual(put.status_code, 200)
            self.assertEqual((put.json()["channel"], put.json()["mode"], put.json()["send_as"]),
                             ("RescueCodec", "normal", "normal"))
            self.assertEqual(gw.post("/v2/text", text=HUT).json()["codec"]["version"], 0)
            health = c.get("/v2/health").json()["radio"]["channels"][0]
            self.assertEqual((health["mode"], health["send_as"]), ("normal", "normal"))
            self.assertEqual(c.put("/v2/channels/RescueCodec/mode", json={"mode": None}).json()["mode"], "auto")
            self.assertEqual(c.put("/v2/channels/RescueCodec/mode", json={"mode": "fast"}).status_code, 400)
            self.assertEqual(c.put("/v2/channels/RescueCodec/mode", json={}).status_code, 400)
            self.assertEqual(c.get("/v2/channels/Nope/mode").status_code, 404)

    def test_always_compressed_beats_a_meshtastic_user(self):
        with gateway() as gw:
            gw.radio.inject_text(STOCK, "Hallo")
            gw.client.put("/v2/channels/RescueCodec/mode", json={"mode": "compressed"})
            self.assertEqual(gw.post("/v2/text", text=HUT).json()["codec"]["version"], 2)

    def test_direct_chat_mode(self):
        with gateway() as gw:
            put = gw.client.put(f"/v2/nodes/{STOCK}/mode", json={"mode": "compressed"}).json()
            self.assertEqual((put["node"], put["mode"], put["send_as"]), (STOCK, "compressed", "compressed"))
            self.assertEqual(gw.post("/v2/text", text=HUT, to=STOCK).json()["codec"]["version"], 2)
            self.assertEqual(gw.client.get("/v2/nodes/bad/mode").status_code, 400)

    def test_public_channels_always_get_normal_text(self):
        with base_gateway(show_public=True) as gw:
            gw.client.put("/v2/channels/LongFast/mode", json={"mode": "compressed"})
            mode = gw.client.get("/v2/channels/LongFast/mode").json()
            self.assertEqual((mode["send_as"], mode["reason"]), ("normal", "public_channel"))
            record = gw.post("/v2/text", text=HUT, channel="LongFast").json()
            self.assertEqual((record["codec"]["version"], record["channel"]), (0, LONGFAST))

    def test_each_channel_has_its_own_users(self):
        with base_gateway() as gw:
            gw.radio.inject_text(STOCK, "Hallo", channel=DRAEGER["index"])
            gw.radio.inject(PEER, encode({"id": 1, "sector": {"sector": 3, "status": "cleared"}}))  # RescueCodec on the team channel
            wait_for(lambda: len(gw.client.get("/v2/messages").json()["items"]) == 2)
            self.assertEqual(gw.client.get("/v2/channels/draeger-srs/mode").json()["send_as"], "normal")
            self.assertEqual(gw.client.get("/v2/channels/RescueCodec/mode").json()["send_as"], "compressed")
            self.assertEqual(gw.post("/v2/text", text=HUT).json()["channel"], RESCUECODEC)

    def test_modes_survive_a_restart(self):
        with tempfile.TemporaryDirectory() as tmp:
            db = str(Path(tmp) / "gw.db")
            with gateway(db=db) as gw:
                gw.client.put("/v2/channels/RescueCodec/mode", json={"mode": "normal"})
                gw.radio.inject_text(STOCK, "Hallo")
            with gateway(db=db) as gw:
                mode = gw.client.get("/v2/channels/RescueCodec/mode").json()
                self.assertEqual((mode["mode"], mode["meshtastic_users"]), ("normal", [STOCK]))

    def test_text_mode_flag(self):
        parse = lambda *a: config_from_args(build_parser().parse_args(["--emulator", *a]))    # noqa: E731
        self.assertEqual(parse().text_mode, "auto")
        self.assertEqual(parse("--text-mode", "normal").text_mode, "normal")
        with gateway(text_mode="normal") as gw:
            self.assertEqual(gw.post("/v2/text", text=HUT).json()["codec"]["version"], 0)


class Receiving(unittest.TestCase):
    def test_a_packed_text_shows_the_words_everywhere(self):
        with gateway() as gw:
            gw.hear({"id": 5, "pos": {"lat": 50.69212, "lon": 7.21345}, "text": {"text": HUT, "dict": 1}})
            record = wait_for(lambda: gw.client.get("/v2/messages").json()["items"])[0]
            self.assertEqual(record["message"]["text"], {"text": HUT, "dict": 1})
            self.assertTrue(record["codec"]["packed"])
            self.assertIn(HUT, record["text"]["de"])
            features = gw.client.get("/v2/export.geojson").json()["features"]
            self.assertIn(HUT, str(features[0]["properties"]))

    def test_stats_count_a_packed_text_as_its_words(self):
        with gateway() as gw:
            gw.hear({"id": 5, "text": {"text": HUT, "dict": 1}})
            wait_for(lambda: gw.client.get("/v2/messages").json()["items"])
            stats = gw.client.get("/v2/stats").json()
            self.assertEqual((stats["bytes_in"]["value"], stats["text_equivalent_bytes"]["value"]),
                             (len(encode({"id": 5, "text": {"text": HUT, "dict": 1}})), 81))


if __name__ == "__main__":
    unittest.main()
