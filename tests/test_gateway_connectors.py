"""Connectors: TAK CoT (checked against ATAK-CIV's own examples), MQTT, GeoJSON, command line."""
import re
import socket
import threading
import unittest
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

from gateway_fixtures import FOUND, gateway, wait_for
from rescuecodec.gateway.cli import build_app, build_parser, config_from_args
from rescuecodec.gateway.connectors import cot
from rescuecodec.gateway.connectors.mqtt import MqttConnector

# Sample from ATAK-CIV, takcot/examples/"Marker - Spot.cot" (a map spot marker made in ATAK), shortened.
OFFICIAL_SPOT = """<?xml version='1.0' encoding='UTF-8' standalone='yes'?>
<event version='2.0' uid='9405e320-9356-41c4-8449-f46990aa17f8' type='b-m-p-s-m' time='2020-12-16T19:59:34.913Z' start='2020-12-16T19:59:34.913Z' stale='2021-01-02T20:40:03.841Z' how='h-g-i-g-o'>
  <point lat='38.85606343062312' lon='-77.0563755018233' hae='9999999.0' ce='9999999.0' le='9999999.0' />
  <detail>
    <status readiness='true'/>
    <link uid='ANDROID-589520ccfcd20f01' production_time='2020-12-16T19:51:09.603Z' type='a-f-G-U-C' parent_callsign='HOPE' relation='p-p'/>
    <contact callsign='R 1'/>
    <remarks></remarks>
    <color argb='-65536'/>
    <precisionlocation altsrc='???'/>
    <usericon iconsetpath='COT_MAPPING_SPOTMAP/b-m-p-s-m/-65536'/>
  </detail>
</event>"""
# ATAK's own 911 alert and cancel (EmergencyManager.java, generateInitiateMessage and generateCancelMessage), as XML.
OFFICIAL_ALERT = """<event version='2.0' uid='ANDROID-589520ccfcd20f01-9-1-1' type='b-a-o-tbl' time='2020-12-16T19:59:34.913Z' start='2020-12-16T19:59:34.913Z' stale='2020-12-16T19:59:44.913Z' how='h-g-i-g-o'>
  <point lat='38.85606' lon='-77.05637' hae='9999999.0' ce='9999999.0' le='9999999.0'/>
  <detail><link uid='ANDROID-589520ccfcd20f01' type='a-f-G-U-C' relation='p-p'/><contact callsign='HOPE-Alert'/><emergency type='911 Alert'>HOPE</emergency></detail>
</event>"""
OFFICIAL_CANCEL = """<event version='2.0' uid='ANDROID-589520ccfcd20f01-9-1-1' type='b-a-o-can' time='2020-12-16T19:59:34.913Z' start='2020-12-16T19:59:34.913Z' stale='2020-12-16T19:59:44.913Z' how='h-g-i-g-o'>
  <point lat='38.85606' lon='-77.05637' hae='9999999.0' ce='9999999.0' le='9999999.0'/>
  <detail><emergency cancel='true'>HOPE</emergency></detail>
</event>"""

TIME_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$")
TYPE_RE = re.compile(r"^[a-z](-[A-Za-z0-9]+)*$")
EVENT_ATTRS = {"version", "uid", "type", "time", "start", "stale", "how"}
POINT_ATTRS = {"lat", "lon", "hae", "ce", "le"}
DETAIL_CHILDREN = {"status": {"readiness"}, "link": {"uid", "type", "relation", "production_time", "parent_callsign"},
                   "contact": {"callsign"}, "remarks": {"source"}, "color": {"argb"}, "usericon": {"iconsetpath"},
                   "precisionlocation": {"altsrc"}, "emergency": {"type", "cancel"}, "archive": set()}


def check_event(test: unittest.TestCase, xml: bytes | str) -> ET.Element:
    """The rules of the CoT event schema that every TAK client relies on."""
    event = ET.fromstring(xml)
    test.assertEqual(event.tag, "event")
    test.assertTrue(EVENT_ATTRS <= set(event.attrib), event.attrib)
    test.assertEqual(event.get("version"), "2.0")
    test.assertRegex(event.get("type"), TYPE_RE)
    for name in ("time", "start", "stale"):
        test.assertRegex(event.get(name), TIME_RE)
    point = event.find("point")
    test.assertIsNotNone(point)
    test.assertEqual(set(point.attrib), POINT_ATTRS)
    test.assertTrue(-90 <= float(point.get("lat")) <= 90 and -180 <= float(point.get("lon")) <= 180)
    for key in ("hae", "ce", "le"):
        float(point.get(key))
    detail = event.find("detail")
    test.assertIsNotNone(detail)
    for child in detail:
        test.assertIn(child.tag, DETAIL_CHILDREN)
        test.assertLessEqual(set(child.attrib), DETAIL_CHILDREN[child.tag] | {"source"}, child.tag)
    return event


def task(**over):
    base = {"key": "a1b2c3d4-7", "from": "!a1b2c3d4", "id": 7, "type": "found", "priority": "urgent", "state": "open",
            "created": "2026-11-25T14:32:05Z", "updated": "2026-11-25T14:32:05Z", "pos": FOUND["pos"], "message_seq": 1,
            "text": {"en": "Person found: child, conscious. Position 50.69212, 7.21345.", "de": "Person gefunden: Kind."}, "acks": []}
    base.update(over)
    return base


NOW = datetime(2026, 11, 25, 14, 33, 0, 120000, tzinfo=timezone.utc)


class CotAgainstOfficialExamples(unittest.TestCase):
    def test_the_checker_accepts_the_official_examples(self):
        for sample in (OFFICIAL_SPOT, OFFICIAL_ALERT, OFFICIAL_CANCEL):
            check_event(self, sample)

    def test_found_is_a_spot_marker_like_the_official_one(self):
        (xml,) = cot.task_events(task(), now=NOW)
        ours, official = check_event(self, xml), check_event(self, OFFICIAL_SPOT)
        self.assertEqual(ours.get("type"), official.get("type"))
        self.assertEqual(ours.get("how"), official.get("how"))
        self.assertEqual(set(ours.attrib), set(official.attrib))
        self.assertEqual(set(ours.find("point").attrib), set(official.find("point").attrib))
        self.assertEqual(ours.get("uid"), "RescueCodec.a1b2c3d4-7")
        self.assertEqual(ours.get("time"), "2026-11-25T14:33:00.120Z")
        self.assertEqual(ours.get("stale"), "2026-11-25T20:33:00.120Z")
        self.assertEqual(ours.find("point").get("lat"), "50.69212")
        self.assertEqual(ours.find("point").get("lon"), "7.21345")
        detail = ours.find("detail")
        self.assertIn("Person found", detail.find("remarks").text)
        self.assertIn("[OPEN]", detail.find("remarks").text)
        self.assertEqual(detail.find("color").get("argb"), "-65536")              # red, a signed ARGB integer like ATAK's
        self.assertEqual(detail.find("usericon").get("iconsetpath"), "COT_MAPPING_SPOTMAP/b-m-p-s-m/-65536")
        self.assertEqual(detail.find("contact").get("callsign"), "FOUND a1b2c3d4-7")

    def test_mayday_is_a_911_alert_and_done_is_a_cancel(self):
        (alert,) = cot.task_events(task(type="mayday", key="a1b2c3d4-8", id=8), now=NOW)
        ours, official = check_event(self, alert), check_event(self, OFFICIAL_ALERT)
        self.assertEqual(ours.get("type"), official.get("type"))
        self.assertEqual(ours.find("detail/emergency").get("type"), official.find("detail/emergency").get("type"))
        self.assertTrue(ours.find("detail/contact").get("callsign").endswith("-Alert"))
        self.assertEqual(ours.find("detail/emergency").text, "MAYDAY a1b2c3d4-8")
        self.assertEqual(ours.find("detail/link").get("type"), "a-f-G-U-C")
        (cancel,) = cot.task_events(task(type="mayday", key="a1b2c3d4-8", id=8, state="done"), now=NOW)
        ours, official = check_event(self, cancel), check_event(self, OFFICIAL_CANCEL)
        self.assertEqual(ours.get("type"), official.get("type"))
        self.assertEqual(ours.get("uid"), "RescueCodec.a1b2c3d4-8")                # the same uid as the alert
        self.assertEqual(ours.find("detail/emergency").get("cancel"), "true")
        self.assertEqual([c.tag for c in ours.find("detail")], ["emergency"])
        self.assertGreater(ours.get("stale"), ours.get("time"))                   # not stale yet: TAK acts on it

    def test_accept_updates_the_same_marker_and_done_expires_it(self):
        accepted = task(state="accepted", acks=[{"by": "!0badc0de", "status": "accepted", "eta": 15, "team": "Trupp 3", "time": "x", "seq": 2}])
        marker = check_event(self, cot.task_events(accepted, now=NOW)[0])
        self.assertEqual(marker.get("uid"), "RescueCodec.a1b2c3d4-7")
        self.assertIn("[ACCEPTED by Trupp 3, ETA 15 min]", marker.find("detail/remarks").text)
        done = check_event(self, cot.task_events(task(state="done"), now=NOW)[0])
        self.assertEqual(done.get("stale"), done.get("time"))                     # expired: TAK removes the marker

    def test_hazard_request_sector_and_track(self):
        hazard = check_event(self, cot.task_events(task(type="hazard"), now=NOW)[0])
        self.assertEqual((hazard.get("type"), hazard.find("detail/color").get("argb")), ("b-m-p-s-m", cot.ORANGE))
        request = check_event(self, cot.task_events(task(type="request"), now=NOW)[0])
        self.assertEqual(request.find("detail/color").get("argb"), cot.BLUE)

        def record(kind, message, text="x"):
            return {"type": kind, "from": "!a1b2c3d4", "message": message, "text": {"en": text, "de": ""}}
        colors = {}
        for status in ("cleared", "partial", "blocked"):
            (xml,) = cot.message_events(record("sector", {"pos": FOUND["pos"], "sector": {"sector": 12, "status": status}}), now=NOW)
            sector = check_event(self, xml)
            colors[status] = sector.find("detail/color").get("argb")
            self.assertEqual(sector.get("uid"), "RescueCodec.sector-a1b2c3d4-12")
        self.assertEqual(len(set(colors.values())), 3)
        self.assertEqual(cot.message_events(record("sector", {"sector": {"sector": 12, "status": "cleared"}}), now=NOW), [])   # no position
        (xml,) = cot.message_events(record("track", {"track": {"points": [{"lat": 50.1, "lon": 7.1}, {"lat": 50.2, "lon": 7.2}]}}), now=NOW)
        unit = check_event(self, xml)
        self.assertEqual((unit.get("type"), unit.find("point").get("lat")), ("a-f-G-U-C", "50.20000"))

    def test_no_position_means_no_event_unless_the_node_position_is_known(self):
        self.assertEqual(cot.task_events(task(pos=None), now=NOW), [])
        (xml,) = cot.task_events(task(pos=None), now=NOW, fallback_pos=lambda node: {"lat": 50.0, "lon": 7.0})
        event = check_event(self, xml)
        self.assertEqual(event.find("point").get("ce"), "1000.0")                 # only the sender's area is known

    def test_text_and_ack_make_no_event_and_xml_is_escaped(self):
        self.assertEqual(cot.message_events({"type": "text", "from": "!a1b2c3d4", "message": {}, "text": {"en": "x", "de": ""}}, now=NOW), [])
        (xml,) = cot.task_events(task(text={"en": 'Fire <b>& "smoke"', "de": ""}), now=NOW)
        self.assertEqual(check_event(self, xml).find("detail/remarks").text, 'Fire <b>& "smoke" [OPEN]')

    def test_accuracy_sets_the_circular_error(self):
        for acc, ce in (("gps", "10.0"), ("gps_poor", "50.0"), ("estimated", "100.0"), ("area", "1000.0")):
            event = check_event(self, cot.task_events(task(pos={**FOUND["pos"], "acc": acc}), now=NOW)[0])
            self.assertEqual(event.find("point").get("ce"), ce)


class CotSending(unittest.TestCase):
    def test_udp_sink_sends_the_event_as_one_datagram(self):
        receiver = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        receiver.bind(("127.0.0.1", 0))
        receiver.settimeout(2)
        sink = cot.UdpMulticastSink("127.0.0.1", receiver.getsockname()[1])
        connector = cot.CotConnector([sink], stale_min=60)
        connector.handle("task", task())
        data, _ = receiver.recvfrom(65535)
        check_event(self, data)
        self.assertEqual(connector.sent, 1)
        connector.handle("health", {})
        connector.handle("task", task(pos=None))
        self.assertEqual(connector.sent, 1)
        sink.close()
        receiver.close()

    def test_tcp_sink_reaches_a_tak_server(self):
        server = socket.socket()
        server.bind(("127.0.0.1", 0))
        server.listen(1)
        received = bytearray()

        def serve():
            conn, _ = server.accept()
            conn.settimeout(2)
            received.extend(conn.recv(65535))
            conn.close()
        thread = threading.Thread(target=serve, daemon=True)
        thread.start()
        sink = cot.TcpSink("127.0.0.1", server.getsockname()[1], retry_s=0.05)
        sink.send(cot.task_events(task(), now=NOW)[0])
        thread.join(3)
        self.assertIn(b"RescueCodec.a1b2c3d4-7", bytes(received))
        sink.close()
        server.close()

    def test_a_dead_tak_server_never_blocks(self):
        sink = cot.TcpSink("127.0.0.1", 1, retry_s=0.05)
        started = threading.Event()
        threading.Thread(target=lambda: (sink.send(b"<event/>"), started.set()), daemon=True).start()
        self.assertTrue(started.wait(1))
        self.assertFalse(sink.connected)


class FakeMqttClient:
    def __init__(self):
        self.published, self.will, self.on_connect, self.on_disconnect = [], None, None, None
        self.started = False

    def username_pw_set(self, user, password): self.credentials = (user, password)
    def will_set(self, topic, payload, qos=0, retain=False): self.will = (topic, payload, retain)
    def reconnect_delay_set(self, **kw): pass
    def max_queued_messages_set(self, n): pass
    def connect_async(self, host, port, keepalive=60): self.address = (host, port)
    def loop_start(self): self.started = True
    def loop_stop(self): self.started = False
    def disconnect(self): pass
    def publish(self, topic, payload, qos=0, retain=False): self.published.append((topic, payload, retain))


class MqttTests(unittest.TestCase):
    def setUp(self):
        self.client = FakeMqttClient()
        self.states = []
        self.mqtt = MqttConnector("broker", 1884, username="u", password="p", client_factory=lambda: self.client, on_state=self.states.append)

    def test_connect_sets_status_and_credentials(self):
        self.mqtt.start()
        self.assertEqual((self.client.address, self.client.credentials, self.client.started), (("broker", 1884), ("u", "p"), True))
        self.assertEqual(self.client.will, ("rescuecodec/v2/status", "offline", True))
        self.client.on_connect(self.client, None, {}, SimpleReason(False), None)
        self.assertTrue(self.mqtt.connected)
        self.assertEqual(self.client.published[-1], ("rescuecodec/v2/status", "online", True))
        self.client.on_disconnect(self.client, None, {}, SimpleReason(False), None)
        self.assertEqual(self.states, [True, False])

    def test_refused_connection_is_not_connected(self):
        self.client.on_connect(self.client, None, {}, SimpleReason(True), None)
        self.assertFalse(self.mqtt.connected)

    def test_topics_and_payloads(self):
        record = {"seq": 1, "type": "found", "text": {"en": "Person found: child.", "de": "Person gefunden: Kind."}, "message": {"id": 7}}
        self.mqtt.handle("message", record)
        self.mqtt.handle("task", task())
        self.mqtt.handle("task", {"type": None})
        topics = [t for t, _, _ in self.client.published]
        self.assertEqual(topics, ["rescuecodec/v2/messages", "rescuecodec/v2/found", "rescuecodec/v2/readable", "rescuecodec/v2/tasks"])
        import json
        self.assertEqual(json.loads(self.client.published[1][1]), record)
        self.assertEqual(self.client.published[2][1], b"Person found: child.")

    def test_utf8_is_kept(self):
        self.mqtt.handle("message", {"type": "text", "text": {"en": "Tür auf", "de": "Tür auf"}})
        self.assertIn("Tür auf".encode(), self.client.published[0][1])


class SimpleReason:
    def __init__(self, failure): self.is_failure = failure


class ThroughTheGateway(unittest.TestCase):
    def test_mqtt_and_cot_get_what_the_gateway_receives(self):
        from rescuecodec.gateway.hub import ConnectorHub
        client = FakeMqttClient()
        mqtt = MqttConnector("b", client_factory=lambda: client)
        events = []
        with gateway() as gw:
            hub = ConnectorHub(gw.service.bus)
            hub.add(mqtt.handle)
            hub.add(lambda event, data: events.append((event, data)))
            hub.start()
            gw.hear({"id": 7, **FOUND})
            wait_for(lambda: any(t == "rescuecodec/v2/tasks" for t, _, _ in client.published))
            gw.post("/v2/tasks/0000e002-7/ack", status="accepted", eta=5)
            wait_for(lambda: sum(1 for t, _, _ in client.published if t == "rescuecodec/v2/tasks") >= 2)
            hub.stop()
        topics = {t for t, _, _ in client.published}
        self.assertTrue({"rescuecodec/v2/messages", "rescuecodec/v2/found", "rescuecodec/v2/ack", "rescuecodec/v2/readable"} <= topics)

    def test_a_broken_connector_does_not_stop_the_others(self):
        from rescuecodec.gateway.hub import ConnectorHub
        got = []
        with gateway() as gw:
            hub = ConnectorHub(gw.service.bus)
            hub.add(lambda event, data: 1 / 0)
            hub.add(lambda event, data: got.append(event))
            hub.start()
            gw.hear({"id": 7, **FOUND})
            wait_for(lambda: "task" in got)
            hub.stop()


class CommandLine(unittest.TestCase):
    def parse(self, *argv):
        return config_from_args(build_parser().parse_args(list(argv)))

    def test_exactly_one_radio(self):
        self.assertEqual(self.parse("--tcp", "localhost:4404").tcp, "localhost:4404")
        self.assertEqual(self.parse("--serial", "auto").serial, "auto")
        self.assertTrue(self.parse("--emulator").emulator)
        for argv in ([], ["--tcp", "a:1", "--emulator"], ["--tcp", "a:1", "--serial", "auto"]):
            with self.assertRaises(ValueError):
                self.parse(*argv)

    def test_options(self):
        cfg = self.parse("--emulator", "--mqtt", "broker:1884", "--cot-tcp", "tak:8087", "--text-over-air", "both",
                         "--team", "DLRG 2", "--port", "9000", "--db", ":memory:")
        self.assertEqual((cfg.mqtt, cfg.cot, cfg.cot_tcp, cfg.text_over_air, cfg.team, cfg.http_port),
                         ("broker:1884", True, "tak:8087", "both", "DLRG 2", 9000))
        self.assertEqual(self.parse("--emulator").text_over_air, "off")          # off by default: it doubles airtime
        self.assertEqual(self.parse("--emulator").host, "127.0.0.1")            # local only by default
        with self.assertRaises(ValueError):
            self.parse("--emulator", "--team", "a team name that is too long")

    def test_environment_variables(self):
        import os
        from unittest import mock
        with mock.patch.dict(os.environ, {"RESCUECODEC_TCP": "localhost:4403", "RESCUECODEC_MQTT": "b", "RESCUECODEC_COT": "1"}):
            cfg = self.parse()
        self.assertEqual((cfg.tcp, cfg.mqtt, cfg.cot), ("localhost:4403", "b", True))

    def test_build_app_wires_connectors_without_starting_anything(self):
        cfg = self.parse("--emulator", "--cot", "--cot-udp", "127.0.0.1:6999", "--db", ":memory:")
        app = build_app(cfg)
        self.assertIsNotNone(app.state.service)
        self.assertFalse(app.state.service.radio.connected)


if __name__ == "__main__":
    unittest.main()
