"""Normal Meshtastic texts (port 1, TEXT_MESSAGE_APP) in the gateway: which ones are shown, how they are stored,
duplicates and readable copies, and sending a text-only message as a normal Meshtastic text (SPEC section 2.1)."""
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

from gateway_fixtures import FOUND, PEER, gateway, wait_for
from rescuecodec.engine.extract import fit_plain
from rescuecodec.gateway.cli import build_parser, config_from_args
from rescuecodec.gateway.hub import ConnectorHub
from rescuecodec.gateway.radio import MeshtasticRadio
from rescuecodec.gateway.radio.base import PORT_PRIVATE_APP, PORT_TEXT_MESSAGE, TEXT_COPY_PREFIX, RadioPacket
from rescuecodec.gateway.radio.emulator import GATEWAY_NODE
from rescuecodec.v2 import codes as C
from test_gateway_radio import make_radio

RELIABLE = C.MESHTASTIC_PRIORITY["normal"]


def records(gw, **query):
    params = "&".join(f"{k}={v}" for k, v in query.items())
    return gw.client.get(f"/v2/messages?{params}").json()["items"]


class Receiving(unittest.TestCase):
    def test_a_text_on_the_team_channel_is_stored_like_a_text(self):
        with gateway() as gw:
            self.assertTrue(gw.radio.inject_text(PEER, "Wir brauchen Wasser am Gasthaus", packet_id=4242))
            record = wait_for(lambda: records(gw))[0]
            self.assertEqual((record["direction"], record["from"], record["type"], record["status"]),
                             ("in", PEER, "text", "received"))
            self.assertEqual(record["codec"], {"version": 0, "size": 31, "hex": "Wir brauchen Wasser am Gasthaus".encode().hex()})
            self.assertEqual(record["message"], {"text": {"text": "Wir brauchen Wasser am Gasthaus"}})
            self.assertEqual(record["text"]["de"], "Nachricht: Wir brauchen Wasser am Gasthaus.")
            self.assertEqual((record["key"], record["task_key"], record["priority"]), (None, None, "normal"))
            self.assertEqual((record["radio"]["packet_id"], record["radio"]["channel"], record["radio"]["via"]), (4242, 0, "sim"))
            self.assertEqual(gw.client.get("/v2/tasks").json()["items"], [])
            stats = gw.client.get("/v2/stats").json()
            self.assertEqual(stats["by_type"]["text"]["count"], 1)
            self.assertEqual(stats["text_equivalent_bytes"]["value"], 31)    # no made-up saving for plain text
            self.assertEqual(stats["size_ratio"]["value"], 1.0)

    def test_a_text_on_another_channel_or_to_another_node_is_never_shown(self):
        with gateway() as gw:
            self.assertFalse(gw.radio.inject_text(PEER, "LongFast chat", channel=3))
            self.assertFalse(gw.radio.inject_text(PEER, "for someone else", to="!12345678"))
            # Even if an adapter passed it on: a direct message to another node is not ours.
            gw.service._on_packet(RadioPacket(sender=PEER, to="!12345678", port=PORT_TEXT_MESSAGE, payload=b"psst", packet_id=9))
            time.sleep(0.1)
            self.assertEqual(records(gw), [])

    def test_a_direct_message_to_us_is_shown_on_any_channel(self):
        with gateway() as gw:
            self.assertTrue(gw.radio.inject_text(PEER, "Kommst du?", to=GATEWAY_NODE, channel=0, packet_id=77))
            self.assertTrue(gw.radio.inject_text("!0000e003", "Hier auch", to=GATEWAY_NODE, channel=5, packet_id=78))
            got = wait_for(lambda: len(records(gw)) == 2 and records(gw))
            self.assertEqual([r["message"]["text"]["text"] for r in got], ["Kommst du?", "Hier auch"])

    def test_the_same_packet_twice_is_shown_once(self):
        with gateway() as gw:
            gw.radio.inject_text(PEER, "Hilfe", packet_id=500)
            gw.radio.inject_text(PEER, "Hilfe", packet_id=500)             # the mesh delivered it twice
            gw.radio.inject_text(PEER, "Hilfe", packet_id=501)             # a new message with the same words
            gw.radio.inject_text("!0000e003", "Hilfe", packet_id=500)      # another sender, same packet id
            wait_for(lambda: len(records(gw)) == 3)
            time.sleep(0.1)
            self.assertEqual(len(records(gw)), 3)
            self.assertEqual(gw.service._counters["duplicates"], 1)

    def test_a_readable_copy_of_a_rescuecodec_message_is_not_shown_twice(self):
        with gateway() as gw:
            gw.hear({"id": 7, **FOUND})
            gw.radio.inject_text("!0000e003", TEXT_COPY_PREFIX + "Person found: child, conscious.")
            wait_for(lambda: gw.service._counters["duplicates"] == 1)
            self.assertEqual([r["type"] for r in records(gw)], ["found"])

    def test_the_duplicate_memory_survives_a_restart(self):
        with tempfile.TemporaryDirectory() as tmp:
            db = str(Path(tmp) / "gw.db")
            with gateway(db=db) as gw:
                gw.radio.inject_text(PEER, "Treffpunkt Gasthaus", packet_id=900)
                wait_for(lambda: records(gw))
            with gateway(db=db) as gw:
                gw.radio.inject_text(PEER, "Treffpunkt Gasthaus", packet_id=900)     # the radio hands it over again
                time.sleep(0.1)
                self.assertEqual(len(records(gw)), 1)
                self.assertEqual(gw.service._counters["duplicates"], 1)

    def test_texts_go_to_the_websocket_and_the_connectors(self):
        events = []
        with gateway() as gw:
            hub = ConnectorHub(gw.service.bus)
            hub.add(lambda event, data: events.append((event, data)))
            hub.start()
            with gw.client.websocket_connect("/v2/stream") as ws:
                self.assertEqual(ws.receive_json()["event"], "hello")
                gw.radio.inject_text(PEER, "Brauchen Licht")
                live = ws.receive_json()
                self.assertEqual((live["event"], live["data"]["codec"]["version"]), ("message", 0))
            wait_for(lambda: events)
            hub.stop()
        self.assertEqual(events[0][0], "message")
        self.assertEqual(events[0][1]["message"]["text"]["text"], "Brauchen Licht")

    def test_a_received_text_is_not_copied_again_with_text_over_air(self):
        with gateway(text_over_air="both") as gw:
            gw.radio.inject_text(PEER, "Brauchen Licht")
            wait_for(lambda: records(gw))
            time.sleep(0.3)
            self.assertEqual(gw.radio.plain_texts, [])


class Sending(unittest.TestCase):
    def test_a_text_goes_out_as_a_normal_meshtastic_text(self):
        with gateway() as gw:
            response = gw.post("/v2/text", text="Treffpunkt jetzt am Gasthaus")
            self.assertEqual(response.status_code, 202)
            record = response.json()
            self.assertEqual((record["type"], record["key"], record["codec"]["version"]), ("text", None, 0))
            self.assertEqual(record["codec"]["hex"], "Treffpunkt jetzt am Gasthaus".encode().hex())
            wait_for(lambda: gw.client.get(f"/v2/messages/{record['seq']}").json()["status"] == "delivered")
            sent = gw.radio.sent[-1]
            self.assertEqual((sent["port"], sent["payload"], sent["want_ack"], sent["priority"]),
                             (PORT_TEXT_MESSAGE, "Treffpunkt jetzt am Gasthaus".encode(), True, RELIABLE))

    def test_a_text_through_messages_drops_position_and_keeps_priority(self):
        with gateway() as gw:
            record = gw.post("/v2/messages", message={"priority": "urgent", "pos": {"lat": 50.7, "lon": 7.2},
                                                      "text": {"text": "Weg gesperrt"}}).json()
            self.assertEqual(record["message"], {"priority": "urgent", "text": {"text": "Weg gesperrt"}})
            self.assertEqual(record["priority"], "urgent")
            wait_for(lambda: gw.radio.sent)
            self.assertEqual((gw.radio.sent[0]["port"], gw.radio.sent[0]["payload"], gw.radio.sent[0]["priority"]),
                             (PORT_TEXT_MESSAGE, b"Weg gesperrt", C.MESHTASTIC_PRIORITY["urgent"]))

    def test_reports_always_use_port_256(self):
        with gateway() as gw:
            gw.post("/v2/messages", message=FOUND)
            gw.post("/v2/messages", message={"mayday": {"reason": "injured"}})
            wait_for(lambda: len(gw.radio.sent) == 2)
            self.assertEqual({s["port"] for s in gw.radio.sent}, {PORT_PRIVATE_APP})

    def test_with_the_setting_off_a_text_is_a_rescuecodec_text_on_port_256(self):
        with gateway(text_as_meshtastic=False) as gw:
            record = gw.post("/v2/text", text="Treffpunkt", also_plain=True).json()
            self.assertEqual(record["codec"]["version"], 2)
            self.assertEqual(record["message"]["text"]["text"], "Treffpunkt")
            wait_for(lambda: len(gw.radio.sent) == 2)
            self.assertEqual([s["port"] for s in gw.radio.sent], [PORT_PRIVATE_APP, PORT_TEXT_MESSAGE])
            self.assertEqual(gw.radio.plain_texts, [TEXT_COPY_PREFIX + "Treffpunkt"])    # also_plain: a marked copy

    def test_also_plain_sends_nothing_extra_when_the_text_is_plain_already(self):
        with gateway(text_over_air="out") as gw:
            gw.post("/v2/text", text="Treffpunkt", also_plain=True)
            wait_for(lambda: gw.radio.sent)
            time.sleep(0.3)
            self.assertEqual(gw.radio.plain_texts, ["Treffpunkt"])

    def test_the_same_text_twice_goes_out_twice(self):
        with gateway() as gw:
            first = gw.post("/v2/text", text="Hilfe").json()
            wait_for(lambda: gw.client.get(f"/v2/messages/{first['seq']}").json()["status"] == "delivered")
            second = gw.post("/v2/text", text="Hilfe").json()
            self.assertNotEqual(first["seq"], second["seq"])
            wait_for(lambda: len(gw.radio.sent) == 2)        # receivers tell them apart by the Meshtastic packet id

    def test_dry_run_shows_the_plain_record(self):
        with gateway() as gw:
            record = gw.post("/v2/messages", message={"text": {"text": "Test"}}, dry_run=True).json()
            self.assertEqual((record["seq"], record["status"], record["codec"]["version"]), (0, "dry_run", 0))
            self.assertEqual(gw.radio.sent, [])

    def test_a_bad_text_is_still_refused(self):
        with gateway() as gw:
            self.assertEqual(gw.post("/v2/messages", message={"text": {"text": "x" * 181}}).status_code, 400)
            self.assertEqual(gw.post("/v2/messages", message={"text": {"text": "hi", "colour": 1}}).status_code, 400)
            self.assertEqual(gw.post("/v2/messages", message={"priority": "very", "text": {"text": "hi"}}).status_code, 400)


class Copies(unittest.TestCase):
    def test_copies_are_marked_and_at_most_200_bytes(self):
        long_line = "Person gefunden: " + "Unterkühlung, " * 20
        with gateway(text_over_air="out") as gw:
            with mock.patch.object(gw.service, "_copy_line", lambda record: TEXT_COPY_PREFIX + long_line):
                gw.post("/v2/messages", message=FOUND)
                line = wait_for(lambda: gw.radio.plain_texts)[0]
        self.assertTrue(line.startswith(TEXT_COPY_PREFIX))
        self.assertLessEqual(len(line.encode("utf-8")), 200)
        self.assertTrue(line.endswith("…"))

    def test_fit_plain_cuts_on_a_character_boundary(self):
        self.assertEqual(fit_plain("kurz"), "kurz")
        exact = "a" * 200
        self.assertIs(fit_plain(exact), exact)
        for text in ("ü" * 150, "a" + "ü" * 150, "€" * 100, "Weg " * 80, "🚑" * 70):
            cut = fit_plain(text)
            self.assertLessEqual(len(cut.encode("utf-8")), 200, text[:10])
            self.assertGreaterEqual(len(cut.encode("utf-8")), 195, text[:10])
            self.assertTrue(cut.endswith("…"))
            self.assertTrue(text.startswith(cut[:-1].rstrip()))
        self.assertEqual(fit_plain("a" * 196 + " " + "b" * 50), "a" * 196 + "…")     # a trailing space goes


class Adapter(unittest.TestCase):
    """The real Meshtastic adapter: LongFast on channel 0, the team channel RescueCodec on 1, our node !0badc0de."""

    def receive(self, packets):
        radio, iface = make_radio()
        got = []
        radio.on_packet = got.append
        with mock.patch.object(MeshtasticRadio, "_subscribe"):
            radio.start()
            wait_for(lambda: radio.connected)
            for packet in packets:
                radio._on_receive(packet, iface)
            radio.stop()
        return got, radio

    def test_texts_only_from_the_team_channel_or_to_us(self):
        base = {"fromId": "!a1b2c3d4", "toId": "^all", "id": 1, "decoded": {"portnum": "TEXT_MESSAGE_APP", "payload": b"hallo"}}
        got, radio = self.receive([
            {**base, "id": 1, "channel": 1},                                  # team channel: shown
            {**base, "id": 2},                                                # channel 0, public LongFast: ignored
            {**base, "id": 3, "channel": 2},                                  # another channel: ignored
            {**base, "id": 4, "toId": "!0badc0de"},                           # a direct message to us: shown
            {**base, "id": 5, "toId": "!12345678", "channel": 1},             # a direct message to someone else: ignored
        ])
        self.assertEqual([p.packet_id for p in got], [1, 4])
        self.assertEqual(radio.other_channel, 0)                               # that counter is for port 256 only

    def test_sends_a_text_on_the_team_channel(self):
        radio, iface = make_radio()
        with mock.patch.object(MeshtasticRadio, "_subscribe"):
            radio.start()
            wait_for(lambda: radio.connected)
            radio.send(b"hallo", port=PORT_TEXT_MESSAGE, want_ack=True, priority=RELIABLE)
            radio.stop()
        data, kwargs = iface.sent[0]
        self.assertEqual((data, kwargs["portNum"], kwargs["channelIndex"], kwargs["wantAck"], kwargs["destinationId"]),
                         (b"hallo", PORT_TEXT_MESSAGE, 1, True, "^all"))


class CommandLine(unittest.TestCase):
    def parse(self, *argv):
        return config_from_args(build_parser().parse_args(["--emulator", *argv]))

    def test_on_by_default(self):
        self.assertTrue(self.parse().text_as_meshtastic)
        self.assertFalse(self.parse("--text-as-meshtastic", "off").text_as_meshtastic)
        self.assertIn("--text-as-meshtastic", build_parser().format_help())
        import os
        with mock.patch.dict(os.environ, {"RESCUECODEC_TEXT_AS_MESHTASTIC": "off"}):
            self.assertFalse(self.parse().text_as_meshtastic)


if __name__ == "__main__":
    unittest.main()
