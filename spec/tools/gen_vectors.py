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
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from rescuecodec.protocol.codec import confirmation_packet, encode_report, encode_text  # noqa: E402
from rescuecodec.schemas import (Assistance, Breathing, Consciousness, Equipment,  # noqa: E402
                                 PersonFoundReport, Urgency)
from rescuecodec.v2 import CodecError, decode, decode_any, decode_compact, encode, encode_compact, render  # noqa: E402
from rescuecodec.v2 import textpack, textwords  # noqa: E402
from rescuecodec.v2.textwords import decode_words, encode_words  # noqa: E402

POS = {"lat": 50.69212, "lon": 7.21345}  # Siebengebirge, near the Drachenfels
NODE = "!a1b2c3d4"

WALK = [(50.69212, 7.21345), (50.69250, 7.21390), (50.69291, 7.21422), (50.69330, 7.21470),
        (50.69362, 7.21530), (50.69401, 7.21571), (50.69447, 7.21600), (50.69490, 7.21641),
        (50.69521, 7.21702), (50.69560, 7.21744), (50.69600, 7.21790), (50.69633, 7.21840),
        (50.69670, 7.21881), (50.69711, 7.21920), (50.69750, 7.21966), (50.69789, 7.22010)]


def _raw_deflate(data: bytes, zdict: bytes | None = None) -> bytes:
    comp = zlib.compressobj(9, zlib.DEFLATED, -15, 8, zlib.Z_DEFAULT_STRATEGY, **({"zdict": zdict} if zdict else {}))
    return comp.compress(data) + comp.flush()


def _ld(field: int, data: bytes) -> str:
    """One length-delimited protobuf field (tag + varint length + bytes), as hex."""
    n, out = len(data), bytearray([(field << 3) | 2])
    while True:
        out.append((n & 0x7F) | (0x80 if n > 0x7F else 0))
        n >>= 7
        if not n:
            break
    return (bytes(out) + data).hex()


def packed_wire(deflate: bytes, dict_no: int | None = 1, text: str | None = None, id_: int = 7) -> str:
    """Envelope{id, text{text?, deflate, dict?}} as hex."""
    body = (_ld(1, text.encode()) if text else "") + _ld(2, deflate) + (f"18{dict_no:02x}" if dict_no else "")
    return f"08{id_:02x}" + _ld(13, bytes.fromhex(body))


HUT_EN = "We are at the hut, two people with us, waiting for the vehicle on the forest road"
HUT_DE = "Wir sind an der Hütte, zwei Personen bei uns, warten auf das Fahrzeug am Forstweg"
#: Packed texts. Compressors may make other bytes for the same text, so these are not in `valid`. Every library
#: must unpack `deflate` to `text`, decode `hex` to `decoded`, and decode(encode(decoded)) must give `decoded`.
PACKED_TEXT = [
    ("packed-en-hut", "The example from SPEC section 3.5, English.", {"id": 3, "text": {"text": HUT_EN, "dict": 1}}),
    ("packed-de-hut", "The same in German, with umlauts.", {"id": 4, "text": {"text": HUT_DE, "dict": 1}}),
    ("packed-de-short", "A short German chat line.", {"id": 5, "text": {"text": "Bin unterwegs, Ankunft in ca. 10 Minuten", "dict": 1}}),
    ("packed-en-pos", "A packed text keeps position and priority.",
     {"id": 6, "priority": "urgent", "pos": POS, "text": {"text": "Need a stretcher and two more people at the edge of the forest", "dict": 1}}),
    ("packed-long", "A 300-byte text (too long for a plain text) fits packed.",
     {"id": 8, "text": {"text": ("Sector 4 fully searched, nothing found. " * 8)[:300], "dict": 1}}),
]


def words_wire(words: bytes, dict_no: int | None = 1, id_: int = 7, extra: str = "") -> str:
    """Envelope{id, text{words, words_dict?}} as hex, built by hand (also for broken streams)."""
    body = extra + _ld(4, words) + (f"28{dict_no:02x}" if dict_no else "")
    return f"08{id_:02x}" + _ld(13, bytes.fromhex(body))


def _lit(data: bytes) -> bytes:
    return (textwords.LITERAL + len(data)).to_bytes(2, "big") + data


def _tok(entry: str) -> bytes:
    return textwords.dictionary(1).index[entry].to_bytes(2, "big")


#: Compact texts (word tokens, SPEC section 3.6). The encoding is exact, so every library that encodes must give
#: `words` and `hex` byte for byte; every library must decode `hex` to `decoded` and `words` to `text`.
NEAR_LIMIT = ("Update from team 3 at 14:20. We searched the whole north side of the valley along the river and the "
              "old mill. No trace of the missing hiker so far. The path near the bridge is flooded, please use the "
              "forest road instead. Two of us are tired but fine. We need more batteries for the radios, water and a "
              "dry blanket. Next report in one hour. Wind from the west, light rain, ridge in fog.")
COMPACT_TEXT = [
    ("words-conversation", "Ordinary conversation.",
     {"id": 21, "text": {"text": "Hi, are you still at the station? I will be there in ten minutes.", "words": 1}}),
    ("words-rescue-update", "Rescue update with a place name and coordinates typed as text.",
     {"id": 22, "text": {"text": "Team 3 reached Löwenburg hut. Missing hiker found at 50.6921, 7.2134, conscious, "
                                 "needs a stretcher.", "words": 1}}),
    ("words-name", "A name is a literal that owns its leading space: [We] [found] [literal \" Aylin Demir\"].",
     {"id": 23, "text": {"text": "We found Aylin Demir", "words": 1}}),
    ("words-name-first", "Token after a literal: no implied space, the space travels in the literal \"Aylin Demir \".",
     {"id": 24, "text": {"text": "Aylin Demir is safe", "words": 1}}),
    ("words-coordinates-prose", "Coordinates typed as prose keep the minus sign, comma and trailing zeroes.",
     {"id": 25, "text": {"text": "Meet at 50.6900, -1.2500", "words": 1}}),
    ("words-gps-field", "GPS from the report flow stays in Envelope.pos; the words carry no coordinates.",
     {"id": 26, "priority": "urgent", "pos": POS, "text": {"text": "Found him, conscious, needs a stretcher", "words": 1}}),
    ("words-poem", "A poem with intentional line breaks and repeated spaces.",
     {"id": 27, "text": {"text": "The river runs\n  under the bridge,\n\nand we wait   for morning.\n", "words": 1}}),
    ("words-unfamiliar", "Unfamiliar words go as one literal, never a near match.",
     {"id": 28, "text": {"text": "Zyxomorph flibberjab quantimeter", "words": 1}}),
    ("words-mixed-de-en", "Mixed German and English.",
     {"id": 29, "text": {"text": "Wir sind am Parkplatz, the road is blocked, bitte langsam fahren.", "words": 1}}),
    ("words-punctuation", "Punctuation, brackets and quotes.",
     {"id": 30, "text": {"text": "Wait... what?! (Really) [yes] {ok}; done: \"fine\" - right?", "words": 1}}),
    ("words-emoji", "Emoji and other non-Latin characters.",
     {"id": 31, "text": {"text": "All safe 🙂👍 see you soon ❤️ Дякую", "words": 1}}),
    ("words-call-sign", "Apartment number, call sign and capitals.",
     {"id": 32, "text": {"text": "Apt 4B, call sign DL1ABC. NEED WATER NOW", "words": 1}}),
    ("words-whitespace", "Leading and trailing spaces, tab and CR LF.",
     {"id": 33, "text": {"text": "  line one\r\nline two\tend  ", "words": 1}}),
    ("words-near-limit", "385 bytes of text in a 198-byte packet, near the 200-byte encoder limit (too long as a plain text).",
     {"id": 300, "text": {"text": NEAR_LIMIT, "words": 1}}),
]
#: Token streams only (no packet): literal lengths around 127, 128, 255, 256 bytes. There is no length switch: a
#: literal is 0xF800 + N for N = 1..2047, so none of these lengths changes the header.
WORD_STREAMS = [
    ("stream-literal-127", "A literal of 127 bytes.", "x" * 127),
    ("stream-literal-128", "A literal of 128 bytes.", "x" * 128),
    ("stream-literal-255", "A literal of 255 bytes.", "x" * 255),
    ("stream-literal-256", "A literal of 256 bytes.", "x" * 256),
    ("stream-literal-utf8", "A literal counts bytes, not characters: 4 x 4-byte emoji = 16 bytes.", "🙂🙂🙂🙂"),
    ("stream-only-spaces", "Only whitespace: one literal.", "   "),
    ("stream-glue", "Two dictionary words without a space between them need GLUE.", "safe(water)"),
    ("stream-caps", "Capitalised and all-caps words use CAP and UPPER.", "Water WATER water"),
]
WORD_STREAMS_INVALID = [
    ("stream-empty", "An empty stream.", b"", "malformed"),
    ("stream-half-token", "One byte of a token.", b"\x00", "malformed"),
    ("stream-literal-cut", "A literal header promising 5 bytes, 4 present.", bytes.fromhex("f805") + b"Ayli", "malformed"),
    ("stream-literal-zero", "A literal of 0 bytes.", bytes.fromhex("f800"), "malformed"),
    ("stream-unknown-control", "Control 0xF004 is reserved.", bytes.fromhex("f004") + bytes.fromhex("0010"), "malformed"),
    ("stream-token-past-end", "Token 0xEFFF is past the end of a 8192-entry dictionary.", bytes.fromhex("efff"), "malformed"),
    ("stream-control-at-end", "CAP with no token after it.", bytes.fromhex("0010f001"), "malformed"),
    ("stream-control-before-literal", "CAP before a literal.", bytes.fromhex("f001f80178"), "malformed"),
    ("stream-two-case-controls", "CAP and UPPER before one token.", bytes.fromhex("f001f0020010"), "malformed"),
    ("stream-bad-utf8", "A literal that is not UTF-8.", bytes.fromhex("f802c328"), "malformed"),
    ("stream-too-large", "Decodes to more than 1024 bytes.", _lit(b"x" * 1000) + _lit(b"x" * 25), "too_large"),
]


#: Compact frames (SPEC section 3.7): 0x02, dictionary version, word stream. No Envelope, no id.
#: (name, note, text or None, dictionary, hex: given for decode-only cases, else computed and checked)
def _frame_text(size: int) -> str:
    """'We need QQQ…Q now' with as many Q as give a compact frame of exactly `size` bytes."""
    for n in range(100, 240):
        text = "We need " + "Q" * n + " now"
        if len(textwords.encode_words(text, 2)) + 2 == size:
            return text
    raise AssertionError(size)


FRAME_200, FRAME_201 = _frame_text(200), _frame_text(201)
COMPACT_FRAMES = [
    ("frame-hello-world", "Two common words: 2 bytes each, 2 bytes of frame. 6 bytes on air (11 as a normal text).",
     "hello world", 2, None),
    ("frame-name-coordinates", "A sentence with a name and coordinates: the literal keeps every character.",
     "We found Aylin Demir at 50.6900, 7.2134, she is conscious.", 2, None),
    ("frame-poem", "A poem with line breaks and repeated spaces comes back exactly.",
     "The river runs\n  under the bridge,\n\nand we wait   for morning.\n", 2, None),
    ("frame-emoji", "Emoji and Cyrillic go as literals.", "All safe 🙂👍 see you soon ❤️ Дякую", 2, None),
    ("frame-literal-129", "A literal of 129 bytes (past 127): the length lives in the 2-byte unit, no switch.",
     "Code " + "Z" * 127 + " ok", 2, None),
    ("frame-near-limit-385", "The 385-byte update of words-near-limit in one compact frame.", NEAR_LIMIT, 2, None),
    ("frame-exactly-200", "A frame of exactly 200 bytes, the encoder limit.", FRAME_200, 2, None),
    ("frame-dict-1", "A compact frame may also name word dictionary 1: [is] [safe] [.]",
     None, 1, "0201" + (_tok("is") + _tok("safe") + _tok(".")).hex()),
    ("frame-unknown-dict", "Word dictionary 9 is unknown: decoded as unknown with the number, shown as "
     "\"dictionary this app does not have\", never as words.", None, 9, "0209" + "00100011"),
]
COMPACT_FRAMES_INVALID = [
    ("frame-empty-text", "An empty text has no compact frame.", "", None, "missing_field"),
    ("frame-no-stream", "Version byte but no word stream.", None, "0202", "malformed"),
    ("frame-no-version", "Only the first byte.", None, "02", "malformed"),
    ("frame-dict-0", "Word dictionary 0 does not exist.", None, "0200" + "0017", "malformed"),
    ("frame-truncated", "hello world with its last byte missing: half a token.", None, "0202033e01", "malformed"),
    ("frame-truncated-literal", "Cut inside a literal.", None, "0202" + "f80c" + b" Aylin".hex(), "malformed"),
    ("frame-too-large", "234 bytes: more than Meshtastic carries.", None, "0202" + ("f8e6" + "78" * 230), "too_large"),
    ("frame-too-long-text", "A text whose frame would be 201 bytes, over the encoder limit.", FRAME_201, None,
     "too_large"),
]


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
    ("ack-node-broadcast-number", "Node number 0xffffffff is a valid node field value.",
     {"ack": {"ref": 7, "node": "!ffffffff", "status": "accepted"}}),
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
    ("ack-node-zero-string", "Node !00000000 is no node.", {"ack": {"ref": 7, "node": "!00000000", "status": "accepted"}},
     "missing_field", "ack.node"),
    ("v1-not-encodable", "Only v2 can be encoded.", {"v": 1, "id": 1, "found": {}}, "invalid_value", "v"),
    ("text-dict-unknown", "Only dictionary 1 exists.", {"text": {"text": "Hallo", "dict": 2}}, "invalid_value", "text.dict"),
    ("text-dict-zero", "Dictionary numbers start at 1.", {"text": {"text": "Hallo", "dict": 0}}, "invalid_value", "text.dict"),
    ("text-dict-string", "The dictionary is a number.", {"text": {"text": "Hallo", "dict": "1"}}, "invalid_value", "text.dict"),
    ("text-packed-1025", "A packed text is at most 1024 bytes unpacked.", {"text": {"text": "A" * 1025, "dict": 1}},
     "invalid_value", "text.text"),
    ("text-packed-empty", "A packed text must not be empty.", {"text": {"text": "", "dict": 1}}, "missing_field", "text.text"),
    ("text-words-empty", "A compact text must not be empty.", {"text": {"text": "", "words": 1}}, "missing_field", "text.text"),
    ("text-words-and-dict", "Give dict or words, not both.", {"text": {"text": "Hallo", "dict": 1, "words": 1}},
     "invalid_value", "text.words"),
    ("text-words-unknown", "Word dictionary 9 does not exist yet.", {"text": {"text": "Hallo", "words": 9}},
     "invalid_value", "text.words"),
    ("text-words-1025", "A compact text is at most 1024 bytes.", {"text": {"text": "A" * 1025, "words": 1}},
     "invalid_value", "text.text"),
]

# Decode-only cases: bytes another (newer or differently ordered) encoder may send.
DECODE_ONLY = [
    ("unknown-common-field", "Field 5 is unknown to v2.0: ignore it.", "0807" + "2801" + "3200",
     {"id": 7, "found": {}}),
    ("unknown-body-type", "Body field 14 is a newer message type: decode as unknown, do not reject.", "0807" + "7200",
     {"id": 7, "unknown": {}}),
    ("fields-out-of-order", "Decoders accept any field order.", "32020841" + "0807",
     {"id": 7, "found": {"consciousness": "conscious", "age": "child"}}),
    ("flag-bit-31", "The highest bit of a 32 bit flag field is bit_31.", "0807" + "3206" + "108080808008",
     {"id": 7, "found": {"injuries": ["bit_31"]}}),
    ("flag-bit-32-cut", "A flag value wider than 32 bits is cut to 32 bits: bit 32 disappears.", "0807" + "3206" + "108080808010",
     {"id": 7, "found": {}}),
    ("unknown-flag-bit", "Unknown injury bit 20 becomes bit_20.", "0807" + "3204" + "10808040",
     {"id": 7, "found": {"injuries": ["bit_20"]}}),
    ("reserved-state-bits", "State bits 9+ are reserved: ignore them.", "0807" + "3203" + "088104",
     {"id": 7, "found": {"consciousness": "conscious"}}),
    ("unknown-sector-status", "Status 9 from a newer version is kept as unknown_9.", "4204" + "0803" + "1009",
     {"sector": {"sector": 3, "status": "unknown_9"}}),
    ("non-packed-track", "Repeated numbers sent unpacked must also be accepted.", "3a04" + "0802" + "1004",
     {"track": {"points": [{"lat": 0.00001, "lon": 0.00002}]}}),
    ("packed-stored-block", "A DEFLATE stream that does not use the dictionary (one stored block) is valid too.",
     packed_wire(bytes.fromhex("010500faff") + b"Hallo"), {"id": 7, "text": {"text": "Hallo", "dict": 1}}),
    ("packed-no-dictionary-used", "Packed without the dictionary by another compressor: still valid.",
     packed_wire(_raw_deflate(b"Treffpunkt Parkplatz Nord")), {"id": 7, "text": {"text": "Treffpunkt Parkplatz Nord", "dict": 1}}),
    ("packed-1024", "A packed text of exactly 1024 bytes unpacked is the largest allowed.",
     packed_wire(_raw_deflate(b"A" * 1024)), {"id": 7, "text": {"text": "A" * 1024, "dict": 1}}),
    ("packed-unknown-dict", "Dictionary 2 is from a newer spec: decode as unknown, do not reject.",
     packed_wire(_raw_deflate(b"Hallo"), 2), {"id": 7, "unknown": {}}),
    ("words-unknown-dict", "Word dictionary 9 is unknown: decode as unknown with the number, shown as "
     "\"dictionary this app does not have\", never as words.",
     words_wire(bytes.fromhex("00100011"), 9), {"id": 7, "unknown": {"words": 9}}),
    ("words-by-hand", "A stream built by hand: [is] [safe] [.] decodes like the encoder's own.",
     words_wire(_tok("is") + _tok("safe") + _tok(".")), {"id": 7, "text": {"text": "is safe.", "words": 1}}),
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
    ("bad-utf8-overlong", "Text with an overlong UTF-8 sequence (c0 80).", "6a040a02c080", "malformed", None),
    ("varint-truncated-in-body", "Body ends inside a varint.", "0807" + "3202" + "1080", "malformed", None),
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
    ("wire-packed-and-text", "A text has words or packed words, not both.",
     packed_wire(_raw_deflate(b"Hallo"), 1, "Hallo"), "invalid_value", "text.deflate"),
    ("wire-packed-no-dict", "Packed words need their dictionary number.", packed_wire(_raw_deflate(b"Hallo"), None),
     "missing_field", "text.dict"),
    ("wire-dict-without-packed", "A dictionary number without packed words.", "0807" + _ld(13, bytes.fromhex(_ld(1, b"Hallo") + "1801")),
     "invalid_value", "text.dict"),
    ("wire-packed-broken", "Not a DEFLATE stream (block type 3 does not exist).", packed_wire(bytes.fromhex("ffff")),
     "malformed", "text.deflate"),
    ("wire-packed-cut", "A DEFLATE stream cut off before its end.", packed_wire(textpack.pack(HUT_EN)[:-3]),
     "malformed", "text.deflate"),
    ("wire-packed-trailing", "Bytes after the end of the DEFLATE stream.", packed_wire(textpack.pack("Hallo") + b"\x00"),
     "malformed", "text.deflate"),
    ("wire-packed-1025", "Unpacks to more than 1024 bytes: reject (zip bomb guard).", packed_wire(_raw_deflate(b"A" * 1025)),
     "too_large", "text.deflate"),
    ("wire-packed-bomb", "Unpacks to 100000 bytes: reject without unpacking it all.", packed_wire(_raw_deflate(b"A" * 100000)),
     "too_large", "text.deflate"),
    ("wire-packed-bad-utf8", "The unpacked bytes are not UTF-8.", packed_wire(_raw_deflate(b"\xff\xfeHallo")),
     "malformed", "text.deflate"),
    ("wire-packed-empty", "The packed words are empty.", packed_wire(_raw_deflate(b"")), "missing_field", "text.text"),
    ("wire-words-no-dict", "Word tokens need their dictionary number.", words_wire(_tok("safe"), None),
     "missing_field", "text.words_dict"),
    ("wire-words-dict-alone", "A word dictionary number without word tokens.",
     "0807" + _ld(13, bytes.fromhex(_ld(1, b"Hallo") + "2801")), "invalid_value", "text.words_dict"),
    ("wire-words-and-text", "A text has words or word tokens, not both.", words_wire(_tok("safe"), 1, extra=_ld(1, b"Hallo")),
     "invalid_value", "text.words"),
    ("wire-words-truncated", "The packet is cut inside a literal: the token stream ends early.",
     words_wire(_tok("We") + _tok("found") + bytes.fromhex("f80c") + b" Aylin"), "malformed", "text.words"),
    ("wire-words-half-token", "The token stream ends after one byte of a token.", words_wire(_tok("safe") + b"\x00"),
     "malformed", "text.words"),
    ("wire-words-unknown-control", "A reserved control code.", words_wire(bytes.fromhex("f7ff") + _tok("safe")),
     "malformed", "text.words"),
    ("wire-words-past-end", "A token past the end of the dictionary.", words_wire(bytes.fromhex("efff")),
     "malformed", "text.words"),
    ("wire-words-oversized-literal", "A literal length larger than the rest of the packet.",
     words_wire(bytes.fromhex("f8c8") + b"Aylin"), "malformed", "text.words"),
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
        "spec_version": "2.2.0",
        "about": ("RescueCodec v2 conformance vectors. See spec/SPEC.md, section 'Conformance'. "
                  "valid: encode(input) must give hex exactly; decode(hex) must give decoded. "
                  "text: Level 2, render(decoded) must give these strings. "
                  "decode_only: decode(hex) must give decoded. "
                  "invalid_encode / invalid_decode: must be rejected; error codes should match. "
                  "v1: decode_any(hex) must give decoded. "
                  "packed_text: unpack(deflate, dict) must give text, decode(hex) must give decoded, and "
                  "decode(encode(decoded)) must give decoded (compressors may make other bytes). "
                  "text_dicts: SHA-256 of each text dictionary file. "
                  "compact_text: encode(input) must give hex exactly, decode(hex) must give decoded, "
                  "decode_words(words, 1) must give text and encode_words(text, 1) must give words exactly. "
                  "word_streams: encode_words(text) must give words, decode_words(words) must give text. "
                  "word_streams_invalid: decode_words(words, 1) must be rejected with error. "
                  "word_dicts: SHA-256 and entry count of each word dictionary file. "
                  "compact_frames (SPEC 3.7, first byte 0x02): encode_compact(text, dict) must give hex exactly "
                  "(when text is given) and decode_compact(hex) must give decoded. "
                  "compact_frames_invalid: decode_compact(hex) must be rejected with error; encode_compact(text) too "
                  "when text is given."),
        "valid": [], "decode_only": [], "invalid_encode": [], "invalid_decode": [], "v1": [],
        "packed_text": [],
        "text_dicts": {str(n): {"file": f"vocab/{name}", "sha256": sha, "size": len(textpack.dictionary(n))}
                       for n, (name, sha) in textpack.DICTS.items()},
        "compact_text": [], "word_streams": [], "word_streams_invalid": [],
        "compact_frames": [], "compact_frames_invalid": [],
        "word_dicts": {str(n): {"file": f"vocab/{name}", "sha256": sha, "entries": len(textwords.dictionary(n).entries),
                                "size": (ROOT / "spec" / "vocab" / name).stat().st_size}
                       for n, (name, sha) in textwords.DICTS.items()},
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
    all_names = ([c[0] for c in VALID + DECODE_ONLY + INVALID_ENCODE + INVALID_DECODE + PACKED_TEXT + COMPACT_TEXT
                  + WORD_STREAMS + WORD_STREAMS_INVALID + COMPACT_FRAMES + COMPACT_FRAMES_INVALID]
                 + [c[0] for c in v1_cases()])
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
    for name, note, msg in PACKED_TEXT:
        data = encode(msg)
        decoded = decode(data)
        assert decoded == {k: v for k, v in msg.items() if k != "pos"} | ({"pos": decoded["pos"]} if "pos" in msg else {}), name
        deflate = textpack.pack(msg["text"]["text"])
        plain = len(msg["text"]["text"].encode("utf-8"))
        out["packed_text"].append({"name": name, "note": note, "text": msg["text"]["text"], "dict": 1,
                                   "deflate": deflate.hex(), "hex": data.hex(), "size": len(data),
                                   "text_size": plain, "decoded": decoded})
    for name, note, msg in COMPACT_TEXT:
        data = encode(msg)
        assert len(data) <= 200, name
        decoded = decode(data)
        assert encode(decoded) == data, name
        text = msg["text"]["text"]
        words = encode_words(text, 1)
        assert decode_words(words, 1) == text and decoded["text"]["text"] == text, name
        out["compact_text"].append({"name": name, "note": note, "input": msg, "text": text, "words": words.hex(),
                                    "hex": data.hex(), "size": len(data), "text_size": len(text.encode("utf-8")),
                                    "decoded": decoded,
                                    "render": {"en": render(decoded, "en"), "de": render(decoded, "de")}})
    for name, note, text in WORD_STREAMS:
        words = encode_words(text, 1)
        assert decode_words(words, 1) == text, name
        out["word_streams"].append({"name": name, "note": note, "text": text, "words": words.hex()})
    for name, note, words, code in WORD_STREAMS_INVALID:
        try:
            decode_words(words, 1)
        except textwords.WordsError as exc:
            assert exc.code == code, (name, exc.code)
        else:
            raise AssertionError(f"{name} was accepted")
        out["word_streams_invalid"].append({"name": name, "note": note, "words": words.hex(), "error": code})
    for name, note, text, version, hexstr in COMPACT_FRAMES:
        if text is not None:
            data = encode_compact(text, version)
            assert hexstr is None or data.hex() == hexstr, name
            hexstr = data.hex()
        data = bytes.fromhex(hexstr)
        decoded = decode_compact(data)
        assert text is None or decoded == {"text": {"text": text, "words": version}}, name
        assert decode_any(data) == {"v": 2, **decoded}, name
        case = {"name": name, "note": note}
        if text is not None:
            case.update(text=text, dict=version, text_size=len(text.encode("utf-8")))
        case.update(hex=hexstr, size=len(data), decoded=decoded,
                    render={"en": render(decoded, "en"), "de": render(decoded, "de")})
        out["compact_frames"].append(case)
    for name, note, text, hexstr, code in COMPACT_FRAMES_INVALID:
        case = {"name": name, "note": note}
        if text is not None:
            try:
                encode_compact(text)
            except CodecError as exc:
                assert exc.code == code, (name, exc.code)
            else:
                raise AssertionError(f"{name} was accepted")
            case["text"] = text
        if hexstr is not None:
            try:
                decode_compact(bytes.fromhex(hexstr))
            except CodecError as exc:
                assert exc.code == code, (name, exc.code)
            else:
                raise AssertionError(f"{name} was accepted")
            case["hex"] = hexstr
        case["error"] = code
        out["compact_frames_invalid"].append(case)
    for name, note, data in v1_cases():
        out["v1"].append({"name": name, "note": note, "hex": data.hex(), "size": len(data), "decoded": decode_any(data)})
    path = ROOT / "spec" / "vectors" / "codec.json"
    path.write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    sections = ("valid", "decode_only", "invalid_encode", "invalid_decode", "v1", "packed_text", "compact_text",
                "word_streams", "word_streams_invalid", "compact_frames", "compact_frames_invalid")
    total = sum(len(out[k]) for k in sections)
    print(f"wrote {path.relative_to(ROOT)}: {total} cases " + ", ".join(f"{k}={len(out[k])}" for k in sections))


if __name__ == "__main__":
    main()
