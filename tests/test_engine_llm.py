"""Optional Ollama path: grounded by the rules, 4 s timeout, always falls back. No network is used."""
import json
import os
import socket
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from unittest import mock

from rescuecodec.engine import extract
from rescuecodec.engine.llm import extract_with_llm, output_schema, understand


def fake(content):
    def chat(url, payload, timeout):
        chat.calls.append((url, payload, timeout))
        return {"message": {"content": json.dumps(content) if not isinstance(content, str) else content}}
    chat.calls = []
    return chat


class LlmPath(unittest.TestCase):
    def test_off_without_model(self):
        with mock.patch.dict(os.environ, {"RESCUECODEC_OLLAMA_MODEL": ""}):
            chat = fake({})
            r = understand("Kind gefunden", chat=chat)
            self.assertEqual(r.method, "rules")
            self.assertEqual(chat.calls, [])

    def test_schema_is_json_and_has_all_bodies(self):
        schema = json.loads(json.dumps(output_schema()))
        for body in ("found", "sector", "request", "hazard", "mayday", "text"):
            self.assertIn(body, schema["properties"])
        self.assertIn("hypothermia", schema["properties"]["found"]["properties"]["injuries"]["items"]["enum"])

    def test_grounded_addition_is_kept(self):
        # the rules know "kind" and "unterkühlt"; the model adds 'bleeding' with a real quote
        chat = fake({"found": {"age": "child", "injuries": ["hypothermia", "bleeding"], "count": 2},
                     "evidence": [{"field": "injuries", "value": "bleeding", "quote": "Schürfwunde blutet"}]})
        r = extract_with_llm("Kind gefunden, unterkühlt, Schürfwunde blutet", model="m", chat=chat)
        self.assertEqual(r.method, "ollama+rules")
        self.assertEqual(r.message["found"], {"age": "child", "injuries": ["hypothermia", "bleeding"]})
        self.assertTrue(any("count=2" in n for n in r.notes))   # '2' is not in the text: dropped
        url, payload, timeout = chat.calls[0]
        self.assertEqual(timeout, 4.0)
        self.assertIn("format", payload)
        self.assertEqual(payload["model"], "m")

    def test_ungrounded_values_are_dropped(self):
        chat = fake({"found": {"age": "child", "injuries": ["burn"], "needs": ["helicopter"]}, "evidence": []})
        r = extract_with_llm("Kind gefunden, ansprechbar", model="m", chat=chat)
        self.assertEqual(r.message, {"found": {"age": "child", "consciousness": "conscious"}})   # rule fields stay

    def test_negated_quote_is_not_grounded(self):
        chat = fake({"found": {"injuries": ["bleeding"]},
                     "evidence": [{"field": "injuries", "value": "bleeding", "quote": "Schürfwunde"}]})
        r = extract_with_llm("Person gefunden, keine Schürfwunde", model="m", chat=chat)
        self.assertEqual(r.message, {"found": {}})

    def test_model_cannot_change_the_type(self):
        chat = fake({"mayday": {"reason": "lost"}, "evidence": []})
        r = extract_with_llm("Kind gefunden, ansprechbar", model="m", chat=chat)
        self.assertEqual(r.type, "found")
        self.assertEqual(r.method, "rules")

    def test_model_helps_where_rules_found_no_type(self):
        chat = fake({"found": {"age": "child"},
                     "evidence": [{"field": "type", "value": "found", "quote": "bulduk"},
                                  {"field": "age", "value": "child", "quote": "çocuğu"}]})
        r = extract_with_llm("Kayıp çocuğu bulduk", model="m", chat=chat)
        self.assertFalse(r.unsupported)
        self.assertEqual(r.message, {"found": {"age": "child"}})

    def test_type_without_cue_or_quote_is_rejected(self):
        chat = fake({"found": {"age": "child"}, "evidence": []})
        r = extract_with_llm("Das Wetter ist schön", model="m", chat=chat)
        self.assertTrue(r.unsupported)
        self.assertEqual(r.message, {"text": {"text": "Das Wetter ist schön"}})

    def test_position_and_id_never_come_from_the_model(self):
        chat = fake({"found": {"age": "child"}, "id": 5, "pos": {"lat": 1, "lon": 2}, "evidence": []})
        r = extract_with_llm("Kind gefunden", model="m", chat=chat)
        self.assertNotIn("pos", r.message)
        self.assertNotIn("id", r.message)

    def test_deliberate_rejections_skip_the_model(self):
        chat = fake({"found": {}})
        r = extract_with_llm("Zwei Personen gefunden, eine bewusstlos, die andere ansprechbar", model="m", chat=chat)
        self.assertTrue(r.unsupported)
        self.assertEqual(chat.calls, [])

    def test_bad_output_falls_back(self):
        for content in ("not json", "[]", {"found": {"age": "martian"}, "evidence": []}):
            r = extract_with_llm("Kind gefunden", model="m", chat=fake(content))
            self.assertEqual(r.message, {"found": {"age": "child"}})

    def test_errors_fall_back(self):
        def boom(url, payload, timeout):
            raise TimeoutError("slow")
        r = extract_with_llm("Kind gefunden", model="m", chat=boom)
        self.assertEqual(r.method, "rules")
        self.assertTrue(any("TimeoutError" in n for n in r.notes))

    def test_env_model_is_used(self):
        with mock.patch.dict(os.environ, {"RESCUECODEC_OLLAMA_MODEL": "qwen3:8b", "RESCUECODEC_OLLAMA_TIMEOUT_S": "2"}):
            chat = fake({"found": {"age": "child"}, "evidence": []})
            r = understand("Kind gefunden", chat=chat)
            self.assertEqual(chat.calls[0][1]["model"], "qwen3:8b")
            self.assertEqual(chat.calls[0][2], 2.0)
            self.assertEqual(r.message, {"found": {"age": "child"}})
            self.assertEqual(understand("Kind gefunden", use_llm=False, chat=chat).method, "rules")

    def test_real_timeout_with_slow_local_server(self):
        class Slow(BaseHTTPRequestHandler):
            def do_POST(self):
                time.sleep(3)
            def log_message(self, *a):
                pass
        server = HTTPServer(("127.0.0.1", 0), Slow)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        env = {"RESCUECODEC_OLLAMA_URL": f"http://127.0.0.1:{server.server_port}", "RESCUECODEC_OLLAMA_TIMEOUT_S": "0.5"}
        try:
            with mock.patch.dict(os.environ, env):
                t0 = time.monotonic()
                r = extract_with_llm("Kind gefunden", model="m")
                self.assertLess(time.monotonic() - t0, 2.5)
                self.assertEqual(r.message, {"found": {"age": "child"}})
                self.assertEqual(r.method, "rules")
        finally:
            server.shutdown()

    def test_unreachable_server_falls_back(self):
        s = socket.socket(); s.bind(("127.0.0.1", 0)); port = s.getsockname()[1]; s.close()
        with mock.patch.dict(os.environ, {"RESCUECODEC_OLLAMA_URL": f"http://127.0.0.1:{port}"}):
            r = extract_with_llm("Kind gefunden", model="m")
        self.assertEqual(r.message, {"found": {"age": "child"}})


if __name__ == "__main__":
    unittest.main()
