"""Send queue, airtime budget, dedupe, tasks and the airtime estimator."""
import unittest
from datetime import datetime, timedelta, timezone

from rescuecodec.encoding.airtime import (LoRaConfig, airtime_report, airtime_seconds, message_airtime_seconds,
                                          preset_config)
from rescuecodec.engine.sendqueue import AirtimeBudget, Deduper, SendQueue
from rescuecodec.engine.tasks import InvalidTransition, TaskBoard
from rescuecodec.v2 import decode, encode

TRACK = {"track": {"points": [{"lat": 50.69212, "lon": 7.21345}]}}


def track(i):
    return {"track": {"points": [{"lat": 50.0 + i / 1000, "lon": 7.0}]}}


class Airtime(unittest.TestCase):
    def test_defaults_are_meshtastic(self):
        self.assertEqual(LoRaConfig().preamble_symbols, 16)
        rep = airtime_report(11)
        self.assertEqual(rep["estimated_radio_bytes"], 11 + 23)
        self.assertTrue(rep["estimate_only"])
        self.assertEqual(rep["basis"], "estimated")

    def test_matches_spec_table(self):
        # spec/SPEC.md section 9 (LONG_FAST, SF11, 250 kHz, CR 4/5, 16 preamble symbols, 23 B overhead)
        for size, expected in ((11, 0.52), (23, 0.60), (34, 0.68), (8, 0.48), (48, 0.76), (64, 0.89), (122, 1.34), (270, 2.44)):
            self.assertAlmostEqual(message_airtime_seconds(size), expected, places=2, msg=size)

    def test_batching_beats_single_packets(self):
        self.assertGreater(10 * message_airtime_seconds(13) / message_airtime_seconds(48), 6.5)

    def test_slower_preset_is_longer(self):
        self.assertGreater(message_airtime_seconds(23, "LONG_SLOW"), message_airtime_seconds(23, "LONG_FAST"))
        self.assertLess(message_airtime_seconds(23, "SHORT_FAST"), message_airtime_seconds(23, "LONG_FAST"))
        with self.assertRaises(ValueError):
            preset_config("nope")

    def test_old_value_was_shorter(self):
        old = airtime_seconds(11 + 16, LoRaConfig(preamble_symbols=8))
        self.assertLess(old, message_airtime_seconds(11))


class Queue(unittest.TestCase):
    def test_mayday_goes_first_after_five_track_points(self):
        q = SendQueue()
        for i in range(5):
            q.put(track(i))
        q.put({"id": 9, "mayday": {"reason": "lost"}})
        self.assertEqual(q.pop(0).kind, "mayday")
        self.assertEqual({q.pop(0).kind for _ in range(5)}, {"track"})

    def test_full_order(self):
        q = SendQueue()
        q.put({"text": {"text": "hi"}})
        q.put(track(1))
        q.put({"sector": {"sector": 1, "status": "cleared"}})
        q.put({"id": 3, "hazard": {"kind": "fire"}})
        q.put({"id": 2, "request": {"resource": "medic"}})
        q.put({"id": 1, "found": {"age": "child"}})
        q.put({"id": 4, "mayday": {}})
        self.assertEqual([i.kind for i in q.pending()], ["mayday", "found", "request", "hazard", "sector", "track", "text"])

    def test_fifo_inside_rank_and_priority_inside_rank(self):
        q = SendQueue()
        a = q.put({"id": 1, "found": {}})
        b = q.put({"id": 2, "found": {}})
        c = q.put({"id": 3, "request": {"resource": "medic"}, "priority": "critical"})
        order = [i.seq for i in q.pending()]
        self.assertEqual(order, [c.seq, a.seq, b.seq])

    def test_item_has_wire_data(self):
        q = SendQueue()
        item = q.put({"id": 4, "mayday": {}})
        self.assertEqual(item.meshtastic_priority, 110)
        self.assertEqual(decode(item.payload), {"id": 4, "mayday": {}})
        self.assertEqual(item.airtime_basis, "estimated")
        self.assertGreater(item.airtime_s, 0.4)

    def test_duplicate_bytes_are_not_queued_twice(self):
        q = SendQueue()
        self.assertIsNotNone(q.put({"id": 1, "found": {}}))
        self.assertIsNone(q.put({"id": 1, "found": {}}))
        self.assertEqual(len(q), 1)

    def test_invalid_message_raises(self):
        from rescuecodec.v2 import CodecError
        with self.assertRaises(CodecError):
            SendQueue().put({"found": {}})  # id missing

    def test_budget_holds_routine_items_and_never_mayday(self):
        q = SendQueue(AirtimeBudget(window_s=100, duty_cycle=0.10))   # 10 s of airtime per 100 s
        for i in range(30):
            q.put(track(i))
        sent = 0
        while q.pop(0.0):
            sent += 1
        self.assertTrue(0 < sent < 30)
        self.assertLessEqual(q.budget.used_s(0), 0.7 * 10 + 1e-6)     # tracks stop at 70 percent
        self.assertEqual(len(q.held(0.0)), 30 - sent)
        q.put({"id": 1, "found": {}})
        self.assertEqual(q.pop(0.0).kind, "found")                    # found may use the rest
        for n in range(40):
            q.put({"id": 3, "request": {"resource": "medic", "count": n + 2}})
        while q.pop(0.0):
            pass
        q.put({"id": 5, "mayday": {"persons": 2}})
        self.assertEqual(q.pop(0.0).kind, "mayday")                   # still goes, even over budget
        self.assertGreater(q.budget.used_s(0), q.budget.budget_s * 0.99)

    def test_held_items_go_out_when_the_window_moves(self):
        q = SendQueue(AirtimeBudget(window_s=100, duty_cycle=0.10))
        for i in range(30):
            q.put(track(i))
        while q.pop(0.0):
            pass
        self.assertIsNone(q.pop(50.0))
        self.assertIsNotNone(q.pop(101.0))

    def test_budget_status_is_labelled(self):
        self.assertEqual(AirtimeBudget().status(0)["basis"], "estimated")
        self.assertEqual(AirtimeBudget().budget_s, 360.0)


class Dedupe(unittest.TestCase):
    def test_id_and_payload_rules(self):
        d = Deduper()
        found = {"id": 7, "found": {}}
        self.assertFalse(d.seen("!a", found, encode(found), 0))
        self.assertTrue(d.seen("!a", found, encode(found), 5))
        self.assertFalse(d.seen("!b", found, encode(found), 5))            # another sender
        self.assertTrue(d.seen("!a", {"id": 7, "found": {"count": 2}}, b"x", 3600))  # same id within 24 h
        self.assertFalse(d.seen("!a", found, encode(found), 24 * 3600 + 10))
        sector = {"sector": {"sector": 1, "status": "cleared"}}
        self.assertFalse(d.seen("!a", sector, encode(sector), 100))
        self.assertTrue(d.seen("!a", sector, encode(sector), 399))
        self.assertFalse(d.seen("!a", sector, encode(sector), 701))        # after 10 minutes


class Tasks(unittest.TestCase):
    NOW = datetime(2026, 11, 25, 14, 0, tzinfo=timezone.utc)

    def board_with_found(self):
        b = TaskBoard()
        t = b.on_message("!a1b2c3d4", {"id": 7, "found": {"age": "child"}, "pos": {"lat": 50.69212, "lon": 7.21345}}, self.NOW)
        return b, t

    def test_task_created_open(self):
        b, t = self.board_with_found()
        self.assertEqual((t.key, t.state, t.type, t.priority), ("a1b2c3d4-7", "open", "found", "urgent"))
        self.assertEqual(t.to_dict()["from"], "!a1b2c3d4")
        self.assertEqual(b.on_message("!a1b2c3d4", {"id": 7, "found": {}}).key, t.key)   # same task, not a new one
        self.assertEqual(len(b), 1)

    def test_only_four_types_are_tasks(self):
        b = TaskBoard()
        self.assertIsNone(b.on_message("!a1b2c3d4", {"sector": {"sector": 1, "status": "cleared"}}))
        self.assertIsNone(b.on_message("!a1b2c3d4", {"id": 1, "text": {"text": "x"}}))
        self.assertEqual(len(b), 0)

    def test_state_machine(self):
        b, t = self.board_with_found()
        ack = b.transition(t.key, "accepted", eta=15, team="Trupp 3", now=self.NOW)
        self.assertEqual(ack, {"ack": {"ref": 7, "node": "!a1b2c3d4", "status": "accepted", "eta": 15, "team": "Trupp 3"}})
        encode(ack)                                           # the Ack is a valid message
        self.assertEqual(t.state, "accepted")
        b.transition(t.key, "accepted")                       # repeating is allowed
        b.transition(t.key, "declined")
        b.transition(t.key, "accepted")
        b.transition(t.key, "done")
        self.assertEqual(t.state, "done")
        for status in ("accepted", "declined"):
            with self.assertRaises(InvalidTransition):
                b.transition(t.key, status)

    def test_open_cannot_jump_to_done(self):
        b, t = self.board_with_found()
        with self.assertRaises(InvalidTransition) as cm:
            b.transition(t.key, "done")
        self.assertEqual(cm.exception.code, "invalid_transition")
        self.assertEqual(t.state, "open")

    def test_unknown_key_and_status(self):
        b, t = self.board_with_found()
        with self.assertRaises(KeyError):
            b.transition("ffffffff-1", "accepted")
        with self.assertRaises(InvalidTransition):
            b.transition(t.key, "maybe")

    def test_incoming_acks_newest_wins_done_is_final(self):
        b, t = self.board_with_found()
        def ack(status, who="!0badc0de", **kw):
            return {"ack": {"ref": 7, "node": "!a1b2c3d4", "status": status, **kw}}
        b.on_message("!0badc0de", ack("accepted", eta=10, team="Trupp 3"), self.NOW)
        self.assertEqual(t.state, "accepted")
        b.on_message("!0000beef", ack("declined"), self.NOW + timedelta(seconds=5))
        self.assertEqual(t.state, "declined")
        b.on_message("!0badc0de", ack("done"), self.NOW + timedelta(seconds=9))
        b.on_message("!0000beef", ack("accepted"), self.NOW + timedelta(seconds=3))   # late, out of order
        self.assertEqual(t.state, "done")
        self.assertEqual(len(t.acks), 4)
        self.assertEqual(t.acks[0]["team"], "Trupp 3")

    def test_ack_before_message_is_unknown_then_resolved(self):
        b = TaskBoard()
        t = b.on_message("!0badc0de", {"ack": {"ref": 7, "node": "!a1b2c3d4", "status": "accepted"}}, self.NOW)
        self.assertEqual((t.state, t.type), ("unknown", None))
        with self.assertRaises(InvalidTransition):
            b.transition(t.key, "done")
        again = b.on_message("!a1b2c3d4", {"id": 7, "found": {}}, self.NOW)
        self.assertIs(again, t)
        self.assertEqual((t.state, t.type), ("accepted", "found"))

    def test_list_order_mayday_first(self):
        b = TaskBoard()
        b.on_message("!a1b2c3d4", {"id": 1, "request": {"resource": "medic"}}, self.NOW)
        b.on_message("!a1b2c3d4", {"id": 2, "found": {}}, self.NOW + timedelta(seconds=1))
        b.on_message("!0badc0de", {"id": 1, "mayday": {}}, self.NOW + timedelta(seconds=2))
        b.on_message("!a1b2c3d4", {"id": 3, "hazard": {"kind": "fire"}}, self.NOW + timedelta(seconds=3))
        self.assertEqual([t.type for t in b.list()], ["mayday", "found", "request", "hazard"])
        self.assertEqual([t.key for t in b.list({"open"})][0], "0badc0de-1")

    def test_roundtrip_through_codec(self):
        b = TaskBoard()
        wire = encode({"id": 7, "found": {"needs": ["stretcher"]}})
        t = b.on_message(0xA1B2C3D4, decode(wire), self.NOW)
        self.assertEqual(t.key, "a1b2c3d4-7")


if __name__ == "__main__":
    unittest.main()
