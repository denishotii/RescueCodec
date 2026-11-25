"""Several channels and direct messages: the gateway hears every private channel, keeps direct messages to its node,
ignores public channels unless --show-public, records the channel on every record and answers on the report's channel.

The radio here looks like our real base radio: LongFast (public, index 0), MeshMeister (1), draeger-srs (2),
RescueCodec (3)."""
import json
import sqlite3
import tempfile
import time
import unittest
from contextlib import contextmanager
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

from fastapi.testclient import TestClient

from gateway_fixtures import FOUND, PEER, Gateway, wait_for
from rescuecodec.gateway.app import create_app
from rescuecodec.gateway.cli import build_parser, config_from_args
from rescuecodec.gateway.config import GatewayConfig
from rescuecodec.gateway.connectors.mqtt import MqttConnector
from rescuecodec.gateway.radio import EmulatorRadio, MeshtasticRadio
from rescuecodec.gateway.radio.base import PORT_PRIVATE_APP, PORT_TEXT_MESSAGE, Channel
from rescuecodec.gateway.radio.emulator import GATEWAY_NODE
from rescuecodec.gateway.radio.meshtastic_radio import channel_table
from rescuecodec.gateway.service import GatewayService
from rescuecodec.gateway.store import Store
from rescuecodec.v2 import encode
from test_gateway_radio import FakeInterface, make_radio

BASE_RADIO = (Channel(0, "LongFast", True), Channel(1, "MeshMeister", False), Channel(2, "draeger-srs", False),
              Channel(3, "RescueCodec", False))
LONGFAST, MESHMEISTER, DRAEGER, RESCUECODEC = (c.as_json() for c in BASE_RADIO)


@contextmanager
def gateway(db: str = ":memory:", **config):
    cfg = GatewayConfig(emulator=True, db_path=db, tx_per_minute=0, **config)
    radio = EmulatorRadio(channels=BASE_RADIO, channel_name=cfg.channel_name, listen=cfg.listen_channels,
                          show_public=cfg.show_public)
    service = GatewayService(cfg, radio, Store(db))
    with TestClient(create_app(service)) as client:
        yield Gateway(service, radio, client)


def records(gw, **query):
    params = "&".join(f"{k}={v}" for k, v in query.items())
    return gw.client.get(f"/v2/messages?{params}").json()["items"]


class Receiving(unittest.TestCase):
    def test_reports_and_texts_on_two_private_channels_keep_their_channel(self):
        with gateway() as gw:
            self.assertTrue(gw.radio.inject(PEER, encode({"id": 7, **FOUND}), channel=3))
            self.assertTrue(gw.radio.inject("!0000e003", encode({"id": 8, "hazard": {"kind": "fire"}}), channel=2))
            self.assertTrue(gw.radio.inject_text(PEER, "Wasser am Gasthaus", channel=3, packet_id=11))
            self.assertTrue(gw.radio.inject_text("!0000e003", "Trage kommt", channel=2, packet_id=12))
            got = wait_for(lambda: len(records(gw)) == 4 and records(gw))
            self.assertEqual([(r["type"], r["channel"], r["to"]) for r in got],
                             [("found", RESCUECODEC, None), ("hazard", DRAEGER, None), ("text", RESCUECODEC, None),
                              ("text", DRAEGER, None)])
            tasks = {t["key"]: t["channel"] for t in gw.client.get("/v2/tasks").json()["items"]}
            self.assertEqual(tasks, {"0000e002-7": RESCUECODEC, "0000e003-8": DRAEGER})

    def test_filter_by_channel(self):
        with gateway() as gw:
            gw.radio.inject(PEER, encode({"id": 7, **FOUND}), channel=3)
            gw.radio.inject("!0000e003", encode({"id": 8, "hazard": {"kind": "fire"}}), channel=2)
            gw.radio.inject_text("!0000e003", "Trage kommt", channel=2, packet_id=12)
            wait_for(lambda: len(records(gw)) == 3)
            self.assertEqual([r["type"] for r in records(gw, channel="draeger-srs")], ["hazard", "text"])
            self.assertEqual([r["type"] for r in records(gw, channel="DRAEGER-SRS")], ["hazard", "text"])   # no case
            self.assertEqual(len(records(gw, channel="draeger-srs,RescueCodec")), 3)
            self.assertEqual(records(gw, channel="MeshMeister"), [])
            self.assertEqual([t["key"] for t in gw.client.get("/v2/tasks?channel=RescueCodec").json()["items"]], ["0000e002-7"])
            features = gw.client.get("/v2/export.geojson?channel=draeger-srs").json()["features"]
            self.assertEqual([f["properties"]["channel"] for f in features], [])                 # the hazard has no position
            self.assertEqual(gw.client.get("/v2/export.geojson").json()["features"][0]["properties"]["channel"], "RescueCodec")

    def test_public_channels_are_ignored_by_default(self):
        with gateway() as gw:
            self.assertFalse(gw.radio.inject_text(PEER, "LongFast chat", channel=0))
            self.assertFalse(gw.radio.inject(PEER, encode({"id": 9, **FOUND}), channel=0))
            self.assertFalse(gw.radio.inject_text(PEER, "unknown channel", channel=6))
            # Even if an adapter passed it on, the gateway drops public chatter.
            gw.service._on_packet(SimpleNamespace(sender=PEER, to="^all", port=PORT_TEXT_MESSAGE, payload=b"psst",
                                                  packet_id=5, channel=0, rssi=None, snr=None, hops=None, via="sim"))
            time.sleep(0.1)
            self.assertEqual(records(gw), [])
            self.assertEqual([c["name"] for c in gw.client.get("/v2/health").json()["radio"]["channels"]],
                             ["MeshMeister", "draeger-srs", "RescueCodec"])

    def test_show_public_adds_them(self):
        with gateway(show_public=True) as gw:
            self.assertTrue(gw.radio.inject_text(PEER, "LongFast chat", channel=0, packet_id=3))
            record = wait_for(lambda: records(gw))[0]
            self.assertEqual((record["channel"], record["to"]), (LONGFAST, None))
            self.assertEqual(len(gw.client.get("/v2/health").json()["radio"]["channels"]), 4)

    def test_a_direct_message_to_us_is_kept_on_any_channel(self):
        with gateway() as gw:
            self.assertTrue(gw.radio.inject_text(PEER, "Kommst du?", to=GATEWAY_NODE, channel=0, packet_id=77))
            self.assertTrue(gw.radio.inject(PEER, encode({"id": 3, **FOUND}), to=GATEWAY_NODE, channel=0))
            self.assertFalse(gw.radio.inject_text(PEER, "not for us", to="!12345678", channel=3))
            got = wait_for(lambda: len(records(gw)) == 2 and records(gw))
            self.assertEqual([(r["type"], r["to"], r["channel"]["name"]) for r in got],
                             [("text", GATEWAY_NODE, "LongFast"), ("found", GATEWAY_NODE, "LongFast")])

    def test_channels_limits_what_we_hear_but_keeps_the_default_channel(self):
        with gateway(listen_channels=["draeger-srs"]) as gw:
            self.assertTrue(gw.radio.inject_text(PEER, "a", channel=2, packet_id=1))
            self.assertTrue(gw.radio.inject_text(PEER, "b", channel=3, packet_id=2))      # RescueCodec = default channel
            self.assertFalse(gw.radio.inject_text(PEER, "c", channel=1, packet_id=3))
            wait_for(lambda: len(records(gw)) == 2)

    def test_dedupe_and_the_rc_filter_stay(self):
        with gateway() as gw:
            gw.radio.inject(PEER, encode({"id": 7, **FOUND}), channel=3)
            gw.radio.inject(PEER, encode({"id": 7, **FOUND}), channel=2)              # the same report again
            gw.radio.inject_text("!0000e003", "[RC] Person found: child.", channel=2)
            wait_for(lambda: gw.service._counters["duplicates"] == 2)
            self.assertEqual([r["type"] for r in records(gw)], ["found"])


class Sending(unittest.TestCase):
    def test_default_channel_and_a_named_channel(self):
        with gateway() as gw:
            first = gw.post("/v2/messages", message=FOUND)
            second = gw.post("/v2/messages", message=FOUND, channel="draeger-srs")
            text = gw.post("/v2/text", text="Treffpunkt", channel="meshmeister")
            self.assertEqual([r.status_code for r in (first, second, text)], [202, 202, 202])
            self.assertEqual([r.json()["channel"] for r in (first, second, text)], [RESCUECODEC, DRAEGER, MESHMEISTER])
            wait_for(lambda: len(gw.radio.sent) == 3)
            self.assertEqual(sorted((s["port"], s["channel"], s["to"]) for s in gw.radio.sent),
                             [(PORT_TEXT_MESSAGE, 1, None), (PORT_PRIVATE_APP, 2, None), (PORT_PRIVATE_APP, 3, None)])

    def test_the_same_text_on_two_channels_goes_out_twice(self):
        with gateway() as gw:
            a = gw.post("/v2/text", text="Hilfe", channel="RescueCodec").json()
            b = gw.post("/v2/text", text="Hilfe", channel="draeger-srs").json()
            self.assertNotEqual(a["seq"], b["seq"])
            wait_for(lambda: len(gw.radio.sent) == 2)
            self.assertEqual(sorted(s["channel"] for s in gw.radio.sent), [2, 3])

    def test_a_direct_message(self):
        with gateway() as gw:
            response = gw.post("/v2/messages", message={"text": {"text": "Bitte melden"}}, to="!A1B2C3D4")
            self.assertEqual(response.status_code, 202)
            record = response.json()
            self.assertEqual((record["to"], record["channel"], record["codec"]["version"]), ("!a1b2c3d4", RESCUECODEC, 0))
            via_text = gw.post("/v2/text", text="Kommst du?", to="!a1b2c3d4", channel="draeger-srs").json()
            self.assertEqual((via_text["to"], via_text["channel"]["name"]), ("!a1b2c3d4", "draeger-srs"))
            wait_for(lambda: len(gw.radio.sent) == 2)
            self.assertEqual([(s["port"], s["to"], s["channel"]) for s in gw.radio.sent],
                             [(PORT_TEXT_MESSAGE, "!a1b2c3d4", 3), (PORT_TEXT_MESSAGE, "!a1b2c3d4", 2)])
            self.assertEqual(gw.radio.sent[0]["payload"], b"Bitte melden")

    def test_a_direct_message_is_plain_even_with_text_as_meshtastic_off(self):
        with gateway(text_as_meshtastic=False) as gw:
            record = gw.post("/v2/text", text="Kommst du?", to="!a1b2c3d4").json()
            self.assertEqual(record["codec"]["version"], 0)
            wait_for(lambda: gw.radio.sent)
            self.assertEqual(gw.radio.sent[0]["port"], PORT_TEXT_MESSAGE)

    def test_bad_channel_or_peer_is_refused(self):
        with gateway() as gw:
            cases = [
                ({"message": FOUND, "channel": "nope"}, "channel"),
                ({"message": FOUND, "channel": ""}, "channel"),
                ({"message": FOUND, "channel": 3}, "channel"),
                ({"message": FOUND, "channel": "LongFast"}, "channel"),             # public: not listened, not for reports
                ({"message": {"text": {"text": "x"}}, "to": "a1b2c3d4"}, "to"),
                ({"message": {"text": {"text": "x"}}, "to": "!ffffffff"}, "to"),
                ({"message": FOUND, "to": "!a1b2c3d4"}, "to"),                       # a report is never a direct message
            ]
            for body, field in cases:
                response = gw.client.post("/v2/messages", json=body)
                self.assertEqual((response.status_code, response.json()["error"]["field"]), (400, field), body)
            self.assertEqual(gw.radio.sent, [])

    def test_reports_never_go_out_on_a_public_channel(self):
        with gateway(show_public=True) as gw:
            response = gw.post("/v2/messages", message=FOUND, channel="LongFast")
            self.assertEqual(response.status_code, 400)
            self.assertIn("public", response.json()["error"]["message"])
            self.assertEqual(gw.post("/v2/text", text="Hallo LongFast", channel="LongFast").status_code, 202)

    def test_dry_run_shows_the_channel(self):
        with gateway() as gw:
            record = gw.post("/v2/messages", message=FOUND, channel="draeger-srs", dry_run=True).json()
            self.assertEqual((record["status"], record["channel"]), ("dry_run", DRAEGER))


class Answers(unittest.TestCase):
    def test_the_ack_goes_out_on_the_reports_channel(self):
        with gateway() as gw:
            gw.radio.inject("!0000e003", encode({"id": 8, "hazard": {"kind": "fire"}}), channel=2)
            gw.radio.inject(PEER, encode({"id": 7, **FOUND}), channel=1)
            wait_for(lambda: len(gw.client.get("/v2/tasks").json()["items"]) == 2)
            first = gw.post("/v2/tasks/0000e003-8/ack", status="accepted", eta=10)
            second = gw.post("/v2/tasks/0000e002-7/ack", status="declined")
            self.assertEqual((first.status_code, second.status_code), (202, 202))
            self.assertEqual(first.json()["record"]["channel"], DRAEGER)
            self.assertEqual(second.json()["record"]["channel"], MESHMEISTER)
            self.assertEqual(first.json()["task"]["channel"], DRAEGER)
            wait_for(lambda: len(gw.radio.sent) == 2)
            self.assertEqual([(s["port"], s["channel"]) for s in gw.radio.sent], [(PORT_PRIVATE_APP, 2), (PORT_PRIVATE_APP, 1)])

    def test_an_answer_to_a_direct_report_goes_on_its_channel_too(self):
        with gateway() as gw:
            gw.radio.inject(PEER, encode({"id": 4, **FOUND}), to=GATEWAY_NODE, channel=0)      # a DM on LongFast
            wait_for(lambda: gw.client.get("/v2/tasks").json()["items"])
            response = gw.post("/v2/tasks/0000e002-4/ack", status="accepted")
            self.assertEqual(response.status_code, 202)
            wait_for(lambda: gw.radio.sent)
            self.assertEqual(gw.radio.sent[0]["channel"], 0)

    def test_tasks_remember_their_channel_after_a_restart(self):
        with tempfile.TemporaryDirectory() as tmp:
            db = str(Path(tmp) / "gw.db")
            with gateway(db=db) as gw:
                gw.radio.inject("!0000e003", encode({"id": 8, "hazard": {"kind": "fire"}}), channel=2)
                wait_for(lambda: gw.client.get("/v2/tasks").json()["items"])
            with gateway(db=db) as gw:
                self.assertEqual(gw.client.get("/v2/tasks").json()["items"][0]["channel"], DRAEGER)
                gw.post("/v2/tasks/0000e003-8/ack", status="accepted")
                wait_for(lambda: gw.radio.sent)
                self.assertEqual(gw.radio.sent[0]["channel"], 2)


class Migration(unittest.TestCase):
    OLD_SCHEMA = """CREATE TABLE messages (seq INTEGER PRIMARY KEY AUTOINCREMENT, key TEXT, direction TEXT NOT NULL,
                    type TEXT NOT NULL, status TEXT NOT NULL, time TEXT NOT NULL, doc TEXT NOT NULL);
                    CREATE TABLE meta (name TEXT PRIMARY KEY, value TEXT NOT NULL);"""

    def old_record(self, seq, direction, radio_channel):
        return {"seq": seq, "key": f"0000e002-{seq}", "direction": direction, "from": PEER if direction == "in" else GATEWAY_NODE,
                "type": "found", "priority": "urgent", "status": "received" if direction == "in" else "delivered",
                "error": None, "time": "2026-11-25T14:32:05Z", "event_time": None,
                "codec": {"version": 2, "size": 23, "hex": encode({"id": seq, **FOUND}).hex()},
                "radio": {"packet_id": seq, "rssi": -90, "snr": 5.0, "hops": 0, "channel": radio_channel, "via": "tcp"}
                if direction == "in" else None,
                "message": {"id": seq, **FOUND}, "text": {"en": "Person found.", "de": "Person gefunden."},
                "task_key": f"0000e002-{seq}" if direction == "in" else f"0000e001-{seq}"}

    def test_an_old_database_gets_channels(self):
        with tempfile.TemporaryDirectory() as tmp:
            db = str(Path(tmp) / "old.db")
            con = sqlite3.connect(db)
            con.executescript(self.OLD_SCHEMA)
            for record in (self.old_record(1, "in", 3), self.old_record(2, "in", 3), self.old_record(3, "out", None),
                           self.old_record(4, "in", 0)):
                con.execute("INSERT INTO messages (seq, key, direction, type, status, time, doc) VALUES (?,?,?,?,?,?,?)",
                            (record["seq"], record["key"], record["direction"], record["type"], record["status"],
                             record["time"], json.dumps(record)))
            con.commit()
            con.close()
            with gateway(db=db) as gw:
                got = records(gw)
                self.assertEqual([(r["channel"], r["to"]) for r in got],
                                 [(RESCUECODEC, None), (RESCUECODEC, None), (RESCUECODEC, None),
                                  ({"index": 0, "name": "Channel 0", "public": False}, None)])
                self.assertEqual(len(records(gw, channel="RescueCodec")), 3)
                task = gw.client.get("/v2/tasks/0000e002-1").json()
                self.assertEqual(task["channel"], RESCUECODEC)
                self.assertEqual(gw.post("/v2/tasks/0000e002-1/ack", status="accepted").status_code, 202)
                wait_for(lambda: gw.radio.sent)
                self.assertEqual(gw.radio.sent[0]["channel"], 3)
            columns = [row[1] for row in sqlite3.connect(db).execute("PRAGMA table_info(messages)")]
            self.assertIn("channel", columns)
            with gateway(db=db) as gw:                                   # the second start changes nothing
                self.assertEqual(gw.service.store.backfill_channels("RescueCodec"), 0)
                self.assertEqual(len(records(gw)), 5)


class MeshtasticAdapter(unittest.TestCase):
    """The real adapter with a fake radio that has our base radio's four channels."""

    def iface(self):
        iface = FakeInterface()
        key = bytes(range(1, 17))
        iface.localNode.channels = [
            SimpleNamespace(index=0, role=1, settings=SimpleNamespace(name="", psk=b"\x01")),
            SimpleNamespace(index=1, role=2, settings=SimpleNamespace(name="MeshMeister", psk=key)),
            SimpleNamespace(index=2, role=2, settings=SimpleNamespace(name="draeger-srs", psk=bytes(range(2, 34)))),
            SimpleNamespace(index=3, role=2, settings=SimpleNamespace(name="RescueCodec", psk=bytes(range(3, 19)))),
            SimpleNamespace(index=4, role=0, settings=SimpleNamespace(name="", psk=b"")),
        ]
        return iface

    def receive(self, packets, **kw):
        iface = self.iface()
        radio, _ = make_radio(iface, **kw)
        got = []
        radio.on_packet = got.append
        with mock.patch.object(MeshtasticRadio, "_subscribe"):
            radio.start()
            wait_for(lambda: radio.connected)
            for packet in packets:
                radio._on_receive(packet, iface)
            info = radio.info()
            radio.stop()
        return got, radio, info

    def packets(self):
        base = {"fromId": "!a1b2c3d4", "toId": "^all", "decoded": {"portnum": "PRIVATE_APP", "payload": b"\x08\x01"}}
        text = {**base, "decoded": {"portnum": "TEXT_MESSAGE_APP", "payload": b"hallo"}}
        return [{**base, "id": 1, "channel": 3}, {**base, "id": 2, "channel": 2}, {**text, "id": 3, "channel": 1},
                {**text, "id": 4}, {**base, "id": 5},                                       # LongFast (channel 0)
                {**text, "id": 6, "toId": "!0badc0de"}, {**text, "id": 7, "channel": 2, "toId": "!12345678"},
                {**base, "id": 8, "channel": 4}]

    def test_hears_every_private_channel_and_direct_messages(self):
        got, radio, info = self.receive(self.packets())
        self.assertEqual([p.packet_id for p in got], [1, 2, 3, 6])
        self.assertEqual(radio.other_channel, 2)
        self.assertEqual((info.channel, info.channel_index), ("RescueCodec", 3))
        self.assertEqual([c.name for c in info.channels], ["MeshMeister", "draeger-srs", "RescueCodec"])
        self.assertEqual(info.as_health()["channels"][1], {"index": 2, "name": "draeger-srs", "public": False})

    def test_show_public_and_a_channel_list(self):
        got, _, info = self.receive(self.packets(), show_public=True)
        self.assertEqual([p.packet_id for p in got], [1, 2, 3, 4, 5, 6])
        self.assertEqual(info.channels[0], Channel(0, "LongFast", True))
        got, _, info = self.receive(self.packets(), listen=["draeger-srs"])
        self.assertEqual([p.packet_id for p in got], [1, 2, 6])                # RescueCodec stays: it is the default
        self.assertEqual([c.name for c in info.channels], ["draeger-srs", "RescueCodec"])

    def test_sends_a_direct_message_on_the_asked_channel(self):
        iface = self.iface()
        radio, _ = make_radio(iface)
        with mock.patch.object(MeshtasticRadio, "_subscribe"):
            radio.start()
            wait_for(lambda: radio.connected)
            radio.send(b"hallo", port=PORT_TEXT_MESSAGE, channel=2, to="!a1b2c3d4")
            radio.send(b"\x08\x01")
            radio.stop()
        self.assertEqual([(kw["destinationId"], kw["channelIndex"]) for _, kw in iface.sent], [("!a1b2c3d4", 2), ("^all", 3)])

    def test_channel_names(self):
        rows = self.iface().localNode.channels
        self.assertEqual([c.name for c in channel_table(rows)], ["LongFast", "MeshMeister", "draeger-srs", "RescueCodec"])
        self.assertEqual(channel_table(rows, "MEDIUM_FAST")[0], Channel(0, "MediumFast", True))
        unnamed = [SimpleNamespace(index=5, role=2, settings=SimpleNamespace(name="", psk=bytes(16)))]
        self.assertEqual(channel_table(unnamed), [Channel(5, "Channel 5", False)])


class CommandLine(unittest.TestCase):
    def parse(self, *argv):
        return config_from_args(build_parser().parse_args(["--emulator", *argv]))

    def test_flags(self):
        cfg = self.parse()
        self.assertEqual((cfg.listen_channels, cfg.show_public), (None, False))
        cfg = self.parse("--channels", "draeger-srs, RescueCodec", "--show-public")
        self.assertEqual((cfg.listen_channels, cfg.show_public), (["draeger-srs", "RescueCodec"], True))
        self.assertIsNone(self.parse("--channels", "all").listen_channels)
        text = build_parser().format_help()
        for flag in ("--channels", "--show-public", "default channel for sending"):
            self.assertIn(flag, text)
        import os
        with mock.patch.dict(os.environ, {"RESCUECODEC_CHANNELS": "MeshMeister", "RESCUECODEC_SHOW_PUBLIC": "1"}):
            cfg = self.parse()
        self.assertEqual((cfg.listen_channels, cfg.show_public), (["MeshMeister"], True))


class Connectors(unittest.TestCase):
    def test_mqtt_has_a_topic_per_channel(self):
        published = []
        client = SimpleNamespace(publish=lambda topic, body, qos, retain=False: published.append(topic),
                                 username_pw_set=lambda *a: None, will_set=lambda *a, **k: None,
                                 reconnect_delay_set=lambda **k: None)
        mqtt = MqttConnector("b", client_factory=lambda: client)
        mqtt.handle("message", {"type": "text", "text": {"en": "x", "de": "x"}, "channel": {"name": "draeger srs/2"}})
        self.assertEqual(published, ["rescuecodec/v2/messages", "rescuecodec/v2/text", "rescuecodec/v2/channels/draeger_srs_2",
                                     "rescuecodec/v2/readable"])

    def test_tak_remarks_name_the_channel(self):
        from datetime import datetime, timezone
        from rescuecodec.gateway.connectors.cot import task_events
        task = {"key": "a1b2c3d4-7", "from": "!a1b2c3d4", "type": "found", "state": "open", "pos": {"lat": 50.7, "lon": 7.2},
                "text": {"en": "Person found."}, "acks": [], "channel": DRAEGER}
        xml = task_events(task, now=datetime.now(timezone.utc))[0].decode()
        self.assertIn("Person found. [OPEN] (channel draeger-srs)", xml)


if __name__ == "__main__":
    unittest.main()
