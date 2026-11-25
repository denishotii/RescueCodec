"""Every JSON the gateway returns is checked against the schemas in spec/api/openapi.yaml."""
import unittest

import yaml

try:
    from jsonschema import Draft202012Validator, FormatChecker
    from referencing import Registry, Resource
    from referencing.jsonschema import DRAFT202012
except ImportError:                                   # pip install jsonschema
    Draft202012Validator = None

from gateway_fixtures import FOUND, gateway, wait_for
from rescuecodec.gateway.app import openapi_path


@unittest.skipIf(Draft202012Validator is None, "jsonschema is not installed")
class Contract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        doc = yaml.safe_load(openapi_path().read_text(encoding="utf-8"))
        cls.registry = Registry().with_resource("urn:openapi", Resource.from_contents(doc, default_specification=DRAFT202012))

    def check(self, schema: str, instance):
        validator = Draft202012Validator({"$ref": f"urn:openapi#/components/schemas/{schema}"}, registry=self.registry,
                                         format_checker=FormatChecker())
        errors = sorted(validator.iter_errors(instance), key=lambda e: list(e.path))
        self.assertEqual(errors, [], f"{schema}: {errors[0].message if errors else ''} at {list(errors[0].path) if errors else ''}")

    def test_all_responses_match_the_schemas(self):
        with gateway() as gw:
            c = gw.client
            self.check("Health", c.get("/v2/health").json())
            gw.hear({"id": 7, **FOUND})
            gw.hear({"id": 8, "mayday": {"reason": "injured", "persons": 1}, "pos": {"lat": 50.7, "lon": 7.2, "acc": "estimated"}})
            gw.hear({"id": 9, "hazard": {"kind": "rockfall", "severity": "high", "radius": 50}, "pos": {"lat": 50.7, "lon": 7.2}})
            gw.hear({"id": 10, "request": {"resource": "searchers", "count": 10, "urgency": "now"}})
            gw.hear({"sector": {"sector": 12, "status": "partial", "coverage": 60}})
            gw.hear({"time": "14:30", "track": {"points": [{"lat": 50.69212, "lon": 7.21345, "s": 5}, {"lat": 50.6925, "lon": 7.2139, "s": 35}]}})
            gw.hear({"text": {"text": "Treffpunkt am Gasthaus"}})
            gw.radio.inject_text("!0000e003", "Normal Meshtastic text from the official app")      # codec.version 0
            gw.hear({"priority": "critical", "id": 11, "found": {"count": 2}})
            sent = c.post("/v2/messages", json={"message": FOUND})
            self.assertEqual(sent.status_code, 202)
            self.check("MessageRecord", sent.json())
            wait_for(lambda: c.get(f"/v2/messages/{sent.json()['seq']}").json()["status"] == "delivered")
            self.check("MessageRecord", c.post("/v2/messages", json={"message": FOUND, "dry_run": True}).json())
            self.check("MessageRecord", c.post("/v2/text", json={"text": "hello"}).json())
            self.check("ChatMode", c.put("/v2/channels/RescueCodec/mode", json={"mode": "compressed"}).json())
            packed = c.post("/v2/text", json={"text": "We are at the hut, two people with us, waiting for the vehicle"}).json()
            self.assertTrue(packed["codec"]["packed"])
            self.check("MessageRecord", packed)
            gw.hear({"id": 12, "text": {"text": "Bin unterwegs, Ankunft in ca. 10 Minuten", "dict": 1}})
            self.check("ChatMode", c.get("/v2/channels/RescueCodec/mode").json())
            self.check("ChatMode", c.put("/v2/channels/RescueCodec/mode", json={"mode": "compressed"}).json())
            self.check("ChatMode", c.get("/v2/nodes/!0000e002/mode").json())
            self.check("Health", c.get("/v2/health").json())
            for node in c.get("/v2/nodes").json()["items"]:
                self.check("Node", node)
            listing = c.get("/v2/messages?limit=1000").json()
            self.assertGreaterEqual(len(listing["items"]), 9)
            for record in listing["items"]:
                self.check("MessageRecord", record)
            self.check("MessageRecord", c.get("/v2/messages/1").json())
            for task in c.get("/v2/tasks").json()["items"]:
                self.check("Task", task)
            self.check("Task", c.get("/v2/tasks/0000e002-7").json())
            acked = c.post("/v2/tasks/0000e002-7/ack", json={"status": "accepted", "eta": 15, "team": "Trupp 3"})
            self.assertEqual(acked.status_code, 202)
            self.check("Task", acked.json()["task"])
            self.check("MessageRecord", acked.json()["record"])
            self.check("Stats", c.get("/v2/stats").json())
            for node in c.get("/v2/nodes").json()["items"]:
                self.check("Node", node)
            self.check("FeatureCollection", c.get("/v2/export.geojson").json())
            self.check("EncodeResult", c.post("/v2/encode", json={"message": {"id": 7, **FOUND}}).json())
            decoded = c.post("/v2/decode", json={"hex": "08071a0908b8e6ea041082875832080841100518012802"}).json()
            self.check("Message", decoded["message"])
            self.check("Text2", decoded["text"])

    def test_compact_and_domain_frames_match_the_schemas(self):
        from rescuecodec.v2 import encode_compact, encode_domain
        with gateway(compact_text=True) as gw:
            c = gw.client
            self.check("Health", c.get("/v2/health").json())
            self.check("ChatMode", c.put("/v2/channels/RescueCodec/mode", json={"mode": "compressed"}).json())
            gw.radio.inject("!0000e002", encode_domain("Missing person found, alive.", "sar"), channel=0, packet_id=5)
            gw.radio.inject("!0000e002", bytes.fromhex("0309fe41f1f43900b79000"), channel=0, packet_id=6)   # unknown
            gw.radio.inject("!0000e002", encode_compact("hello world"), channel=0, packet_id=7)
            for text in ("Check airway and breathing.", "hello world", "Ok"):
                self.check("MessageRecord", c.post("/v2/text", json={"text": text}).json())
                self.check("ExtractResult", c.post("/v2/extract", json={"text": text, "channel": "RescueCodec"}).json())
            items = wait_for(lambda: len(c.get("/v2/messages").json()["items"]) >= 6 and c.get("/v2/messages").json()["items"])
            self.assertEqual({i["codec"].get("form") for i in items}, {"domain", "lean", None})
            for record in items:
                self.check("MessageRecord", record)
            decoded = c.post("/v2/decode", json={"hex": encode_domain("Apply a tourniquet.", "medical").hex()}).json()
            self.check("Message", decoded["message"])

    def test_errors_match_the_error_schema(self):
        with gateway() as gw:
            c = gw.client
            gw.hear({"id": 7, **FOUND})
            responses = [c.post("/v2/messages", json={"message": {"found": {"colour": 1}}}), c.get("/v2/messages/99"),
                         c.get("/v2/tasks/0000e002-99"), c.post("/v2/tasks/0000e002-7/ack", json={"status": "done"}),
                         c.post("/v2/messages", content=b"{", headers={"content-type": "application/json"}), c.get("/v2/nope")]
            gw.service.queue.budget.duty_cycle = 0.0001
            responses.append(c.post("/v2/messages", json={"message": FOUND}))
            gw.radio.stop()
            responses.append(c.post("/v2/text", json={"text": "x"}))
            self.assertEqual([r.status_code for r in responses], [400, 404, 404, 409, 400, 404, 429, 503])
            for response in responses:
                self.check("Error", response.json())

    def test_websocket_events_match_the_schemas(self):
        with gateway() as gw:
            with gw.client.websocket_connect("/v2/stream") as ws:
                hello = ws.receive_json()
                self.check("Health", hello["data"]["health"])
                gw.hear({"id": 7, **FOUND})
                gw.post("/v2/messages", message=FOUND)
                seen = {}
                while {"message", "task", "message_status"} - set(seen) or seen["message_status"]["status"] != "delivered":
                    event = ws.receive_json()
                    seen[event["event"]] = event["data"]
                self.check("MessageRecord", seen["message"])
                self.check("Task", seen["task"])
                self.assertEqual(set(seen["message_status"]), {"seq", "status", "error", "updated"})
            event = gw.service.stats()
            self.check("Stats", event)


if __name__ == "__main__":
    unittest.main()
