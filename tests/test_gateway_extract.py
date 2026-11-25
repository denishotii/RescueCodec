"""POST /v2/extract: a typed sentence -> the report it would send, for the dashboard composer. Nothing is sent."""
import unittest

from gateway_fixtures import gateway
from rescuecodec.v2 import encode

try:
    from jsonschema import Draft202012Validator
    from referencing import Registry, Resource
    from referencing.jsonschema import DRAFT202012
except ImportError:                                   # pip install jsonschema
    Draft202012Validator = None

FOUND_DE = "Kind gefunden, ansprechbar, unterkühlt. Wir brauchen eine Trage."


class Extract(unittest.TestCase):
    def test_a_found_report_with_chips_and_both_sizes(self):
        with gateway() as gw:
            r = gw.post("/v2/extract", text=FOUND_DE)
            self.assertEqual(r.status_code, 200)
            body = r.json()
            self.assertEqual(body["type"], "found")
            self.assertFalse(body["unsupported"])
            self.assertEqual(body["lang"], "de")
            self.assertEqual(body["message"], {"found": {"age": "child", "consciousness": "conscious",
                                                         "injuries": ["hypothermia"], "needs": ["stretcher"]}})
            self.assertEqual([f["text"]["en"] for f in body["fields"]], ["child", "conscious", "hypothermia", "needs stretcher"])
            self.assertEqual(body["fields"][0], {"field": "found.age", "code": "child", "text": {"en": "child", "de": "Kind"}})
            self.assertEqual(body["text"]["en"], "Person found: child, conscious, hypothermia, needs stretcher.")
            # the gateway adds the next id when it sends; the size counts it
            next_id = gw.service.store.peek_message_id()
            self.assertEqual(body["coded_bytes"], len(encode({"id": next_id, **body["message"]})))
            self.assertEqual(body["text_bytes"], len(FOUND_DE.encode("utf-8")))
            self.assertLess(body["coded_bytes"], body["text_bytes"])

    def test_a_request_shows_count_and_resource_as_one_chip(self):
        with gateway() as gw:
            body = gw.post("/v2/extract", text="Need 2 stretchers now at the hut").json()
            self.assertEqual(body["type"], "request")
            self.assertEqual(body["message"]["request"], {"resource": "stretcher", "count": 2, "urgency": "now"})
            self.assertEqual([f["text"]["en"] for f in body["fields"]], ["2 x stretcher", "needed now"])
            self.assertEqual([f["text"]["de"] for f in body["fields"]], ["2 x Trage", "sofort"])

    def test_no_report_gives_a_text_with_the_same_words(self):
        with gateway() as gw:
            body = gw.post("/v2/extract", text="  Recall all   teams to the command post ").json()
            self.assertEqual(body["type"], "text")
            self.assertTrue(body["unsupported"])
            self.assertEqual(body["reason"], "no_supported_type")
            self.assertEqual(body["message"], {"text": {"text": "Recall all teams to the command post"}})
            self.assertEqual(body["fields"], [])
            # a text goes out as a plain Meshtastic text (the default): its UTF-8 bytes, no id
            self.assertEqual(body["coded_bytes"], len("Recall all teams to the command post"))
            self.assertEqual(body["text_bytes"], body["coded_bytes"])

    def test_a_text_without_text_as_meshtastic_counts_the_codec_bytes(self):
        with gateway(text_as_meshtastic=False) as gw:
            body = gw.post("/v2/extract", text="Status check").json()
            next_id = gw.service.store.peek_message_id()
            self.assertEqual(body["coded_bytes"], len(encode({"id": next_id, "text": {"text": "Status check"}})))

    def test_position_and_language_hint(self):
        with gateway() as gw:
            body = gw.post("/v2/extract", text="Mayday, two of us injured, 50.69212 7.21345", lang="en").json()
            self.assertEqual(body["type"], "mayday")
            self.assertEqual(body["lang"], "en")
            self.assertEqual(body["fields"][-1]["field"], "pos")
            self.assertEqual(body["fields"][-1]["code"], "50.69212, 7.21345")

    def test_nothing_is_sent_or_stored(self):
        with gateway(tx_per_minute=1) as gw:
            for _ in range(5):                                 # not limited by --tx-per-minute
                self.assertEqual(gw.post("/v2/extract", text=FOUND_DE).status_code, 200)
            self.assertEqual(gw.radio.sent, [])
            self.assertEqual(gw.client.get("/v2/messages").json()["items"], [])
            self.assertEqual(gw.service.store.peek_message_id(), 1)

    def test_bad_requests(self):
        with gateway() as gw:
            cases = [({}, "text"), ({"text": ""}, "text"), ({"text": "   "}, "text"), ({"text": 5}, "text"),
                     ({"text": "x" * 1001}, "text"), ({"text": "hi", "lang": "fr"}, "lang"),
                     ({"text": "hi", "channel": "x"}, "channel")]
            for body, field in cases:
                r = gw.client.post("/v2/extract", json=body)
                self.assertEqual(r.status_code, 400, body)
                self.assertEqual(r.json()["error"]["code"], "bad_request")
                self.assertEqual(r.json()["error"]["field"], field, body)
            self.assertEqual(gw.client.post("/v2/extract", json=["hi"]).status_code, 400)

    def test_needs_the_token_like_every_v2_call(self):
        with gateway(token="s3cret") as gw:
            self.assertEqual(gw.client.post("/v2/extract", json={"text": "hi"}).status_code, 401)
            ok = gw.client.post("/v2/extract?token=s3cret", json={"text": "hi"})
            self.assertEqual(ok.status_code, 200)
            ok = gw.client.post("/v2/extract", json={"text": "hi"}, headers={"Authorization": "Bearer s3cret"})
            self.assertEqual(ok.status_code, 200)

    @unittest.skipIf(Draft202012Validator is None, "jsonschema is not installed")
    def test_matches_the_schema(self):
        import yaml
        from rescuecodec.gateway.app import openapi_path
        doc = yaml.safe_load(openapi_path().read_text(encoding="utf-8"))
        registry = Registry().with_resource("urn:openapi", Resource.from_contents(doc, default_specification=DRAFT202012))
        validator = Draft202012Validator({"$ref": "urn:openapi#/components/schemas/ExtractResult"}, registry=registry)
        with gateway() as gw:
            for text in (FOUND_DE, "Need 2 stretchers now", "Rockfall on the north trail", "Sector 12 cleared",
                         "Mayday, two of us injured, 50.69212 7.21345", "Status check"):
                body = gw.post("/v2/extract", text=text).json()
                self.assertEqual(list(validator.iter_errors(body)), [], text)


if __name__ == "__main__":
    unittest.main()
