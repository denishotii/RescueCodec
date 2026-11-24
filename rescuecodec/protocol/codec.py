"""Compact, strict RescueCodec packet codec (network byte order)."""
from __future__ import annotations

import struct
import time
from dataclasses import dataclass

from rescuecodec.schemas import Assistance, Breathing, Consciousness, Equipment, PersonFoundReport, Urgency

PROTOCOL_VERSION = 1
CODEC_SEMANTIC = 1
CODEC_TEXT = 0
CODEC_CONFIRMATION = 7
SCHEMA_PERSON_FOUND = 6
MAX_PACKET_BYTES = 200
HEADER = struct.Struct("!BBB")  # protocol version, codec id, schema id
BODY = struct.Struct("!IBBBB")  # request id, packed states, equipment mask, request, coords flag, timestamp
COORDS = struct.Struct("!ii")  # signed degrees x 1e5
TIMESTAMP = struct.Struct("!I")
PERSON_COUNT = struct.Struct("!B")
RESPONDERS_NEEDED = struct.Struct("!B")


class PacketError(ValueError):
    pass


def _pack_states(r: PersonFoundReport) -> int:
    # 2 bits each: consciousness [7:6], breathing [5:4], urgency [3:2], location quality [1:0]
    return (int(r.consciousness) << 6) | (int(r.breathing) << 4) | (int(r.urgency) << 2) | int(r.location_quality)


def encode_report(report: PersonFoundReport, request_id: int, timestamp: int | None = None) -> bytes:
    if not 0 <= request_id <= 0xFFFFFFFF:
        raise PacketError("request_id must fit uint32")
    if timestamp is not None and not 0 <= timestamp <= 0xFFFFFFFF:
        raise PacketError("timestamp must fit uint32")
    mask = 0
    for item in report.equipment_needed:
        mask |= 1 << int(item)
    flags = ((1 if report.location is not None else 0) | (2 if timestamp is not None else 0)
             | (4 if report.person_count is not None else 0) | (8 if report.responders_needed_min is not None else 0))
    payload = HEADER.pack(PROTOCOL_VERSION, CODEC_SEMANTIC, SCHEMA_PERSON_FOUND)
    payload += BODY.pack(request_id, _pack_states(report), mask, int(report.request), flags)
    if report.location is not None:
        lat, lon = report.location
        payload += COORDS.pack(round(lat * 100_000), round(lon * 100_000))
    if timestamp is not None:
        payload += TIMESTAMP.pack(timestamp)
    if report.person_count is not None:
        payload += PERSON_COUNT.pack(report.person_count)
    if report.responders_needed_min is not None:
        payload += RESPONDERS_NEEDED.pack(report.responders_needed_min)
    if len(payload) > MAX_PACKET_BYTES:
        raise PacketError("packet exceeds configured application budget")
    return payload


def encode_text(text: str, message_id: int = 0) -> bytes:
    body = text.encode("utf-8")
    if not 0 <= message_id <= 0xFFFFFFFF or not body or len(body) > MAX_PACKET_BYTES - HEADER.size - 4:
        raise PacketError("text fallback must fit in one application packet")
    return HEADER.pack(PROTOCOL_VERSION, CODEC_TEXT, 0) + struct.pack("!I", message_id) + body


def decode_report(payload: bytes) -> tuple[PersonFoundReport, int, int | None]:
    if not isinstance(payload, bytes) or len(payload) < HEADER.size + BODY.size or len(payload) > MAX_PACKET_BYTES:
        raise PacketError("truncated or oversized packet")
    version, codec_id, schema_id = HEADER.unpack_from(payload)
    if version != PROTOCOL_VERSION:
        raise PacketError(f"unsupported protocol version: {version}")
    if codec_id != CODEC_SEMANTIC or schema_id != SCHEMA_PERSON_FOUND:
        raise PacketError(f"unsupported codec/schema: {codec_id}/{schema_id}")
    offset = HEADER.size
    request_id, states, mask, request, flags = BODY.unpack_from(payload, offset)
    offset += BODY.size
    if flags & ~0x0F:
        raise PacketError("reserved flag bits must be zero")
    expected = (HEADER.size + BODY.size + (COORDS.size if flags & 1 else 0) + (TIMESTAMP.size if flags & 2 else 0)
                + (PERSON_COUNT.size if flags & 4 else 0) + (RESPONDERS_NEEDED.size if flags & 8 else 0))
    if len(payload) != expected:
        raise PacketError("payload length does not match option flags")
    conscious, breathing, urgency, quality = (states >> 6, (states >> 4) & 3, (states >> 2) & 3, states & 3)
    try:
        consciousness = Consciousness(conscious)
        breathing_value = Breathing(breathing)
        urgency_value = Urgency(urgency)
        request_value = Assistance(request)
        equipment = [item for item in Equipment if mask & (1 << int(item))]
    except ValueError as exc:
        raise PacketError("invalid categorical value") from exc
    if mask & ~0xFF:
        raise PacketError("reserved equipment bits must be zero")
    location = None
    if flags & 1:
        lat, lon = COORDS.unpack_from(payload, offset)
        offset += COORDS.size
        location = (lat / 100_000, lon / 100_000)
    timestamp = None
    if flags & 2:
        (timestamp,) = TIMESTAMP.unpack_from(payload, offset)
        offset += TIMESTAMP.size
    person_count = None
    if flags & 4:
        (person_count,) = PERSON_COUNT.unpack_from(payload, offset)
        if not 1 <= person_count <= 15:
            raise PacketError("person count must be 1..15")
        offset += PERSON_COUNT.size
    responders_needed_min = None
    if flags & 8:
        (responders_needed_min,) = RESPONDERS_NEEDED.unpack_from(payload, offset)
        if not 1 <= responders_needed_min <= 15:
            raise PacketError("minimum responders needed must be 1..15")
    report = PersonFoundReport(consciousness=consciousness, breathing=breathing_value,
        urgency=urgency_value, equipment_needed=equipment, request=request_value,
        location=location, location_quality=quality, person_count=person_count,
        responders_needed_min=responders_needed_min)
    return report, request_id, timestamp


def decode_text(payload: bytes) -> tuple[str, int]:
    if len(payload) < HEADER.size + 5 or len(payload) > MAX_PACKET_BYTES:
        raise PacketError("invalid text packet length")
    version, codec, schema = HEADER.unpack_from(payload)
    if version != PROTOCOL_VERSION or codec != CODEC_TEXT or schema != 0:
        raise PacketError("unsupported text packet")
    try:
        (message_id,) = struct.unpack_from("!I", payload, HEADER.size)
        return payload[HEADER.size + 4:].decode("utf-8", errors="strict"), message_id
    except UnicodeDecodeError as exc:
        raise PacketError("invalid UTF-8 text") from exc


def confirmation_packet(request_id: int, status: int) -> bytes:
    if status not in (1, 2):
        raise PacketError("confirmation status must be 1=accepted or 2=completed")
    return HEADER.pack(PROTOCOL_VERSION, CODEC_CONFIRMATION, 0) + struct.pack("!IB", request_id, status)


def decode_confirmation(payload: bytes) -> tuple[int, int]:
    if len(payload) != 8:
        raise PacketError("invalid confirmation length")
    version, codec, schema = HEADER.unpack_from(payload)
    if version != PROTOCOL_VERSION or codec != CODEC_CONFIRMATION or schema != 0:
        raise PacketError("unsupported confirmation packet")
    request_id, status = struct.unpack_from("!IB", payload, HEADER.size)
    if status not in (1, 2):
        raise PacketError("invalid confirmation status")
    return request_id, status


def reconstruct(report: PersonFoundReport, language: str = "en") -> str:
    words = {
        "en": {"found": "Person located", "conscious": "conscious", "unconscious": "unconscious", "yes": "yes", "no": "no", "unknown": "unknown", "priority": "Priority"},
        "de": {"found": "Person gefunden", "conscious": "ansprechbar", "unconscious": "bewusstlos", "yes": "ja", "no": "nein", "unknown": "unbekannt", "priority": "Priorität"},
    }
    w = words.get(language, words["en"])
    c = {Consciousness.UNKNOWN: w["unknown"], Consciousness.CONSCIOUS: w["conscious"], Consciousness.UNCONSCIOUS: w["unconscious"], Consciousness.NOT_APPLICABLE: w["unknown"]}[report.consciousness]
    b = {Breathing.UNKNOWN: w["unknown"], Breathing.YES: w["yes"], Breathing.NO: w["no"], Breathing.NOT_APPLICABLE: w["unknown"]}[report.breathing]
    equipment = ", ".join(e.name.replace("_", " ").lower() for e in report.equipment_needed) or ("keine Angabe" if language == "de" else "not specified")
    urgency = {Urgency.UNKNOWN: w["unknown"], Urgency.ROUTINE: "routine", Urgency.URGENT: "urgent", Urgency.CRITICAL: "critical"}[report.urgency]
    request_en = {Assistance.UNKNOWN: "not specified", Assistance.NONE: "no assistance requested", Assistance.MEDICAL: "medical assistance requested", Assistance.SEARCH: "search assistance requested", Assistance.EXTRACTION: "extraction assistance requested", Assistance.FIRE: "fire assistance requested", Assistance.OTHER: "other assistance requested"}[report.request]
    request_de = {Assistance.UNKNOWN: "nicht angegeben", Assistance.NONE: "keine Hilfe angefordert", Assistance.MEDICAL: "medizinische Hilfe angefordert", Assistance.SEARCH: "Suchhilfe angefordert", Assistance.EXTRACTION: "technische Rettung angefordert", Assistance.FIRE: "Brandhilfe angefordert", Assistance.OTHER: "andere Hilfe angefordert"}[report.request]
    location_en = f" Reported coordinates: {report.location[0]:.5f}, {report.location[1]:.5f}." if report.location else ""
    location_de = f" Gemeldete Koordinaten: {report.location[0]:.5f}, {report.location[1]:.5f}." if report.location else ""
    people_en = f" People found: {report.person_count}." if report.person_count else ""
    people_de = f" Gefundene Personen: {report.person_count}." if report.person_count else ""
    if language == "de":
        extra = f" Mindestens {report.responders_needed_min} weitere Einsatzkräfte benötigt." if report.responders_needed_min else ""
        return f"{w['found']}.{people_de} Bewusstsein: {c}. Atmung: {b}. Benötigte Ausrüstung: {equipment}. Auftrag: {request_de}. {w['priority']}: {urgency}.{location_de}{extra}"
    extra = f" At least {report.responders_needed_min} additional responders needed." if report.responders_needed_min else ""
    return f"{w['found']}.{people_en} Consciousness: {c}. Breathing: {b}. Equipment needed: {equipment}. Request: {request_en}. {w['priority']}: {urgency}.{location_en}{extra}"
