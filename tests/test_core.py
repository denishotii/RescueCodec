import unittest

from rescuecodec.encoding.airtime import LoRaConfig, airtime_seconds
from rescuecodec.protocol.codec import PacketError, decode_confirmation, decode_report, decode_text, encode_report, encode_text, confirmation_packet
from rescuecodec.protocol.fragments import Reassembler, fragment
from rescuecodec.schemas import Assistance, Breathing, Consciousness, Equipment, PersonFoundReport, Urgency
from rescuecodec.semantic import extract_report
from rescuecodec.transport.base import Packet
from rescuecodec.transport.emulator import MeshEmulator
from rescuecodec.transport.gateway import PhysicalToVirtualGateway


def sample():
    return PersonFoundReport(consciousness=Consciousness.UNCONSCIOUS, breathing=Breathing.YES,
        equipment_needed=[Equipment.OXYGEN_MASK, Equipment.AED], request=Assistance.MEDICAL,
        urgency=Urgency.CRITICAL, location=(50.9375, 6.9603), location_quality=3, person_count=2)


class CoreTests(unittest.TestCase):
  def test_gateway_forwards_only_valid_reports_without_radio_tx(self):
    class FakeInterface:
      def getMyNodeInfo(self): return {"user": {"id": "!gateway"}}
    class FakeRadio:
      interface = FakeInterface()
      serial_path = "/test/serial"
      def connect(self): pass
      def disconnect(self): pass
      def subscribe(self, node, callback): self.callback = callback
      def emit(self, packet): self.callback(packet)
    mesh = MeshEmulator(); mesh.connect(); seen=[]; mesh.subscribe("C", seen.append)
    gateway = PhysicalToVirtualGateway(mesh); fake = FakeRadio(); gateway.radio = fake
    gateway.start()
    wire = encode_report(sample(), 91)
    fake.emit(Packet("!remote", "!gateway", wire, 8))
    self.assertEqual(len(seen), 1); self.assertEqual(seen[0].source, "!remote")
    self.assertEqual(seen[0].hops, ("!remote", "B", "C"))
    fake.emit(Packet("!remote", "!gateway", b"bad packet", 9))
    self.assertEqual(gateway.metrics["unsupported_or_malformed"], 1)
    self.assertFalse(gateway.capabilities["radio_tx_enabled"])
    gateway.stop()

  def test_local_request_accept_completion_flow(self):
    from rescuecodec.api import ActionRequest, SendRequest, accept, complete, send
    record = send(SendRequest(source="A", destination="C", report=sample()))
    self.assertEqual(record["status"], "delivered")
    self.assertEqual(record["path"], ("A", "B", "C"))
    self.assertEqual(accept(record["request_id"], ActionRequest(node="C"))["status"], "accepted")
    self.assertEqual(complete(record["request_id"], ActionRequest(node="C"))["status"], "completed")

  def test_binary_roundtrip_optional_fields(self):
    original = sample(); packet = encode_report(original, 0x12345678, 1_700_000_000)
    decoded, mid, timestamp = decode_report(packet)
    self.assertEqual(decoded, original); self.assertEqual(mid, 0x12345678); self.assertEqual(timestamp, 1_700_000_000)
    self.assertLess(len(packet), 32)

  def test_person_count_optional_field(self):
    report = PersonFoundReport(person_count=3)
    decoded, _, _ = decode_report(encode_report(report, 12))
    self.assertEqual(decoded.person_count, 3)

  def test_responder_count_is_distinct_from_found_person_count(self):
    text = "I found the person. He is unconscious and needs an oxygen mask; we need at least two people."
    extracted = extract_report(text, use_ollama=False)
    self.assertTrue(extracted["supported"])
    self.assertEqual(extracted["report"].responders_needed_min, 2)
    self.assertIsNone(extracted["report"].person_count)
    decoded, _, _ = decode_report(encode_report(extracted["report"], 13))
    self.assertEqual(decoded.responders_needed_min, 2)

  def test_schema_capacity_distinguishes_people_found_from_help_requested(self):
    resources = extract_report("Person found; we need at least two people.", use_ollama=False)
    casualties = extract_report("Two people found. One is unconscious, the other is conscious.", use_ollama=False)
    vague = extract_report("Person found; needs help.", use_ollama=False)
    self.assertTrue(resources["supported"])
    self.assertEqual(resources["report"].responders_needed_min, 2)
    self.assertFalse(casualties["supported"])
    self.assertFalse(vague["supported"])

  def test_evidence_grounding_keeps_unknown_separate_from_negation_and_urgency(self):
    from rescuecodec.semantic import _ground_report
    report = PersonFoundReport(consciousness=Consciousness.UNKNOWN, breathing=Breathing.YES,
        request=Assistance.NONE, urgency=Urgency.CRITICAL)
    result = _ground_report(report, "I found the person; breathing status cannot be determined, and they do not need oxygen.")
    self.assertEqual(result.breathing, Breathing.UNKNOWN)
    self.assertEqual(result.request, Assistance.UNKNOWN)
    self.assertEqual(result.urgency, Urgency.UNKNOWN)
    no_breathing = _ground_report(PersonFoundReport(breathing=Breathing.UNKNOWN), "Person gefunden, Bewusstsein unbekannt, atmet nicht.")
    self.assertEqual(no_breathing.breathing, Breathing.NO)
    urgent = _ground_report(PersonFoundReport(urgency=Urgency.UNKNOWN), "The person needs help urgently.")
    critical = _ground_report(PersonFoundReport(urgency=Urgency.UNKNOWN), "The person needs help immediately.")
    self.assertEqual(urgent.urgency, Urgency.URGENT)
    self.assertEqual(critical.urgency, Urgency.CRITICAL)


  def test_binary_rejects_bad_version_and_truncation(self):
    packet = encode_report(sample(), 4)
    with self.assertRaises(PacketError): decode_report(packet[:-1])
    with self.assertRaises(PacketError): decode_report(b"\x02" + packet[1:])


  def test_coordinates_validated(self):
    with self.assertRaises(ValueError): PersonFoundReport(location=(91, 0))


  def test_confirmation_packet_roundtrip(self):
    self.assertEqual(decode_confirmation(confirmation_packet(1234, 1)), (1234, 1))

  def test_text_fallback_roundtrip_and_utf8(self):
    self.assertEqual(decode_text(encode_text("Hilfe benötigt", 778)), ("Hilfe benötigt", 778))


  def test_fragments_reassemble_out_of_order_and_duplicates(self):
    content = bytes(range(256)) * 3
    parts = fragment(content, 44, packet_budget=51)
    reassembly = Reassembler()
    result = None
    for part in [parts[2], parts[0], parts[0], *parts[3:], parts[1]]:
        result = reassembly.add(part) or result
    self.assertEqual(result, content); self.assertFalse(reassembly.pending)


  def test_fragment_expiry(self):
    packet = fragment(b"long enough", 8, packet_budget=9)[0]
    r = Reassembler(expiration_seconds=5)
    r.add(packet, now=1)
    self.assertEqual(r.expire(now=7), [8])


  def test_mesh_multihop_and_partition(self):
    mesh = MeshEmulator(seed=3); mesh.connect(); seen=[]
    mesh.subscribe("C", seen.append)
    packet = mesh.send("A", "C", b"\x00binary")
    self.assertEqual(seen[0].payload, b"\x00binary"); self.assertEqual(packet.hops, ("A", "B", "C"))
    mesh.set_link("B", "C", False)
    mesh.send("A", "C", b"unreachable")
    self.assertEqual(len(seen), 1); self.assertEqual(mesh.metrics["lost"], 1)


  def test_heuristic_handles_negation_and_unknown(self):
    result = extract_report("I found a person who is not unconscious and does not need oxygen.", use_ollama=False)
    self.assertEqual(result["report"].consciousness, Consciousness.CONSCIOUS)
    self.assertEqual(result["report"].equipment_needed, [])
    uncertain = extract_report("Found a person; breathing status unknown.", use_ollama=False)
    self.assertEqual(uncertain["report"].breathing, Breathing.UNKNOWN)

  def test_coordinates_only_used_when_reported(self):
    stated = extract_report("Person found near 50.9375, 6.9603", use_ollama=False)["report"]
    absent = extract_report("Person found near the entrance", use_ollama=False)["report"]
    self.assertEqual(stated.location, (50.9375, 6.9603))
    self.assertIsNone(absent.location)


  def test_airtime_increases_with_packet_size(self):
    conf = LoRaConfig(spreading_factor=11, bandwidth_hz=250_000)
    self.assertLess(airtime_seconds(30, conf), airtime_seconds(50, conf))


if __name__ == "__main__": unittest.main()
