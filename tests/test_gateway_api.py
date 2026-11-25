"""Gateway API on the in-memory emulator: sending, receiving, tasks, errors, docs."""
import json
import time
import unittest
from pathlib import Path
import tempfile

from gateway_fixtures import FOUND, PEER, gateway, wait_for
from rescuecodec.engine.sendqueue import AirtimeBudget
from rescuecodec.gateway.radio.base import RadioPacket
from rescuecodec.v2 import encode

HEX_FOUND_ID7 = "08071a0908b8e6ea041082875832080841100518012802"


def message_hex(msg):
    return encode(msg).hex()


class Basics(unittest.TestCase):
    def test_health(self):
        with gateway() as gw:
            body = gw.client.get("/v2/health").json()
            self.assertEqual(body["status"], "ok")
            self.assertEqual(body["spec_version"], "2.1.0")
            self.assertEqual(body["radio"]["kind"], "sim")
            self.assertEqual(body["radio"]["port"], 256)
            self.assertFalse(body["mqtt"]["connected"])

    def test_health_without_radio_is_degraded_but_200(self):
        with gateway() as gw:
            gw.radio.stop()
            response = gw.client.get("/v2/health")
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()["status"], "degraded")
            self.assertIsNone(response.json()["radio"]["node"])

    def test_unknown_endpoint_is_a_json_404(self):
        with gateway() as gw:
            response = gw.client.get("/v2/nope")
            self.assertEqual(response.status_code, 404)
            self.assertEqual(response.json()["error"]["code"], "not_found")

    def test_encode_matches_the_spec_example(self):
        with gateway() as gw:
            body = gw.post("/v2/encode", message={"id": 7, **FOUND}).json()
            self.assertEqual(body["hex"], HEX_FOUND_ID7)
            self.assertEqual(body["size"], 23)
            self.assertEqual(body["text"]["en"], "Person found: child, conscious, hypothermia, fracture, needs stretcher, 2 more helpers needed. Position 50.69212, 7.21345.")
            self.assertEqual(body["text_size"], 122)
            self.assertEqual(body["airtime_s"]["source"], "estimated")
            self.assertEqual(body["airtime_s"]["value"], 0.6)
            self.assertEqual(body["text_airtime_s"]["value"], 1.34)

    def test_decode_v2_and_v1(self):
        with gateway() as gw:
            v2 = gw.post("/v2/decode", hex=HEX_FOUND_ID7).json()
            self.assertEqual(v2["v"], 2)
            self.assertNotIn("v", v2["message"])
            self.assertEqual(v2["message"]["id"], 7)
            vectors = json.loads((Path(__file__).resolve().parents[1] / "spec" / "vectors" / "codec.json").read_text())
            case = vectors["v1"][0]
            v1 = gw.post("/v2/decode", hex=case["hex"]).json()
            self.assertEqual(v1["v"], 1)
            self.assertEqual(v1["message"]["v"], 1)

    def test_decode_and_encode_errors(self):
        with gateway() as gw:
            self.assertEqual(gw.post("/v2/decode", hex="zz").status_code, 400)
            bad = gw.post("/v2/decode", hex="0000")
            self.assertEqual(bad.status_code, 400)
            self.assertEqual(bad.json()["error"]["code"], "unsupported_version")
            bad = gw.post("/v2/encode", message={"found": {"count": 300}})
            self.assertEqual(bad.json()["error"]["code"], "invalid_value")
            self.assertEqual(bad.json()["error"]["field"], "found.count")


class Sending(unittest.TestCase):
    def test_send_found_goes_queued_sent_delivered(self):
        with gateway() as gw:
            response = gw.post("/v2/messages", message=FOUND)
            self.assertEqual(response.status_code, 202)
            record = response.json()
            self.assertEqual(record["direction"], "out")
            self.assertEqual(record["type"], "found")
            self.assertEqual(record["status"], "queued")
            self.assertEqual(record["message"]["id"], 1)            # the gateway assigned the id
            self.assertEqual(record["key"], "0000e001-1")
            self.assertEqual(record["task_key"], "0000e001-1")
            self.assertEqual(record["codec"]["size"], 23)
            self.assertEqual(record["priority"], "urgent")
            self.assertIsNone(record["radio"])
            final = wait_for(lambda: (r := gw.client.get(f"/v2/messages/{record['seq']}").json())["status"] == "delivered" and r)
            self.assertEqual(final["from"], "!0000e001")
            second = gw.post("/v2/messages", message={**FOUND, "pos": {"lat": 50.7, "lon": 7.2}}).json()
            self.assertEqual(second["message"]["id"], 2)            # the next message gets the next id

    def test_ids_count_up_and_are_used_as_given(self):
        with gateway() as gw:
            a = gw.post("/v2/messages", message={"pos": {"lat": 1.0, "lon": 2.0}, "hazard": {"kind": "fire"}}).json()
            b = gw.post("/v2/messages", message={"id": 500, "mayday": {"reason": "lost"}}).json()
            c = gw.post("/v2/messages", message={"sector": {"sector": 4, "status": "cleared"}}).json()
            self.assertEqual(a["message"]["id"], 1)
            self.assertEqual(b["message"]["id"], 500)
            self.assertEqual(c["message"]["id"], 2)                  # every message but an Ack gets an id (SPEC section 14)
            self.assertEqual(c["key"], "0000e001-2")

    def test_the_same_text_twice_is_two_messages(self):
        with gateway(text_mode="compressed") as gw:                   # packed RescueCodec texts on port 256
            first = gw.post("/v2/text", text="Wir brauchen dringend Hilfe am Waldweg hinter der Brücke").json()
            second = gw.post("/v2/text", text="Wir brauchen dringend Hilfe am Waldweg hinter der Brücke").json()
            self.assertEqual((first["codec"]["version"], second["codec"]["version"]), (2, 2))
            self.assertNotEqual(first["seq"], second["seq"])
            self.assertNotEqual(first["codec"]["hex"], second["codec"]["hex"])     # different ids, so no dedupe at the receiver

    def test_dry_run_sends_nothing(self):
        with gateway() as gw:
            record = gw.post("/v2/messages", message=FOUND, dry_run=True).json()
            self.assertEqual(record["seq"], 0)
            self.assertEqual(record["status"], "dry_run")
            self.assertEqual(gw.client.get("/v2/messages").json()["items"], [])
            self.assertEqual(gw.radio.mesh.metrics["sent"], 0)

    def test_invalid_messages_give_400_with_codec_codes(self):
        with gateway() as gw:
            cases = [
                ({"found": {"colour": "red"}}, "unknown_key", "found.colour"),
                ({"pos": {"lat": 1, "lon": 2}}, "no_body", None),
                ({"pos": {"lat": 91, "lon": 2}, "found": {}}, "invalid_value", "pos.lat"),
                ({"found": {}, "hazard": {"kind": "fire"}}, "multiple_bodies", None),
                ({"hazard": {}}, "missing_field", "hazard.kind"),
            ]
            for message, code, field in cases:
                response = gw.post("/v2/messages", message=message)
                self.assertEqual(response.status_code, 400, message)
                error = response.json()["error"]
                self.assertEqual(error["code"], code, message)
                if field:
                    self.assertEqual(error["field"], field)
            self.assertEqual(gw.client.get("/v2/messages").json()["items"], [])

    def test_bad_requests(self):
        with gateway() as gw:
            for response in (gw.client.post("/v2/messages", content=b"not json", headers={"content-type": "application/json"}),
                             gw.client.post("/v2/messages", json={"msg": {}}),
                             gw.client.post("/v2/messages", json={"message": FOUND, "dry_run": "yes"}),
                             gw.client.post("/v2/messages", json=[1, 2]),
                             gw.client.get("/v2/messages?limit=0"),
                             gw.client.get("/v2/messages?direction=sideways")):
                self.assertEqual(response.status_code, 400)
                self.assertEqual(response.json()["error"]["code"], "bad_request")

    def test_no_radio_gives_503(self):
        with gateway() as gw:
            gw.radio.stop()
            response = gw.post("/v2/messages", message=FOUND)
            self.assertEqual(response.status_code, 503)
            self.assertEqual(response.json()["error"]["code"], "radio_unavailable")
            self.assertEqual(gw.post("/v2/messages", message=FOUND, dry_run=True).status_code, 202)

    def test_airtime_limit_gives_429_but_mayday_goes_through(self):
        with gateway() as gw:
            gw.service.queue.budget = AirtimeBudget(window_s=3600, duty_cycle=0.0001)      # 0.36 s per hour
            blocked = gw.post("/v2/messages", message=FOUND)
            self.assertEqual(blocked.status_code, 429)
            body = blocked.json()["error"]
            self.assertEqual(body["code"], "airtime_limit")
            self.assertGreater(body["retry_after_s"], 0)
            self.assertEqual(blocked.headers["Retry-After"], str(body["retry_after_s"]))
            self.assertEqual(gw.post("/v2/messages", message={"mayday": {"reason": "injured"}}).status_code, 202)

    def test_mayday_is_sent_before_older_routine_messages(self):
        with gateway() as gw:
            gw.radio.stop()
            gw.radio.start()
            order = []
            original = gw.radio.send
            gw.radio.send = lambda payload, **kw: (order.append(payload), original(payload, **kw))[1]
            with gw.service.lock:                                   # hold the sender back while we queue three messages
                for message in ({"track": {"points": [{"lat": 1.0, "lon": 2.0}]}},
                                {"sector": {"sector": 2, "status": "partial"}},
                                {"mayday": {"reason": "injured"}}):
                    gw.service._queue_message(message, want_ack=False)
            wait_for(lambda: len(order) == 3)
            self.assertEqual(order[0], encode({"id": 3, "mayday": {"reason": "injured"}}))

    def test_text_and_also_plain(self):
        with gateway(text_mode="normal") as gw:
            response = gw.post("/v2/text", text="Treffpunkt jetzt am Gasthaus", also_plain=True)
            self.assertEqual(response.status_code, 202)
            record = response.json()
            self.assertEqual(record["type"], "text")
            self.assertEqual(record["priority"], "normal")
            wait_for(lambda: gw.radio.plain_texts)
            self.assertEqual(gw.radio.plain_texts, ["Treffpunkt jetzt am Gasthaus"])
            self.assertEqual(gw.post("/v2/text", text="x" * 181).status_code, 400)
            self.assertEqual(gw.post("/v2/text", text="").status_code, 400)
            self.assertEqual(gw.post("/v2/text", text=5).status_code, 400)

    def test_text_over_air_out_posts_the_readable_line(self):
        with gateway(text_over_air="out") as gw:
            gw.post("/v2/messages", message=FOUND)
            line = wait_for(lambda: gw.radio.plain_texts)
            self.assertTrue(line[0].startswith("[RC] Person found: child"))     # marked as a copy (SPEC section 2.1)


    def test_text_over_air_in_relays_what_we_hear_for_stock_apps(self):
        with gateway(text_over_air="in", text_lang="de") as gw:
            gw.hear({"id": 7, **FOUND})
            line = wait_for(lambda: gw.radio.plain_texts)
            self.assertTrue(line[0].startswith("[RC] Person gefunden: Kind"))

    def test_text_over_air_is_off_by_default(self):
        with gateway() as gw:
            gw.hear({"id": 7, **FOUND})
            gw.post("/v2/messages", message=FOUND)
            wait_for(lambda: gw.client.get("/v2/messages/2").json()["status"] == "delivered")
            time.sleep(0.3)
            self.assertEqual(gw.radio.plain_texts, [])

    def test_long_text_with_position_goes_packed_and_whole(self):
        with gateway(text_mode="compressed") as gw:                   # as a packed RescueCodec text on port 256
            text = ("Fußweg über den Bach zerstört, nördlich gehts nicht weiter. " * 4).encode("utf-8")[:180].decode("utf-8", "ignore")
            response = gw.post("/v2/messages", message={"pos": {"lat": 52.52001, "lon": 13.40495, "acc": "estimated"},
                                                        "text": {"text": text}})
            self.assertEqual(response.status_code, 202)
            record = response.json()
            self.assertLess(record["codec"]["size"], 120)                # 180 bytes of words, packed with position and id
            self.assertEqual(record["message"]["text"], {"text": text, "dict": 1})     # nothing cut
            self.assertEqual(record["message"]["pos"]["lat"], 52.52001)

    def test_without_want_ack_the_status_stays_sent(self):
        with gateway() as gw:
            record = gw.post("/v2/messages", message=FOUND, want_ack=False).json()
            wait_for(lambda: gw.client.get(f"/v2/messages/{record['seq']}").json()["status"] == "sent")
            time.sleep(0.3)
            self.assertEqual(gw.client.get(f"/v2/messages/{record['seq']}").json()["status"], "sent")

    def test_no_answer_from_the_radio_fails_after_the_timeout(self):
        with gateway(ack_timeout_s=0.5) as gw:
            gw.radio.send = lambda payload, **kw: 1                  # a radio that never answers
            record = gw.post("/v2/messages", message=FOUND).json()
            failed = wait_for(lambda: (r := gw.client.get(f"/v2/messages/{record['seq']}").json())["status"] == "failed" and r, timeout=5)
            self.assertEqual(failed["error"], "no_ack")

    def test_a_radio_that_refuses_marks_the_message_failed(self):
        with gateway() as gw:
            def refuse(payload, **kw):
                raise RuntimeError("serial port closed")
            gw.radio.send = refuse
            record = gw.post("/v2/messages", message=FOUND).json()
            failed = wait_for(lambda: (r := gw.client.get(f"/v2/messages/{record['seq']}").json())["status"] == "failed" and r)
            self.assertEqual(failed["error"], "radio_error")


class Receiving(unittest.TestCase):
    def test_received_found_becomes_record_and_task(self):
        with gateway() as gw:
            gw.hear({"id": 7, **FOUND})
            record = gw.client.get("/v2/messages/1").json()
            self.assertEqual(record["direction"], "in")
            self.assertEqual(record["status"], "received")
            self.assertEqual(record["from"], PEER)
            self.assertEqual(record["key"], "0000e002-7")
            self.assertEqual(record["codec"], {"version": 2, "size": 23, "hex": message_hex({"id": 7, **FOUND})})
            self.assertEqual(record["radio"]["via"], "sim")
            self.assertEqual(record["radio"]["hops"], 0)
            self.assertIn("rssi", record["radio"])
            self.assertTrue(record["text"]["de"].startswith("Person gefunden"))
            tasks = gw.client.get("/v2/tasks").json()["items"]
            self.assertEqual([t["key"] for t in tasks], ["0000e002-7"])
            self.assertEqual(tasks[0]["state"], "open")
            self.assertEqual(tasks[0]["message_seq"], 1)
            self.assertEqual(tasks[0]["pos"], FOUND["pos"])

    def test_duplicate_is_dropped_and_garbage_is_ignored(self):
        with gateway() as gw:
            gw.hear({"id": 7, **FOUND})
            gw.hear({"id": 7, **FOUND})
            gw.radio.inject(PEER, b"\x00\x01\x02")
            gw.radio.inject(PEER, b"\xff" * 5)
            self.assertEqual(len(gw.client.get("/v2/messages").json()["items"]), 1)

    def test_v1_packet_is_decoded(self):
        vectors = json.loads((Path(__file__).resolve().parents[1] / "spec" / "vectors" / "codec.json").read_text())
        case = next(c for c in vectors["v1"] if "found" in c["decoded"])
        with gateway() as gw:
            gw.radio.inject(PEER, bytes.fromhex(case["hex"]))
            record = gw.client.get("/v2/messages/1").json()
            self.assertEqual(record["codec"]["version"], 1)
            self.assertEqual(record["message"]["v"], 1)
            self.assertEqual(record["type"], "found")

    def test_filters_and_paging(self):
        with gateway() as gw:
            gw.hear({"id": 1, **FOUND})
            gw.hear({"id": 2, "hazard": {"kind": "fire"}})
            gw.hear({"sector": {"sector": 3, "status": "cleared"}})
            everything = gw.client.get("/v2/messages").json()
            self.assertEqual([r["seq"] for r in everything["items"]], [1, 2, 3])
            self.assertEqual(everything["next_since_seq"], 3)
            only = gw.client.get("/v2/messages?type=hazard,sector").json()
            self.assertEqual([r["type"] for r in only["items"]], ["hazard", "sector"])
            page = gw.client.get("/v2/messages?since_seq=1&limit=1").json()
            self.assertEqual([r["seq"] for r in page["items"]], [2])
            self.assertEqual(page["next_since_seq"], 2)
            empty = gw.client.get("/v2/messages?since_seq=3").json()
            self.assertEqual(empty, {"items": [], "next_since_seq": 3})
            self.assertEqual(gw.client.get("/v2/messages?direction=out").json()["items"], [])

    def test_stats_count_what_happened(self):
        with gateway() as gw:
            gw.hear({"id": 1, **FOUND})
            stats = gw.client.get("/v2/stats").json()
            self.assertEqual(stats["messages_in"], {"value": 1, "unit": "count", "source": "measured"})
            self.assertEqual(stats["bytes_in"]["value"], 23)
            self.assertEqual(stats["text_equivalent_bytes"]["value"], 122)
            self.assertAlmostEqual(stats["size_ratio"]["value"], 122 / 23, places=2)
            self.assertEqual(stats["airtime_out_s"]["source"], "estimated")
            self.assertEqual(stats["airtime_limit_pct"]["value"], 10)
            self.assertEqual(stats["by_type"]["found"], {"count": 1, "bytes": 23})
            self.assertIsNone(stats["last_rssi"]["value"])            # the emulator has no radio readings

    def test_nodes(self):
        with gateway() as gw:
            gw.hear({"id": 1, **FOUND})
            nodes = {n["node"]: n for n in gw.client.get("/v2/nodes").json()["items"]}
            self.assertTrue(nodes["!0000e001"]["is_gateway"])
            self.assertFalse(nodes[PEER]["is_gateway"])
            self.assertIsNotNone(nodes[PEER]["last_heard"])


class Tasks(unittest.TestCase):
    def test_accept_then_done_goes_over_the_mesh(self):
        with gateway() as gw:
            sent = []
            original = gw.radio.send
            gw.radio.send = lambda payload, **kw: (sent.append(payload), original(payload, **kw))[1]
            gw.hear({"id": 7, **FOUND})
            response = gw.post("/v2/tasks/0000e002-7/ack", status="accepted", eta=15, team="Trupp 3")
            self.assertEqual(response.status_code, 202)
            body = response.json()
            self.assertEqual(body["task"]["state"], "accepted")
            self.assertEqual(body["task"]["acks"][0]["by"], "!0000e001")
            self.assertEqual(body["task"]["acks"][0]["team"], "Trupp 3")
            self.assertEqual(body["record"]["type"], "ack")
            self.assertEqual(body["record"]["task_key"], "0000e002-7")
            wait_for(lambda: sent)
            self.assertEqual(sent[0], encode({"ack": {"ref": 7, "node": PEER, "status": "accepted", "eta": 15, "team": "Trupp 3"}}))
            done = gw.post("/v2/tasks/0000e002-7/ack", status="done")
            self.assertEqual(done.json()["task"]["state"], "done")
            self.assertEqual(gw.client.get("/v2/tasks/0000e002-7").json()["state"], "done")
            self.assertEqual(gw.client.get("/v2/tasks?state=open").json()["items"], [])
            self.assertEqual(len(gw.client.get("/v2/tasks?state=done&type=found").json()["items"]), 1)

    def test_default_team_label_and_repeat(self):
        with gateway(team="Base 1") as gw:
            gw.hear({"id": 7, **FOUND})
            first = gw.post("/v2/tasks/0000e002-7/ack", status="accepted").json()
            self.assertEqual(first["task"]["acks"][0]["team"], "Base 1")
            again = gw.post("/v2/tasks/0000e002-7/ack", status="accepted")      # repeating is allowed
            self.assertEqual(again.status_code, 202)
            self.assertEqual(len(again.json()["task"]["acks"]), 2)

    def test_bad_moves_and_errors(self):
        with gateway() as gw:
            gw.hear({"id": 7, **FOUND})
            self.assertEqual(gw.post("/v2/tasks/0000e002-7/ack", status="done").status_code, 409)      # open -> done
            self.assertEqual(gw.post("/v2/tasks/0000e002-7/ack", status="done").json()["error"]["code"], "invalid_transition")
            self.assertEqual(gw.client.get("/v2/tasks/0000e002-7").json()["state"], "open")           # unchanged
            self.assertEqual(gw.post("/v2/tasks/0000e002-99/ack", status="accepted").status_code, 404)
            self.assertEqual(gw.client.get("/v2/tasks/0000e002-99").status_code, 404)
            self.assertEqual(gw.post("/v2/tasks/0000e002-7/ack", status="maybe").status_code, 400)
            self.assertEqual(gw.post("/v2/tasks/0000e002-7/ack", status="accepted", eta=0).status_code, 400)
            too_long = gw.post("/v2/tasks/0000e002-7/ack", status="accepted", team="a very long team name")
            self.assertEqual(too_long.status_code, 400)
            self.assertEqual(gw.client.get("/v2/tasks/0000e002-7").json()["state"], "open")           # a failed ack changes nothing
            gw.post("/v2/tasks/0000e002-7/ack", status="accepted")
            gw.post("/v2/tasks/0000e002-7/ack", status="done")
            self.assertEqual(gw.post("/v2/tasks/0000e002-7/ack", status="accepted").status_code, 409)  # done is final

    def test_ack_without_radio_is_503_and_changes_nothing(self):
        with gateway() as gw:
            gw.hear({"id": 7, **FOUND})
            gw.radio.stop()
            self.assertEqual(gw.post("/v2/tasks/0000e002-7/ack", status="accepted").status_code, 503)
            self.assertEqual(gw.client.get("/v2/tasks/0000e002-7").json()["state"], "open")

    def test_ack_from_the_mesh_updates_our_own_task(self):
        with gateway() as gw:
            record = gw.post("/v2/messages", message=FOUND).json()
            key = record["task_key"]
            gw.hear({"ack": {"ref": 1, "node": "!0000e001", "status": "accepted", "eta": 10, "team": "Demo 1"}})
            task = gw.client.get(f"/v2/tasks/{key}").json()
            self.assertEqual(task["state"], "accepted")
            self.assertEqual(task["acks"][0]["by"], PEER)
            self.assertEqual(task["acks"][0]["eta"], 10)
            self.assertEqual(task["acks"][0]["seq"], 2)
            gw.hear({"ack": {"ref": 1, "node": "!0000e001", "status": "done", "team": "Demo 1"}})
            self.assertEqual(gw.client.get(f"/v2/tasks/{key}").json()["state"], "done")

    def test_ack_that_arrives_before_the_task_is_kept(self):
        with gateway() as gw:
            gw.hear({"ack": {"ref": 7, "node": PEER, "status": "accepted"}})
            self.assertEqual(gw.client.get("/v2/tasks").json()["items"], [])      # a placeholder is not shown
            gw.hear({"id": 7, **FOUND})
            tasks = gw.client.get("/v2/tasks").json()["items"]
            self.assertEqual(tasks[0]["state"], "accepted")

    def test_our_decline_does_not_hide_another_teams_accept(self):
        with gateway() as gw:
            gw.hear({"id": 7, **FOUND})
            team_a = encode({"ack": {"ref": 7, "node": PEER, "status": "accepted"}})
            gw.service._on_packet(RadioPacket("!0000e003", "^all", 256, team_a))                     # team A takes it
            first = gw.post("/v2/tasks/0000e002-7/ack", status="declined").json()                   # we cannot
            self.assertEqual(first["task"]["state"], "accepted")
            again = gw.post("/v2/tasks/0000e002-7/ack", status="declined").json()   # the same Ack may still be queued
            self.assertEqual(again["task"]["state"], "accepted")
            self.assertEqual(gw.client.get("/v2/tasks/0000e002-7").json()["state"], "accepted")

    def test_tasks_are_sorted_critical_first(self):
        with gateway() as gw:
            gw.hear({"id": 1, "pos": {"lat": 1.0, "lon": 2.0}, "hazard": {"kind": "fire"}})
            gw.hear({"id": 2, "request": {"resource": "searchers"}})
            gw.hear({"id": 3, "mayday": {"reason": "lost"}})
            gw.hear({"id": 4, **FOUND})
            self.assertEqual([t["type"] for t in gw.client.get("/v2/tasks").json()["items"]],
                             ["mayday", "hazard", "found", "request"])        # urgent ones oldest first


class Export(unittest.TestCase):
    def test_geojson(self):
        with gateway() as gw:
            gw.hear({"id": 7, **FOUND})
            gw.hear({"time": "14:30", "track": {"points": [{"lat": 50.69212, "lon": 7.21345, "s": 5},
                                                          {"lat": 50.6925, "lon": 7.2139, "s": 35}]}})
            gw.hear({"text": {"text": "no position"}})
            gw.post("/v2/tasks/0000e002-7/ack", status="accepted")
            response = gw.client.get("/v2/export.geojson")
            self.assertEqual(response.headers["content-type"], "application/geo+json")
            data = response.json()
            self.assertEqual(data["type"], "FeatureCollection")
            point, line = data["features"]
            self.assertEqual(point["geometry"], {"type": "Point", "coordinates": [7.21345, 50.69212]})
            self.assertEqual(point["properties"]["state"], "accepted")
            self.assertEqual(point["properties"]["key"], "0000e002-7")
            self.assertEqual(point["properties"]["acc"], "gps")
            self.assertEqual(line["geometry"]["type"], "LineString")
            self.assertEqual(line["geometry"]["coordinates"][1], [7.2139, 50.6925])
            self.assertIsNone(line["properties"]["state"])
            only = gw.client.get("/v2/export.geojson?type=track").json()
            self.assertEqual(len(only["features"]), 1)
            self.assertEqual(gw.client.get("/v2/export.geojson?since_seq=2").json()["features"], [])


class Persistence(unittest.TestCase):
    def test_restart_keeps_records_tasks_and_id_counter(self):
        with tempfile.TemporaryDirectory() as tmp:
            db = str(Path(tmp) / "gw.db")
            with gateway(db=db) as gw:
                gw.hear({"id": 7, **FOUND})
                gw.post("/v2/tasks/0000e002-7/ack", status="accepted")
                own = gw.post("/v2/messages", message=FOUND).json()
                self.assertEqual(own["message"]["id"], 1)
                wait_for(lambda: gw.client.get(f"/v2/messages/{own['seq']}").json()["status"] == "delivered")
                before = gw.client.get("/v2/stats").json()
            with gateway(db=db) as gw:
                after = gw.client.get("/v2/stats").json()
                for key in ("messages_in", "messages_out", "bytes_in", "bytes_out", "text_equivalent_bytes"):
                    self.assertEqual(after[key]["value"], before[key]["value"], key)   # stats do not restart from zero
                self.assertGreater(after["airtime_last_hour_pct"]["value"], 0)
                self.assertEqual(len(gw.client.get("/v2/messages").json()["items"]), 3)
                self.assertEqual(gw.client.get("/v2/tasks/0000e002-7").json()["state"], "accepted")
                self.assertEqual(gw.client.get("/v2/tasks/0000e001-1").json()["state"], "open")
                self.assertEqual(gw.post("/v2/messages", message=FOUND).json()["message"]["id"], 2)
                gw.hear({"id": 7, **FOUND})                   # the dedupe memory survived as well
                self.assertEqual(len(gw.client.get("/v2/messages").json()["items"]), 4)


class Docs(unittest.TestCase):
    def test_docs_are_served_offline(self):
        with gateway() as gw:
            page = gw.client.get("/docs")
            self.assertEqual(page.status_code, 200)
            self.assertNotIn("http://", page.text.replace("http://www.w3.org", ""))
            self.assertNotIn("https://", page.text)
            self.assertEqual(gw.client.get("/docs-assets/swagger-ui-bundle.js").status_code, 200)
            self.assertEqual(gw.client.get("/docs-assets/swagger-ui.css").status_code, 200)
            spec = gw.client.get("/openapi.json").json()
            self.assertEqual(spec["openapi"], "3.1.0")
            self.assertEqual(spec["servers"][0]["url"], "http://testserver")
            self.assertIn("/v2/messages", spec["paths"])
            self.assertIn("openapi: 3.1.0", gw.client.get("/openapi.yaml").text)

    def test_every_path_of_the_spec_is_implemented(self):
        import yaml
        spec = yaml.safe_load(Path(gateway_openapi()).read_text())
        with gateway() as gw:
            routes = {(m, r.path) for r in gw.client.app.routes for m in getattr(r, "methods", ()) or ()}
            for path, ops in spec["paths"].items():
                for method in ops:
                    self.assertIn((method.upper(), path), routes, f"{method} {path}")

    def test_placeholder_and_static_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            with gateway(static_dir=tmp) as gw:
                page = gw.client.get("/")
                self.assertEqual(page.status_code, 200)
                self.assertIn("gateway is running", page.text)
                self.assertIn("/docs", page.text)
            (Path(tmp) / "assets").mkdir()
            (Path(tmp) / "index.html").write_text("<html>DASH</html>")
            (Path(tmp) / "assets" / "app.js").write_text("console.log(1)")
            (Path(tmp) / "assets" / "w.mjs").write_text("export default 1")
            (Path(tmp) / "tiles.pmtiles").write_bytes(bytes(range(256)))
            secret = Path(tmp).parent / "secret.txt"
            secret.write_text("secret")
            try:
                with gateway(static_dir=tmp) as gw:
                    self.assertEqual(gw.client.get("/").text, "<html>DASH</html>")
                    self.assertEqual(gw.client.get("/some/route").text, "<html>DASH</html>")        # single-page app route
                    self.assertEqual(gw.client.get("/assets/app.js").text, "console.log(1)")
                    self.assertIn("javascript", gw.client.get("/assets/w.mjs").headers["content-type"])
                    self.assertEqual(gw.client.get("/assets/missing.js").status_code, 404)
                    ranged = gw.client.get("/tiles.pmtiles", headers={"Range": "bytes=2-5"})       # the offline map needs ranges
                    self.assertEqual((ranged.status_code, ranged.content), (206, bytes([2, 3, 4, 5])))
                    for sneaky in ("/../secret.txt", "/%2e%2e/secret.txt", "/assets/../../secret.txt"):
                        self.assertNotEqual(gw.client.get(sneaky).text, "secret", sneaky)
                    self.assertEqual(gw.client.get("/v2/health").status_code, 200)                  # the API still wins
            finally:
                secret.unlink()

    def test_cors_only_for_local_origins(self):
        with gateway() as gw:
            local = gw.client.get("/v2/health", headers={"Origin": "http://localhost:5173"})
            self.assertEqual(local.headers.get("access-control-allow-origin"), "http://localhost:5173")
            foreign = gw.client.get("/v2/health", headers={"Origin": "https://evil.example"})
            self.assertNotIn("access-control-allow-origin", foreign.headers)


def gateway_openapi():
    from rescuecodec.gateway.app import openapi_path
    return openapi_path()


class Stream(unittest.TestCase):
    def test_hello_replay_live_and_ping(self):
        with gateway() as gw:
            gw.hear({"id": 1, **FOUND})
            gw.hear({"id": 2, "hazard": {"kind": "fire"}})
            with gw.client.websocket_connect("/v2/stream?since_seq=1") as ws:
                hello = ws.receive_json()
                self.assertEqual(hello["event"], "hello")
                self.assertEqual(hello["data"]["last_seq"], 2)
                self.assertEqual(hello["data"]["health"]["status"], "ok")
                replay = ws.receive_json()                      # only seq 2: we said since_seq=1
                self.assertEqual((replay["event"], replay["data"]["seq"]), ("message", 2))
                gw.hear({"id": 3, "request": {"resource": "drone"}})
                live = ws.receive_json()
                self.assertEqual((live["event"], live["data"]["seq"], live["data"]["type"]), ("message", 3, "request"))
                task = ws.receive_json()
                self.assertEqual((task["event"], task["data"]["key"]), ("task", "0000e002-3"))
                ws.send_json({"op": "ping"})
                self.assertEqual(ws.receive_json(), {"event": "pong", "data": {}})

    def test_status_events_for_sent_messages(self):
        with gateway() as gw:
            with gw.client.websocket_connect("/v2/stream") as ws:
                self.assertEqual(ws.receive_json()["event"], "hello")
                gw.post("/v2/messages", message=FOUND)
                events = []
                while not any(e["event"] == "message_status" and e["data"]["status"] == "delivered" for e in events):
                    events.append(ws.receive_json())
                names = [e["event"] for e in events]
                self.assertEqual(names[0], "message")
                self.assertIn("task", names)
                statuses = [e["data"]["status"] for e in events if e["event"] == "message_status"]
                self.assertEqual(statuses, ["sent", "delivered"])

    def test_plain_get_is_400(self):
        with gateway() as gw:
            response = gw.client.get("/v2/stream")
            self.assertEqual(response.status_code, 400)


if __name__ == "__main__":
    unittest.main()
