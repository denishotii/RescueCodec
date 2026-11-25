"""Generate spec/vectors/codec.json from the Python reference codec.

Run:  .venv/bin/python spec/tools/gen_vectors.py
The cases below are the source of truth. The hex values are produced by the
reference codec and were cross-checked with `protoc --decode` (see
tests/test_v2_vectors.py). Changing a case changes the conformance suite:
only add cases, never change the bytes of a released one.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from rescuecodec.protocol.codec import confirmation_packet, encode_report, encode_text  # noqa: E402
from rescuecodec.schemas import (Assistance, Breathing, Consciousness, Equipment,  # noqa: E402
                                 PersonFoundReport, Urgency)
from rescuecodec.v2 import CodecError, decode, decode_any, encode, render  # noqa: E402

POS = {"lat": 50.69212, "lon": 7.21345}  # Siebengebirge, near the Drachenfels
NODE = "!a1b2c3d4"

WALK = [(50.69212, 7.21345), (50.69250, 7.21390), (50.69291, 7.21422), (50.69330, 7.21470),
        (50.69362, 7.21530), (50.69401, 7.21571), (50.69447, 7.21600), (50.69490, 7.21641),
        (50.69521, 7.21702), (50.69560, 7.21744), (50.69600, 7.21790), (50.69633, 7.21840),
        (50.69670, 7.21881), (50.69711, 7.21920), (50.69750, 7.21966), (50.69789, 7.22010)]


def walk(n: int, times: bool = True, step: int = 30) -> list[dict]:
    pts = []
    for i, (lat, lon) in enumerate(WALK[:n]):
        p = {"lat": lat, "lon": lon}
        if times:
            p["s"] = 5 + i * step
        pts.append(p)
    return pts


VALID = [
    ("found-minimal", "Smallest valid found: id and an empty body.", {"id": 1, "found": {}}),
    ("found-pitch", "Pitch sentence: unconscious, oxygen mask, 2 helpers (no GPS).",
     {"id": 1, "found": {"consciousness": "unconscious", "needs": ["oxygen"], "responders": 2}}),
    ("found-typical-gps", "Typical found with GPS. Must be 25 bytes or less.",
     {"id": 7, "pos": POS, "found": {"age": "child", "consciousness": "conscious",
                                     "injuries": ["hypothermia", "fracture"], "needs": ["stretcher"],
                                     "responders": 2}}),
    ("found-full", "Every found field set, with time, priority and accuracy.",
     {"id": 300, "priority": "critical", "pos": {"lat": 50.70001, "lon": 7.19876, "acc": "estimated"},
      "time": "17:45",
      "found": {"count": 2, "age": "adult", "consciousness": "unconscious", "breathing": "difficult",
                "mobility": "cannot_walk", "injuries": ["hypothermia", "bleeding", "head", "spine"],
                "needs": ["stretcher", "medic", "helicopter", "warmth"], "responders": 6}}),
    ("found-senior-walks", "Senior, confused, can walk, needs warmth and water.",
     {"id": 12, "pos": POS, "found": {"age": "senior", "consciousness": "confused", "breathing": "normal",
                                      "mobility": "walks", "injuries": ["exhaustion"], "needs": ["warmth", "water"]}}),
    ("found-high-bits", "Flags above bit 6 take a 2-byte varint.",
     {"id": 13, "found": {"injuries": ["cardiac", "allergy"], "needs": ["aed", "oxygen", "radio"]}}),
    ("found-max-id", "Largest id (16383) still fits in 2 bytes.", {"id": 16383, "found": {"count": 1}}),
    ("found-group", "Three persons found, teenagers, trapped.",
     {"id": 44, "pos": POS, "found": {"count": 3, "age": "teen", "mobility": "trapped", "needs": ["rope", "light"]}}),
    ("found-southwest", "Negative latitude and longitude (zigzag encoding).",
     {"id": 2, "pos": {"lat": -33.86882, "lon": -151.2093}, "found": {"consciousness": "conscious"}}),
    ("found-null-island", "Position 0,0 is valid: the Position message is present but empty.",
     {"id": 3, "pos": {"lat": 0, "lon": 0}, "found": {}}),
    ("found-extreme-coords", "Coordinate limits 90 and -180.",
     {"id": 4, "pos": {"lat": 90.0, "lon": -180.0}, "found": {}}),
    ("found-midnight", "time 00:00 is minute 0 and must still be written (optional field).",
     {"id": 5, "time": "00:00", "found": {}}),
    ("found-2359", "Last minute of the day.", {"id": 6, "time": "23:59", "found": {"age": "adult"}}),
    ("found-routine-poor-gps", "Explicit non-default priority and weak GPS.",
     {"id": 8, "priority": "routine", "pos": {"lat": 50.69212, "lon": 7.21345, "acc": "gps_poor"},
      "found": {"consciousness": "conscious", "mobility": "walks"}}),
    ("found-area", "Rough area accuracy.",
     {"id": 9, "pos": {"lat": 50.7, "lon": 7.2, "acc": "area"}, "found": {"age": "child"}}),
    ("track-1", "Track with one point and no times.", {"track": {"points": walk(1, times=False)}}),
    ("track-10", "Ten points, 30 s apart, with times. The main airtime win.",
     {"id": 20, "time": "14:30", "track": {"points": walk(10)}}),
    ("track-16-max", "Largest track: 16 points.", {"time": "14:30", "track": {"points": walk(16)}}),
    ("track-zigzag", "Track going back and forth: negative deltas.",
     {"track": {"points": [{"lat": 50.5, "lon": 7.5}, {"lat": 50.49990, "lon": 7.49980},
                           {"lat": 50.50020, "lon": 7.50010}, {"lat": 50.49900, "lon": 7.50100}]}}),
    ("track-same-second", "Two points with the same time (dt 0).",
     {"time": "08:00", "track": {"points": [{"lat": 50.5, "lon": 7.5, "s": 0}, {"lat": 50.5001, "lon": 7.5, "s": 0}]}}),
    ("sector-cleared", "Sector 12 searched completely.", {"id": 30, "sector": {"sector": 12, "status": "cleared"}}),
    ("sector-partial", "Sector 4 partly searched, 60 percent.",
     {"sector": {"sector": 4, "status": "partial", "coverage": 60}}),
    ("sector-blocked-max", "Largest sector number, blocked.", {"sector": {"sector": 65535, "status": "blocked"}}),
    ("request-stretcher", "Request one stretcher.", {"id": 40, "pos": POS, "request": {"resource": "stretcher"}}),
    ("request-searchers", "Request 10 more searchers now, marked urgent.",
     {"id": 41, "priority": "urgent", "pos": POS, "request": {"resource": "searchers", "count": 10, "urgency": "now"}}),
    ("request-other", "Resource 16 (other).", {"id": 42, "request": {"resource": "other", "urgency": "later"}}),
    ("request-dog-team", "Dog team within 30 minutes.",
     {"id": 43, "pos": POS, "time": "15:10", "request": {"resource": "dog_team", "urgency": "soon"}}),
    ("hazard-rockfall", "Rockfall, high risk, 200 m radius.",
     {"id": 50, "pos": POS, "hazard": {"kind": "rockfall", "severity": "high", "radius": 200}}),
    ("hazard-hunting", "Hunting nearby, low risk, large radius.",
     {"id": 51, "pos": {"lat": 50.71, "lon": 7.25, "acc": "estimated"},
      "hazard": {"kind": "hunting", "severity": "low", "radius": 1500}}),
    ("hazard-minimal", "Hazard with only a kind.", {"id": 52, "hazard": {"kind": "blocked_path"}}),
    ("mayday-sos", "SOS button: empty Mayday with position. Valid.", {"id": 3, "pos": POS, "mayday": {}}),
    ("mayday-injured", "Team member injured, one person, with time.",
     {"id": 60, "pos": POS, "time": "16:05", "mayday": {"reason": "injured", "persons": 1}}),
    ("mayday-explicit-critical", "Explicit priority equal to the default is still written.",
     {"id": 61, "priority": "critical", "mayday": {"reason": "lost", "persons": 3}}),
    ("ack-accepted", "Command post accepts request 7 of node !a1b2c3d4, ETA 15 min.",
     {"ack": {"ref": 7, "node": NODE, "status": "accepted", "eta": 15, "team": "Trupp 3"}}),
    ("ack-done", "Minimal done.", {"ack": {"ref": 7, "node": NODE, "status": "done"}}),
    ("ack-declined-max-node", "Node number 0xffffffff, largest ref.",
     {"ack": {"ref": 16383, "node": "!ffffffff", "status": "declined"}}),
    ("ack-with-id", "An ack may carry its own id.",
     {"id": 99, "ack": {"ref": 41, "node": "!0000abcd", "status": "accepted", "team": "DLRG 2"}}),
    ("text-en", "Text fallback, English.", {"id": 70, "text": {"text": "Need more light at the car park"}}),
    ("text-de-umlauts", "Text fallback with umlauts (UTF-8, 2 bytes each).",
     {"text": {"text": "Brücke über den Bach ist gesperrt, Umweg über Löwenburg."}}),
    ("text-unicode", "Ukrainian and Arabic text.", {"text": {"text": "Допомога потрібна. نحتاج مساعدة"}}),
    ("text-max", "Text of exactly 180 bytes.", {"text": {"text": "A" * 180}}),
    ("forward-unknown-enum", "Unknown age value 6 (newer spec). Encoders may relay it as unknown_6.",
     {"id": 80, "found": {"age": "unknown_6", "injuries": ["bit_20"]}}),
]

INVALID_ENCODE = [
    ("no-body", "A message needs a body.", {"id": 1}, "no_body", None),
    ("two-bodies", "Only one body.", {"id": 1, "found": {}, "mayday": {}}, "multiple_bodies", None),
    ("unknown-key", "Unknown top-level key.", {"id": 1, "found": {}, "colour": "red"}, "unknown_key", "colour"),
    ("unknown-body-key", "Unknown key inside a body.", {"id": 1, "found": {"name": "Anna"}}, "unknown_key", "found.name"),
    ("id-too-big", "id above 16383.", {"id": 16384, "found": {}}, "invalid_value", "id"),
    ("id-negative", "Negative id.", {"id": -1, "found": {}}, "invalid_value", "id"),
    ("id-string", "id must be a number.", {"id": "7", "found": {}}, "invalid_value", "id"),
    ("found-without-id", "found needs an id.", {"found": {"age": "child"}}, "missing_field", "id"),
    ("mayday-without-id", "mayday needs an id.", {"mayday": {}}, "missing_field", "id"),
    ("lat-too-big", "Latitude above 90.", {"id": 1, "pos": {"lat": 90.00001, "lon": 7.0}, "found": {}},
     "invalid_value", "pos.lat"),
    ("lon-too-small", "Longitude below -180.", {"id": 1, "pos": {"lat": 50.0, "lon": -180.5}, "found": {}},
     "invalid_value", "pos.lon"),
    ("pos-missing-lon", "Position needs lat and lon.", {"id": 1, "pos": {"lat": 50.0}, "found": {}},
     "missing_field", "pos"),
    ("time-24", "time 24:00 does not exist.", {"id": 1, "time": "24:00", "found": {}}, "invalid_value", "time"),
    ("time-one-digit", 'time must be "HH:MM".', {"id": 1, "time": "9:30", "found": {}}, "invalid_value", "time"),
    ("count-too-big", "count above 255.", {"id": 1, "found": {"count": 256}}, "invalid_value", "found.count"),
    ("unknown-injury", "Unknown flag name.", {"id": 1, "found": {"injuries": ["sunburn"]}}, "invalid_value",
     "found.injuries"),
    ("unknown-consciousness", "Unknown enum name.", {"id": 1, "found": {"consciousness": "sleepy"}},
     "invalid_value", "found.consciousness"),
    ("age-unknown-too-big", "unknown_8 does not fit the 3-bit age field.", {"id": 1, "found": {"age": "unknown_8"}},
     "invalid_value", "found.age"),
    ("track-empty", "A track needs points.", {"track": {"points": []}}, "missing_field", "track.points"),
    ("track-17", "At most 16 points.", {"time": "10:00", "track": {"points": walk(16) + [{"lat": 50.7, "lon": 7.2, "s": 500}]}},
     "invalid_value", "track.points"),
    ("track-mixed-times", "s on every point or on none.",
     {"time": "10:00", "track": {"points": [{"lat": 50.5, "lon": 7.5, "s": 0}, {"lat": 50.5, "lon": 7.5}]}},
     "invalid_value", "track.points"),
    ("track-time-backwards", "s must not go down.",
     {"time": "10:00", "track": {"points": [{"lat": 50.5, "lon": 7.5, "s": 60}, {"lat": 50.5, "lon": 7.5, "s": 30}]}},
     "invalid_value", "track.points[1].s"),
    ("track-times-without-time", "Track times need Envelope.time.",
     {"track": {"points": [{"lat": 50.5, "lon": 7.5, "s": 0}]}}, "missing_field", "time"),
    ("sector-without-status", "Sector needs a status.", {"sector": {"sector": 3}}, "missing_field", "sector.status"),
    ("sector-zero", "Sector number 0 is not allowed.", {"sector": {"sector": 0, "status": "cleared"}},
     "missing_field", "sector.sector"),
    ("coverage-101", "Coverage is a percentage.", {"sector": {"sector": 3, "status": "partial", "coverage": 101}},
     "invalid_value", "sector.coverage"),
    ("request-without-resource", "Request needs a resource.", {"id": 1, "request": {"count": 2}}, "missing_field",
     "request.resource"),
    ("hazard-without-kind", "Hazard needs a kind.", {"id": 1, "hazard": {"severity": "high"}}, "missing_field",
     "hazard.kind"),
    ("hazard-radius-too-big", "Radius above 100 km.", {"id": 1, "hazard": {"kind": "fire", "radius": 100001}},
     "invalid_value", "hazard.radius"),
    ("ack-without-node", "Ack needs the node of the answered message.", {"ack": {"ref": 7, "status": "accepted"}},
     "missing_field", "ack.node"),
    ("ack-bad-node", 'Node must look like "!a1b2c3d4".', {"ack": {"ref": 7, "node": "a1b2c3d4", "status": "done"}},
     "invalid_value", "ack.node"),
    ("ack-without-status", "Ack needs a status.", {"ack": {"ref": 7, "node": NODE}}, "missing_field", "ack.status"),
    ("ack-ref-zero", "ref 0 is not a message id.", {"ack": {"ref": 0, "node": NODE, "status": "done"}},
     "missing_field", "ack.ref"),
    ("ack-team-too-long", "Team label at most 12 bytes.",
     {"ack": {"ref": 7, "node": NODE, "status": "accepted", "team": "Bergwacht 123"}}, "invalid_value", "ack.team"),
    ("ack-eta-too-big", "ETA at most 1440 minutes.", {"ack": {"ref": 7, "node": NODE, "status": "accepted", "eta": 1441}},
     "invalid_value", "ack.eta"),
    ("text-empty", "Text must not be empty.", {"text": {"text": ""}}, "missing_field", "text.text"),
    ("text-181", "Text at most 180 bytes.", {"text": {"text": "A" * 181}}, "invalid_value", "text.text"),
    ("text-180-umlauts-over", "90 umlauts plus one letter is 181 bytes.", {"text": {"text": "ä" * 90 + "a"}},
     "invalid_value", "text.text"),
    ("too-large", "180-byte text plus a full envelope is over 200 bytes.",
     {"id": 16383, "priority": "urgent", "pos": {"lat": -89.99999, "lon": -179.99999, "acc": "area"}, "time": "23:59",
      "text": {"text": "B" * 180}}, "too_large", None),
    ("v1-not-encodable", "Only v2 can be encoded.", {"v": 1, "id": 1, "found": {}}, "invalid_value", "v"),
]

# Decode-only cases: bytes another (newer or differently ordered) encoder may send.
DECODE_ONLY = [
    ("unknown-common-field", "Field 5 is unknown to v2.0: ignore it.", "0807" + "2801" + "3200",
     {"id": 7, "found": {}}),
    ("unknown-body-type", "Body field 14 is a newer message type: decode as unknown, do not reject.", "0807" + "7200",
     {"id": 7, "unknown": {}}),
    ("fields-out-of-order", "Decoders accept any field order.", "32020841" + "0807",
     {"id": 7, "found": {"consciousness": "conscious", "age": "child"}}),
    ("unknown-flag-bit", "Unknown injury bit 20 becomes bit_20.", "0807" + "3204" + "10808040",
     {"id": 7, "found": {"injuries": ["bit_20"]}}),
    ("reserved-state-bits", "State bits 9+ are reserved: ignore them.", "0807" + "3203" + "088104",
     {"id": 7, "found": {"consciousness": "conscious"}}),
    ("unknown-sector-status", "Status 9 from a newer version is kept as unknown_9.", "4204" + "0803" + "1009",
     {"sector": {"sector": 3, "status": "unknown_9"}}),
    ("non-packed-track", "Repeated numbers sent unpacked must also be accepted.", "3a04" + "0802" + "1004",
     {"track": {"points": [{"lat": 0.00001, "lon": 0.00002}]}}),
]

INVALID_DECODE = [
    ("empty", "Empty payload.", "", "empty", None),
    ("v1-marker-only", "0x01 is v1: a v2-only decoder says unsupported_version.", "01", "unsupported_version", None),
    ("reserved-00", "0x00 is reserved for a future version.", "000807", "unsupported_version", None),
    ("reserved-02", "0x02 is reserved for a future version.", "020807", "unsupported_version", None),
    ("reserved-07", "0x07 is reserved for a future version.", "07", "unsupported_version", None),
    ("truncated-varint", "Varint cut off.", "0887", "malformed", None),
    ("length-past-end", "Body length longer than the packet.", "08073205", "malformed", None),
    ("bad-utf8", "Text with invalid UTF-8.", "6a030a01ff", "malformed", None),
    ("id-only", "No body at all.", "0807", "no_body", None),
    ("found-id-zero", "found without id.", "3200", "missing_field", "id"),
    ("wire-id-too-big", "id 16384.", "08808001" + "3200", "invalid_value", "id"),
    ("lat-out-of-range", "Latitude 90.00001.", "0801" + "1a05" + "0882d1ca08" + "3200", "invalid_value", "pos.lat"),
    ("time-1440", "Minute 1440 does not exist.", "0801" + "20a00b" + "3200", "invalid_value", "time"),
    ("track-length-mismatch", "Two latitudes, one longitude.", "3a07" + "0a020202" + "12010a",
     "invalid_value", "track.points"),
    ("track-dt-without-time", "Times without Envelope.time.", "3a06" + "0802" + "1004" + "1800", "missing_field", "time"),
    ("ack-status-zero", "Ack without status.", "6207" + "0807" + "15d4c3b2a1", "missing_field", "ack.status"),
    ("ack-node-zero", "Ack without node.", "6204" + "0807" + "1801", "missing_field", "ack.node"),
    ("wire-text-181", "Text of 181 bytes.", "6ab801" + "0ab501" + "41" * 181, "invalid_value", "text.text"),
    ("wire-too-large", "234 bytes is above the Meshtastic payload limit.", "6ae701" + "0ae401" + "41" * 228, "too_large", None),
]


def v1_cases() -> list[tuple[str, str, bytes]]:
    full = PersonFoundReport(consciousness=Consciousness.UNCONSCIOUS, breathing=Breathing.YES,
                             equipment_needed=[Equipment.OXYGEN_MASK, Equipment.AED], request=Assistance.MEDICAL,
                             urgency=Urgency.CRITICAL, location=(50.9375, 6.9603), location_quality=3,
                             person_count=2, responders_needed_min=3)
    pitch = PersonFoundReport(consciousness=Consciousness.UNCONSCIOUS, equipment_needed=[Equipment.OXYGEN_MASK],
                              responders_needed_min=2)
    return [
        ("v1-pitch", "v1 report: unconscious, oxygen mask, 2 helpers (12 bytes).", encode_report(pitch, 1)),
        ("v1-full", "v1 report with GPS, time, counts.", encode_report(full, 91, 1764000000)),
        ("v1-text", "v1 text packet.", encode_text("Hallo Leitstelle", 5)),
        ("v1-accept", "v1 confirmation: accepted.", confirmation_packet(91, 1)),
        ("v1-done", "v1 confirmation: completed.", confirmation_packet(91, 2)),
    ]


def main() -> None:
    out: dict = {
        "spec_version": "2.0.0",
        "about": ("RescueCodec v2 conformance vectors. See spec/SPEC.md, section 'Conformance'. "
                  "valid: encode(input) must give hex exactly; decode(hex) must give decoded. "
                  "text: Level 2, render(decoded) must give these strings. "
                  "decode_only: decode(hex) must give decoded. "
                  "invalid_encode / invalid_decode: must be rejected; error codes should match. "
                  "v1: decode_any(hex) must give decoded."),
        "valid": [], "decode_only": [], "invalid_encode": [], "invalid_decode": [], "v1": [],
    }
    names = set()
    for name, note, msg in VALID:
        assert name not in names, name
        names.add(name)
        data = encode(msg)
        decoded = decode(data)
        assert encode(decoded) == data, name
        out["valid"].append({"name": name, "note": note, "input": msg, "hex": data.hex(), "size": len(data),
                             "decoded": decoded, "text": {"en": render(decoded, "en"), "de": render(decoded, "de")}})
    for name, note, hexstr, expected in DECODE_ONLY:
        decoded = decode(bytes.fromhex(hexstr))
        assert decoded == expected, (name, decoded)
        out["decode_only"].append({"name": name, "note": note, "hex": hexstr, "size": len(hexstr) // 2,
                                   "decoded": decoded})
    all_names = [c[0] for c in VALID + DECODE_ONLY + INVALID_ENCODE + INVALID_DECODE] + [c[0] for c in v1_cases()]
    assert len(all_names) == len(set(all_names)), "case names must be unique"
    for name, note, msg, code, field in INVALID_ENCODE:
        try:
            encode(msg)
        except CodecError as exc:
            assert exc.code == code and exc.field == field, (name, exc.code, exc.field)
        else:
            raise AssertionError(f"{name} was accepted")
        out["invalid_encode"].append({"name": name, "note": note, "input": msg, "error": code, "field": field})
    for name, note, hexstr, code, field in INVALID_DECODE:
        try:
            decode(bytes.fromhex(hexstr))
        except CodecError as exc:
            assert exc.code == code and exc.field == field, (name, exc.code, exc.field, str(exc))
        else:
            raise AssertionError(f"{name} was accepted")
        out["invalid_decode"].append({"name": name, "note": note, "hex": hexstr, "error": code, "field": field})
    for name, note, data in v1_cases():
        out["v1"].append({"name": name, "note": note, "hex": data.hex(), "size": len(data), "decoded": decode_any(data)})
    path = ROOT / "spec" / "vectors" / "codec.json"
    path.write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    total = sum(len(out[k]) for k in ("valid", "decode_only", "invalid_encode", "invalid_decode", "v1"))
    print(f"wrote {path.relative_to(ROOT)}: {total} cases "
          + ", ".join(f"{k}={len(out[k])}" for k in ("valid", "decode_only", "invalid_encode", "invalid_decode", "v1")))


if __name__ == "__main__":
    main()
