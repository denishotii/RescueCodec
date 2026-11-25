"""Code tables for RescueCodec v2: JSON names <-> wire numbers.

These mirror spec/rescuecodec/v2/rescuecodec.proto and spec/vocab/sar.yaml.
tests/test_v2_spec.py checks that all three agree.
"""
from __future__ import annotations

SPEC_VERSION = "2.3.0"

# Limits (spec/SPEC.md, section "Limits").
MAX_ENCODE_BYTES = 200   # encoders never produce more
MAX_DECODE_BYTES = 233   # Meshtastic DATA_PAYLOAD_LEN; decoders accept up to this
MAX_ID = 16383           # fits in a 2-byte varint
MAX_TRACK_POINTS = 16
MAX_TEXT_BYTES = 180
MAX_PACKED_TEXT_BYTES = 400  # typed text a chat that packs (DEFLATE or word tokens) accepts; the packet stays <= 200
MAX_TEAM_BYTES = 12
MAX_COUNT = 255
MAX_RADIUS_M = 100_000
MAX_ETA_MIN = 1440
MAX_SECTOR = 65535

BODY_TYPES = ("found", "track", "sector", "request", "hazard", "mayday", "ack", "text")
ID_REQUIRED = frozenset({"found", "request", "hazard", "mayday"})

PRIORITY = {"routine": 1, "normal": 2, "urgent": 3, "critical": 4}
DEFAULT_PRIORITY = {
    "mayday": "critical", "found": "urgent", "hazard": "urgent", "ack": "urgent",
    "request": "normal", "text": "normal", "sector": "routine", "track": "routine",
    "unknown": "normal",
}
# Suggested Meshtastic MeshPacket.Priority per RescueCodec priority.
MESHTASTIC_PRIORITY = {"critical": 110, "urgent": 100, "normal": 70, "routine": 10}

ACCURACY = {"gps": 0, "gps_poor": 1, "estimated": 2, "area": 3}

# Found.state packing: (name, shift, bit width)
CONSCIOUSNESS = {"conscious": 1, "confused": 2, "unconscious": 3}
BREATHING = {"normal": 1, "difficult": 2, "none": 3}
MOBILITY = {"walks": 1, "cannot_walk": 2, "trapped": 3}
AGE = {"child": 1, "teen": 2, "adult": 3, "senior": 4}
STATE_LAYOUT = (
    ("consciousness", 0, 2, CONSCIOUSNESS),
    ("breathing", 2, 2, BREATHING),
    ("mobility", 4, 2, MOBILITY),
    ("age", 6, 3, AGE),
)

# Bit flags: name -> bit number.
INJURIES = {"hypothermia": 0, "bleeding": 1, "fracture": 2, "head": 3, "spine": 4, "exhaustion": 5,
            "other": 6, "cardiac": 7, "heat": 8, "burn": 9, "allergy": 10}
NEEDS = {"stretcher": 0, "medic": 1, "helicopter": 2, "warmth": 3, "water": 4, "vehicle": 5, "other": 6,
         "aed": 7, "oxygen": 8, "first_aid": 9, "rope": 10, "light": 11, "food": 12, "radio": 13}

SECTOR_STATUS = {"cleared": 1, "partial": 2, "blocked": 3}
RESOURCE = {"stretcher": 1, "medic": 2, "helicopter": 3, "vehicle": 4, "searchers": 5, "dog_team": 6,
            "drone": 7, "light": 8, "water": 9, "food": 10, "warmth": 11, "batteries": 12,
            "rope_team": 13, "ambulance": 14, "fire_brigade": 15, "other": 16}
URGENCY = {"now": 1, "soon": 2, "later": 3}
HAZARD = {"blocked_path": 1, "rockfall": 2, "water": 3, "fire": 4, "cliff": 5, "ice": 6, "animal": 7,
          "power_line": 8, "hunting": 9, "weather": 10, "other": 11}
SEVERITY = {"low": 1, "medium": 2, "high": 3}
MAYDAY_REASON = {"injured": 1, "lost": 2, "missing_member": 3, "medical": 4, "trapped": 5, "threat": 6,
                 "other": 7}
ACK_STATUS = {"accepted": 1, "declined": 2, "done": 3}

# For the consistency test: JSON table -> (proto enum name, proto value prefix).
PROTO_ENUMS = {
    "Accuracy": (ACCURACY, "ACCURACY_"),
    "Priority": (PRIORITY, "PRIORITY_"),
    "Consciousness": (CONSCIOUSNESS, "CONSCIOUSNESS_"),
    "Breathing": (BREATHING, "BREATHING_"),
    "Mobility": (MOBILITY, "MOBILITY_"),
    "AgeGroup": (AGE, "AGE_"),
    "SectorStatus": (SECTOR_STATUS, "SECTOR_"),
    "Resource": (RESOURCE, "RESOURCE_"),
    "Urgency": (URGENCY, "URGENCY_"),
    "HazardType": (HAZARD, "HAZARD_"),
    "Severity": (SEVERITY, "SEVERITY_"),
    "MaydayReason": (MAYDAY_REASON, "MAYDAY_"),
    "AckStatus": (ACK_STATUS, "ACK_"),
}
PROTO_FLAGS = {"Injury": (INJURIES, "INJURY_"), "Need": (NEEDS, "NEED_")}
