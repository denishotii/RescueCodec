"""Read v1 packets (first byte 0x01) as v2 canonical JSON, so old senders still show up.

v1 is only decoded, never produced. The result carries "v": 1 and may hold
values a v2 encoder would refuse (for example a 32-bit id), so do not re-encode it.
"""
from __future__ import annotations

import time as _time

from rescuecodec.protocol import codec as v1
from rescuecodec.schemas import Assistance, Breathing, Consciousness, Equipment, Urgency
from rescuecodec.v2 import codes as C
from rescuecodec.v2.codec import CodecError, decode, detect_version

_CONSCIOUSNESS = {Consciousness.CONSCIOUS: "conscious", Consciousness.UNCONSCIOUS: "unconscious"}
_BREATHING = {Breathing.YES: "normal", Breathing.NO: "none"}
_PRIORITY = {Urgency.ROUTINE: "routine", Urgency.URGENT: "urgent", Urgency.CRITICAL: "critical"}
_EQUIPMENT = {Equipment.OXYGEN_MASK: "oxygen", Equipment.FIRST_AID_KIT: "first_aid", Equipment.AED: "aed",
              Equipment.STRETCHER: "stretcher", Equipment.OXYGEN: "oxygen", Equipment.WATER: "water",
              Equipment.RADIO: "radio", Equipment.OTHER: "other"}
_ASSISTANCE = {Assistance.MEDICAL: "medic", Assistance.EXTRACTION: "rope", Assistance.SEARCH: "other",
               Assistance.FIRE: "other", Assistance.OTHER: "other"}
_ACCURACY = {3: "gps", 2: "estimated", 1: "area", 0: "estimated"}


def decode_v1(data: bytes) -> dict:
    """v1 wire bytes -> canonical JSON with "v": 1. Raises CodecError."""
    if len(data) < 3 or data[0] != 0x01:
        raise CodecError("malformed", "not a v1 packet")
    codec_id = data[1]
    try:
        if codec_id == v1.CODEC_TEXT:
            text, message_id = v1.decode_text(data)
            out: dict = {"v": 1}
            if message_id:
                out["id"] = message_id
            out["text"] = {"text": text}
            return out
        if codec_id == v1.CODEC_CONFIRMATION:
            request_id, status = v1.decode_confirmation(data)
            return {"v": 1, "ack": {"ref": request_id, "status": "accepted" if status == 1 else "done"}}
        report, request_id, timestamp = v1.decode_report(data)
    except v1.PacketError as exc:
        raise CodecError("malformed", f"bad v1 packet: {exc}") from exc
    out = {"v": 1, "id": request_id}
    if report.urgency in _PRIORITY:
        out["priority"] = _PRIORITY[report.urgency]
    if report.location is not None:
        lat, lon = report.location
        pos = {"lat": lat, "lon": lon}
        acc = _ACCURACY[report.location_quality]
        if acc != "gps":
            pos["acc"] = acc
        out["pos"] = pos
    if timestamp is not None:
        t = _time.gmtime(timestamp)
        out["time"] = f"{t.tm_hour:02d}:{t.tm_min:02d}"
        out["v1_timestamp"] = timestamp
    found: dict = {}
    if report.person_count:
        found["count"] = report.person_count
    if report.consciousness in _CONSCIOUSNESS:
        found["consciousness"] = _CONSCIOUSNESS[report.consciousness]
    if report.breathing in _BREATHING:
        found["breathing"] = _BREATHING[report.breathing]
    needs = {_EQUIPMENT[e] for e in report.equipment_needed}
    if report.request in _ASSISTANCE:
        needs.add(_ASSISTANCE[report.request])
    if needs:
        found["needs"] = sorted(needs, key=lambda n: C.NEEDS[n])
    if report.responders_needed_min:
        found["responders"] = report.responders_needed_min
    out["found"] = found
    return out


def decode_any(data: bytes) -> dict:
    """Decode a RescueCodec payload of any version.

    First byte 0x01 -> v1 (result has "v": 1). Bytes 0x00 and 0x02..0x07 -> reserved,
    rejected with "unsupported_version". Anything else -> v2 (result has "v": 2).
    """
    if not data:
        raise CodecError("empty", "empty payload")
    version = detect_version(data)
    if version == 1:
        return decode_v1(bytes(data))
    msg = decode(data)
    return {"v": 2, **msg}
