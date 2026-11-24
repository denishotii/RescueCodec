"""Forty hand-labeled fictional reports; no real patient data."""
from rescuecodec.schemas import Assistance, Breathing, Consciousness, Equipment, PersonFoundReport, Urgency

# text, consciousness, breathing, equipment, urgency, request, supported
ROWS = [
    ("I found a missing person, unconscious but breathing; urgent oxygen mask and medical assistance needed.",2,1,[0],2,2,1),
    ("We located someone conscious and breathing. Please send medical help urgently.",1,1,[],2,2,1),
    ("Person found, unresponsive and not breathing. Critical medical assistance required now.",2,2,[],3,2,1),
    ("A person was found, but we cannot determine whether they are breathing.",0,0,[],0,0,1),
    ("I found the person. They are awake, breathing, and do not need oxygen.",1,1,[],0,0,1),
    ("Missing person located unconscious but breathing. Bring an oxygen mask and first aid kit, urgently.",2,1,[0,1],2,0,1),
    ("Found one person, conscious. Need an AED and medical help.",1,0,[2],0,2,1),
    ("We found someone unconscious. Breathing status unknown; stretcher requested.",2,0,[3],0,0,1),
    ("The missing person is found and breathing. Send oxygen cylinder, critical.",0,1,[4],3,0,1),
    ("Person located, awake. No medical help needed.",1,0,[],0,1,1),
    ("Two people have been found. One is unconscious and breathing; medical help is urgent.",2,1,[],2,2,0),
    ("I found a person who is not unconscious and does not need oxygen.",1,0,[],0,0,1),
    ("We found a person, breathing status cannot be determined. Medical assistance requested.",0,0,[],0,2,1),
    ("Person found; unconscious, breathing, oxygen mask required, send medic immediately.",2,1,[0],3,2,1),
    ("A missing person has been located. They are conscious, but breathing is not known.",1,0,[],0,0,1),
    ("Found person is unconscious and not breathing. AED and medical assistance needed urgently.",2,2,[2],2,2,1),
    ("Person located, breathing normally, first aid kit requested.",0,1,[1],0,0,1),
    ("We found the missing person. Consciousness unknown; request a stretcher.",0,0,[3],0,0,1),
    ("The person is not unconscious, breathing, and medical help is requested.",1,1,[],0,2,0),
    ("One person found, unconscious, breathing status unknown, oxygen mask urgently required.",2,0,[0],2,0,1),
    ("Vermisste Person gefunden, bewusstlos aber sie atmet. Sauerstoffmaske und dringend medizinische Hilfe.",2,1,[0],2,2,1),
    ("Person gefunden, ansprechbar und atmet. Bitte dringend medizinische Hilfe schicken.",1,1,[],2,2,1),
    ("Person aufgefunden, bewusstlos und atmet nicht. Sofort medizinische Hilfe erforderlich.",2,2,[],3,2,1),
    ("Wir haben jemanden gefunden, können aber nicht feststellen, ob die Person atmet.",0,0,[],0,0,1),
    ("Vermisste Person gefunden, wach und atmend. Keine Sauerstoffmaske benötigt.",1,1,[],0,0,1),
    ("Bewusstlose Person gefunden, Atmung unbekannt. Trage wird benötigt.",2,0,[3],0,0,1),
    ("Person gefunden. Bewusstlos, atmet; Sauerstoffmaske und Erste-Hilfe-Koffer dringend bringen.",2,1,[0,1],2,0,1),
    ("Eine Person gefunden, ansprechbar und atmend. AED und medizinische Hilfe werden dringend gebraucht.",1,1,[2],2,2,1),
    ("Person gefunden, Bewusstsein unbekannt, atmet nicht. Medizinische Hilfe erforderlich.",0,2,[],0,2,1),
    ("Zwei Personen gefunden. Eine ist bewusstlos und atmet. Medizinische Hilfe dringend.",2,1,[],2,2,0),
    ("Gefundene Person ist nicht bewusstlos und braucht keinen Sauerstoff.",1,0,[],0,0,1),
    ("Person gefunden, wach; Atmung unbekannt, medizinische Hilfe angefordert.",1,0,[],0,2,1),
    ("There is smoke spreading on the east stairwell; avoid that route.",0,0,[],0,0,0),
    ("Gas concentration is rising near the loading bay; request evacuation.",0,0,[],0,0,0),
    ("Team Delta is safe, two responders accounted for.",0,0,[],0,0,0),
    ("Need one technical rescue team and cutting equipment at the north entrance.",0,0,[],0,0,0),
    ("Three people were found: one conscious, one unconscious, one status unknown.",0,0,[],0,0,0),
    ("The caller is unsure whether the person is breathing or even at this location.",0,0,[],0,0,0),
    ("Please relay the coordinates 50.9375, 6.9603 to command.",0,0,[],0,0,0),
    ("Person found; needs help.",0,0,[],0,0,0),
]

def dataset():
    result = []
    for i, (text, c, b, equipment, urgency, request, supported) in enumerate(ROWS, 1):
        if supported:
            gt = PersonFoundReport(consciousness=Consciousness(c), breathing=Breathing(b), equipment_needed=[Equipment(e) for e in equipment], urgency=Urgency(urgency), request=Assistance(request))
            annotation = gt.model_dump(mode="json")
        else:
            annotation = {"unsupported": True}
        result.append({"id": f"SYN-{i:02d}", "text": text, "ground_truth": annotation, "supported": bool(supported)})
    resource_examples = [
        ("I found the person at 50.9375, 6.9603. He is unconscious and breathing, needs an oxygen mask, and we need at least two people.",
         PersonFoundReport(consciousness=Consciousness.UNCONSCIOUS, breathing=Breathing.YES,
             equipment_needed=[Equipment.OXYGEN_MASK], location=(50.9375, 6.9603), location_quality=2,
             responders_needed_min=2)),
        ("Person gefunden, bewusstlos und atmet. Sauerstoffmaske benötigt; bitte mindestens zwei weitere Einsatzkräfte schicken.",
         PersonFoundReport(consciousness=Consciousness.UNCONSCIOUS, breathing=Breathing.YES,
             equipment_needed=[Equipment.OXYGEN_MASK], responders_needed_min=2)),
    ]
    for i, (text, report) in enumerate(resource_examples, 41):
        result.append({"id": f"SYN-{i:02d}", "text": text, "ground_truth": report.model_dump(mode="json"), "supported": True})
    return result
