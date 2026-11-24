"""Conservative extraction: local Ollama first when configured, explicit heuristic fallback."""
from __future__ import annotations

import json
import os
import re
import time
import urllib.request

from rescuecodec.schemas import Assistance, Breathing, Consciousness, Equipment, PersonFoundReport, Urgency

OLLAMA_URL = os.getenv("RESCUECODEC_OLLAMA_URL", "http://127.0.0.1:11434/api/chat")
OLLAMA_MODEL = os.getenv("RESCUECODEC_OLLAMA_MODEL", "")
OLLAMA_TIMEOUT_SECONDS = float(os.getenv("RESCUECODEC_OLLAMA_TIMEOUT_SECONDS", "120"))

OLLAMA_OUTPUT_SCHEMA = {
    "type": "object", "additionalProperties": False,
    "properties": {
        "schema_id": {"type": "integer", "const": 6},
        "event": {"type": "string", "const": "PERSON_FOUND"},
        "consciousness": {"type": "string", "enum": ["UNKNOWN", "CONSCIOUS", "UNCONSCIOUS", "NOT_APPLICABLE"]},
        "breathing": {"type": "string", "enum": ["UNKNOWN", "YES", "NO", "NOT_APPLICABLE"]},
        "equipment_needed": {"type": "array", "items": {"type": "string", "enum": ["OXYGEN_MASK", "FIRST_AID_KIT", "AED", "STRETCHER", "OXYGEN", "WATER", "RADIO", "OTHER"]}, "maxItems": 8},
        "request": {"type": "string", "enum": ["UNKNOWN", "NONE", "MEDICAL", "SEARCH", "EXTRACTION", "FIRE", "OTHER"]},
        "urgency": {"type": "string", "enum": ["UNKNOWN", "ROUTINE", "URGENT", "CRITICAL"]},
        "location": {"anyOf": [{"type": "array", "items": {"type": "number"}, "minItems": 2, "maxItems": 2}, {"type": "null"}]},
        "location_quality": {"type": "integer", "enum": [0, 1, 2, 3]},
        "person_count": {"anyOf": [{"type": "integer", "minimum": 1, "maximum": 15}, {"type": "null"}]},
        "responders_needed_min": {"anyOf": [{"type": "integer", "minimum": 1, "maximum": 15}, {"type": "null"}]},
        "unsupported_reason": {"anyOf": [{"type": "string"}, {"type": "null"}]},
    },
    "required": ["schema_id", "event", "consciousness", "breathing", "equipment_needed", "request", "urgency", "location", "location_quality", "person_count", "responders_needed_min", "unsupported_reason"],
}


def _extract_local(text: str) -> tuple[PersonFoundReport, bool]:
    t = text.casefold()
    responders_needed = _responders_needed(t)
    if responders_needed is None and re.search(r"\b(two|three|four|multiple|several|zwei|drei|mehrere)\s+(?:people|persons|personen)\b", t):
        return PersonFoundReport(source_text=text, unsupported_reason="Current schema cannot safely represent multiple people"), False
    found_words = ("found", "located", "gefunden", "aufgefunden", "locate")
    if not any(w in t for w in found_words) or not any(w in t for w in ("person", "missing", "patient", "someone", "vermisst", "bewusstlos", "person")):
        return PersonFoundReport(source_text=text, unsupported_reason="No supported person-found event detected"), False
    if any(x in t for x in ("needs help", "need help", "needs assistance", "need assistance", "braucht hilfe", "brauchen hilfe")) and not any(
        x in t for x in ("medical", "medic", "medizin", "ärzt", "oxygen", "sauerstoff", "unconscious", "bewusstlos", "breath", "atmung", "atmet", "first aid", "aed", "stretcher", "trage", "search", "suchen", "rescue", "rettung")
    ):
        return PersonFoundReport(source_text=text, unsupported_reason="Generic help request has no represented assistance category"), False
    if re.search(r"not\s+unconscious|nicht\s+bewusstlos", t):
        consciousness = Consciousness.CONSCIOUS
    elif any(x in t for x in ("consciousness unknown", "consciousness is unknown", "bewusstsein unbekannt", "consciousness not known")):
        consciousness = Consciousness.UNKNOWN
    elif any(x in t for x in ("unconscious", "unresponsive", "bewusstlos", "nicht ansprechbar")):
        consciousness = Consciousness.UNCONSCIOUS
    elif any(x in t for x in ("conscious", "awake", "ansprechbar", "wach")):
        consciousness = Consciousness.CONSCIOUS
    else:
        consciousness = Consciousness.UNKNOWN
    if re.search(r"\b(not breathing|isn't breathing|no breathing)\b|\b(atmet nicht|keine atmung)\b", t):
        breathing = Breathing.NO
    elif any(x in t for x in ("breathing", "breathe", "atmet", "atmung")):
        breathing = Breathing.YES
        if any(x in t for x in ("cannot determine", "cannot be determined", "unknown whether", "don't know if", "cannot tell", "status unknown", "unklar ob", "nicht feststellen", "unbekannt")):
            breathing = Breathing.UNKNOWN
    else:
        breathing = Breathing.UNKNOWN
    equipment = []
    if any(x in t for x in ("oxygen mask", "oxygen-mask", "sauerstoffmaske")):
        if not any(x in t for x in ("don't need", "does not need", "not needed", "nicht benötigt", "braucht keine", "keine sauerstoffmaske")):
            equipment.append(Equipment.OXYGEN_MASK)
    for phrases, item in [(("first aid kit", "first-aid kit", "erste-hilfe-koffer"), Equipment.FIRST_AID_KIT), (("aed", "defibrillator"), Equipment.AED), (("stretcher", "trage"), Equipment.STRETCHER), (("oxygen cylinder", "oxygen supply", "sauerstoff"), Equipment.OXYGEN)]:
        if item == Equipment.OXYGEN and any(x in t for x in ("oxygen mask", "oxygen-mask", "sauerstoffmaske")): continue
        if item == Equipment.OXYGEN and any(x in t for x in ("don't need", "does not need", "not needed", "keine sauerstoffmaske", "keinen sauerstoff")): continue
        if any(x in t for x in phrases):
            equipment.append(item)
    if any(x in t for x in ("no medical help needed", "does not need medical", "no medical assistance", "keine medizinische hilfe", "keine ärztliche hilfe")):
        request = Assistance.NONE
    else:
        request = Assistance.MEDICAL if any(x in t for x in ("medical assistance", "medical help", "medic", "ärztliche hilfe", "medizinische hilfe")) else Assistance.UNKNOWN
    urgency = Urgency.CRITICAL if any(x in t for x in ("critical", "immediately", "sofort", "lebensbedrohlich")) else Urgency.URGENT if any(x in t for x in ("urgent", "urgently", "dringend", "akut")) else Urgency.UNKNOWN
    count_match = re.search(r"\b(\d{1,2})\s+(?:people|persons|persons|personen|people)\b", t)
    count = int(count_match.group(1)) if count_match else None
    location = None
    location_quality = 0
    coords = re.search(r"(?<!\d)(-?\d{1,2}\.\d{3,})\s*[,;/ ]\s*(-?\d{1,3}\.\d{3,})(?!\d)", text)
    if coords:
        candidate = (float(coords.group(1)), float(coords.group(2)))
        if -90 <= candidate[0] <= 90 and -180 <= candidate[1] <= 180:
            location, location_quality = candidate, 2
    report = PersonFoundReport(consciousness=consciousness, breathing=breathing, equipment_needed=equipment,
        request=request, urgency=urgency, person_count=count, responders_needed_min=responders_needed, source_text=text)
    if location is not None:
        report.location = location
        report.location_quality = location_quality
    return report, True


def _responders_needed(text: str) -> int | None:
    pattern = r"(?:need|require|request|send|bring|at least|minimum|brauchen|benötigen|benötigt|mindestens).{0,35}\b(1[0-5]|[1-9]|one|two|three|four|five|six|seven|eight|nine|ten|eins|ein|zwei|drei|vier|fünf|sechs|sieben|acht|neun|zehn)\s+(?:(?:additional|more|weitere)\s+)?(?:people|persons|responders?|team members|personen|einsatzkräfte)\b"
    match = re.search(pattern, text)
    if not match:
        return None
    words = {"one": 1, "eins": 1, "ein": 1, "two": 2, "zwei": 2, "three": 3, "drei": 3, "four": 4, "vier": 4, "five": 5, "fünf": 5, "six": 6, "sechs": 6, "seven": 7, "sieben": 7, "eight": 8, "acht": 8, "nine": 9, "neun": 9, "ten": 10, "zehn": 10}
    token = match.group(1)
    return words.get(token, int(token) if token.isdigit() else None)


def _ground_report(report: PersonFoundReport, text: str) -> PersonFoundReport:
    """Prevent model output for high-risk fields unless the report contains evidence."""
    t = text.casefold()
    unknown_cues = ("unknown", "not known", "cannot determine", "cannot tell", "don't know", "unklar", "unbekannt", "nicht feststellen")
    consciousness_cues = ("unconscious", "unresponsive", "conscious", "awake", "bewusstlos", "ansprechbar", "wach", "nicht ansprechbar")
    consciousness_unknown = re.search(r"(?:consciousness|bewusstsein).{0,35}(?:unknown|not known|unklar|unbekannt)|(?:unknown|not known|unklar|unbekannt).{0,35}(?:consciousness|bewusstsein)", t)
    if consciousness_unknown or not any(x in t for x in consciousness_cues):
        report.consciousness = Consciousness.UNKNOWN
    elif any(x in t for x in ("not unconscious", "nicht bewusstlos")):
        report.consciousness = Consciousness.CONSCIOUS
    elif any(x in t for x in ("unconscious", "unresponsive", "bewusstlos", "nicht ansprechbar")):
        report.consciousness = Consciousness.UNCONSCIOUS

    breathing_cues = ("breathing", "breathe", "atmet", "atmung", "atmend")
    breathing_unknown = re.search(r"(?:breath\w*|atmung|atmet).{0,40}(?:unknown|not known|cannot determine|cannot be determined|cannot tell|don't know|unklar|unbekannt|nicht feststellen)|(?:unknown|not known|cannot determine|cannot be determined|cannot tell|don't know|unklar|unbekannt|nicht feststellen).{0,40}(?:breath\w*|atmung|atmet)", t)
    if not any(x in t for x in breathing_cues):
        report.breathing = Breathing.UNKNOWN
    elif any(x in t for x in ("not breathing", "isn't breathing", "no breathing", "atmet nicht", "keine atmung")):
        report.breathing = Breathing.NO
    elif breathing_unknown:
        report.breathing = Breathing.UNKNOWN
    else:
        report.breathing = Breathing.YES

    requested = any(x in t for x in ("need", "needs", "needed", "required", "request", "send", "bring", "deliver", "urgently", "urgent", "dringend", "benötigt", "brauchen", "bringen", "angefordert", "gebraucht"))
    negated_equipment = any(x in t for x in ("don't need", "does not need", "doesn't need", "not needed", "keine ", "braucht keine", "nicht benötigt"))
    equipment_phrases = {
        Equipment.OXYGEN_MASK: ("oxygen mask", "oxygen-mask", "sauerstoffmaske"),
        Equipment.FIRST_AID_KIT: ("first aid kit", "first-aid kit", "erste-hilfe-koffer"),
        Equipment.AED: ("aed", "defibrillator"), Equipment.STRETCHER: ("stretcher", "trage"),
        Equipment.OXYGEN: ("oxygen cylinder", "oxygen supply", "sauerstoff"),
        Equipment.WATER: ("water", "wasser"), Equipment.RADIO: ("radio", "funkgerät"),
    }
    allowed = []
    if requested and not negated_equipment:
        for item, phrases in equipment_phrases.items():
            if item == Equipment.OXYGEN and any(x in t for x in equipment_phrases[Equipment.OXYGEN_MASK]):
                continue
            if any(x in t for x in phrases):
                allowed.append(item)
    report.equipment_needed = allowed

    medical = any(x in t for x in ("medical assistance", "medical help", "medic", "ärztliche hilfe", "medizinische hilfe"))
    explicit_no_medical = any(x in t for x in ("no medical help needed", "does not need medical", "no medical assistance", "keine medizinische hilfe", "keine ärztliche hilfe"))
    if explicit_no_medical:
        report.request = Assistance.NONE
    elif report.request == Assistance.NONE:
        report.request = Assistance.UNKNOWN
    elif report.request == Assistance.MEDICAL and not medical:
        report.request = Assistance.UNKNOWN
    elif report.request == Assistance.SEARCH and not any(x in t for x in ("search", "missing", "vermisst", "suchen", "suche")):
        report.request = Assistance.UNKNOWN
    elif report.request == Assistance.EXTRACTION and not any(x in t for x in ("extraction", "extract", "rescue", "bergung", "retten")):
        report.request = Assistance.UNKNOWN
    elif report.request == Assistance.FIRE and not any(x in t for x in ("fire", "smoke", "brand", "feuer", "rauch")):
        report.request = Assistance.UNKNOWN
    if any(x in t for x in ("critical", "immediately", "sofort", "lebensbedrohlich")):
        report.urgency = Urgency.CRITICAL
    elif any(x in t for x in ("urgent", "urgently", "dringend", "akut")):
        report.urgency = Urgency.URGENT
    elif "routine" in t or "routinemäßig" in t:
        report.urgency = Urgency.ROUTINE
    else:
        report.urgency = Urgency.UNKNOWN

    coords = re.search(r"(?<!\d)(-?\d{1,2}\.\d{3,})\s*[,;/ ]\s*(-?\d{1,3}\.\d{3,})(?!\d)", text)
    if not coords:
        report.location = None
        report.location_quality = 0
    elif report.location is not None:
        report.location_quality = max(2, report.location_quality)
    # Keep responder demand separate from found-person count; only lexical resource requests qualify.
    report.responders_needed_min = _responders_needed(t)
    return report


def extract_report(text: str, *, use_ollama: bool = True) -> dict:
    """Return validated report plus extraction provenance and elapsed milliseconds."""
    started = time.perf_counter()
    if use_ollama and OLLAMA_MODEL:
        fallback, preflight_supported = _extract_local(text)
        if not preflight_supported:
            return {"report": fallback, "supported": False, "method": "unsupported_preflight", "latency_ms": (time.perf_counter()-started)*1000}
    if use_ollama and OLLAMA_MODEL:
        system = ("You extract emergency facts conservatively. Use only facts explicitly stated. Do not guess or add standard equipment. "
                  "equipment_needed must be [] unless an item is explicitly requested; mention of medical help does not imply equipment. "
                  "Preserve negation and uncertainty. UNKNOWN is not the same as NO. Leave location null unless coordinates are written. "
                  "Do not merge different people's conditions. Set unsupported_reason when the report is not a person-found report or cannot be represented safely by one schema-6 record. "
                  "Use UNKNOWN for unreported categorical fields and null for unknown location/count/team-size. person_count means people found; responders_needed_min means the minimum number of additional responders requested. "
                  "Do not confuse two casualties with a request for two responders. Return only the requested JSON object.")
        prompt = "Extract this report into the constrained schema. Interpret explicit 'not unconscious' as CONSCIOUS; explicit 'does not need' as no requested equipment. If the report says two or more found people with different states, mark unsupported. Count requests such as 'we need at least two people' as responders_needed_min, not person_count. Report: " + text
        body = json.dumps({"model": OLLAMA_MODEL, "messages": [{"role": "system", "content": system}, {"role": "user", "content": prompt}], "format": OLLAMA_OUTPUT_SCHEMA, "stream": False, "options": {"temperature": 0, "num_predict": 180}}).encode()
        try:
            req = urllib.request.Request(OLLAMA_URL, data=body, headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=OLLAMA_TIMEOUT_SECONDS) as response:
                result = json.loads(response.read())
            raw = json.loads(result["message"]["content"])
            for field, enum_type in (("consciousness", Consciousness), ("breathing", Breathing), ("urgency", Urgency), ("request", Assistance)):
                value = raw.get(field)
                if isinstance(value, str): raw[field] = enum_type[value.upper().replace(" ", "_")].value
            enum_maps = {item.name: item.value for item in Equipment}
            raw["equipment_needed"] = [enum_maps[item.upper().replace(" ", "_")] if isinstance(item, str) else item for item in raw.get("equipment_needed", [])]
            if raw.get("location") in ([None, None], []):
                raw["location"] = None
            if raw.get("location") is None:
                raw["location_quality"] = 0
            raw["source_text"] = text
            report = PersonFoundReport.model_validate(raw)
            report = _ground_report(report, text)
            # The local schema-capability gate is deterministic; model uncertainty is represented in fields, not a second reject decision.
            report.unsupported_reason = None
            return {"report": report, "supported": True, "method": "ollama", "latency_ms": (time.perf_counter()-started)*1000}
        except Exception as exc:
            fallback, supported = _extract_local(text)
            return {"report": fallback, "supported": supported, "method": "conservative_fallback", "model_error": str(exc), "latency_ms": (time.perf_counter()-started)*1000}
    report, supported = _extract_local(text)
    return {"report": report, "supported": supported, "method": "conservative_fallback", "latency_ms": (time.perf_counter()-started)*1000}
