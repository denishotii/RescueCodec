# RescueCodec v2 specification

Version 2.0.0, 25 Nov 2026. Licence: Apache-2.0 (spec, vocabulary, test vectors and libraries).

## 1. Purpose

A volunteer in a forest with no phone signal says one sentence: "Kind gefunden, ansprechbar, stark unterkühlt, Knöchel verletzt. Wir brauchen eine Trage und zwei Leute."

RescueCodec turns that into **23 bytes**. The bytes ride on a Meshtastic LoRa mesh. The command post decodes them and shows "Person found: child, conscious, hypothermia, fracture, needs stretcher, 2 more helpers needed." in German or English. The command post presses Accept, and 22 bytes go back.

The spec is open so that any app, radio or control-room tool can send and read the same messages. Everything works offline.

What this spec fixes:
- the wire format (one protobuf file, `rescuecodec/v2/rescuecodec.proto`),
- one JSON shape for the same data (used by the REST API, MQTT and the test vectors),
- the codes and their words (`vocab/sar.yaml`, `i18n/*.json`),
- test vectors that every library must pass (`vectors/codec.json`, `vectors/extraction.json`),
- the gateway API (`api/openapi.yaml`).

The key words MUST, SHOULD and MAY have their usual meaning (RFC 2119).

## 2. Transport on Meshtastic

| Setting | Value |
|---|---|
| Port | `PRIVATE_APP` (256). See section 13 for a registered port later. |
| Destination | Broadcast (`^all`) on a private channel, so every team sees reports and who took them. |
| Channel | A private channel with its own random 256-bit key (AES-256). Never the default `LongFast` key. |
| Region and preset | EU_868, LONG_FAST on our radios. The format does not depend on them. |
| Payload | One serialized `Envelope`. Nothing before or after it. |
| Sender | The Meshtastic packet's `from` node number. It is not repeated in the payload. |
| Delivery check | Senders SHOULD set `want_ack`. Meshtastic then retries and reports delivery to a neighbour. The RescueCodec `Ack` is the real "a person took it" answer. |
| Size | Encoders MUST NOT produce more than 200 bytes. Decoders MUST accept up to 233 bytes (Meshtastic's payload limit). |

Meshtastic adds about 23 bytes of its own around every payload (16-byte header plus the `Data` wrapper). That is why squeezing a 20-byte message to 16 bytes barely changes airtime, while putting 10 GPS points into one packet saves a lot (section 9).

**Priority.** Every message has a priority (section 4.2). Senders SHOULD queue by it (critical first) and SHOULD set the Meshtastic `MeshPacket.priority` like this:

| RescueCodec | Default for | Meshtastic priority |
|---|---|---|
| critical | mayday | ALERT (110) |
| urgent | found, hazard, ack | HIGH (100) |
| normal | request, text | RELIABLE (70) |
| routine | sector, track | BACKGROUND (10) |

An unknown priority value from a newer sender is treated as normal.

## 3. Wire format

A packet is one protobuf (proto3) message `rescuecodec.v2.Envelope`, written with the standard protobuf encoding. The full definition with comments is in `rescuecodec/v2/rescuecodec.proto`.

```
Envelope
  1  id        uint32     per-sender counter 1..16383, 0 = none
  2  priority  Priority   0 = type default
  3  pos       Position   lat, lon (sint32, degrees x 1e5), acc
  4  time      uint32     optional, minute of the UTC day 0..1439
  5            (free for a future common field)
  oneof body:
  6  found     7  track     8  sector    9  request
  10 hazard    11 mayday    12 ack       13 text
  14, 15       (free for future frequent message types)
```

### 3.1 Canonical encoding

All libraries MUST produce exactly the same bytes for the same message. This is what the official protobuf libraries do by default:
- fields in ascending field-number order (also inside sub-messages),
- scalar fields with value 0 and empty strings are left out,
- the `optional` field `time` is written whenever it is set, even when it is 0 (midnight),
- a set sub-message is written even when it is empty (an empty `found` is `32 00`),
- repeated numbers are packed (one length-prefixed block per field),
- no unknown fields.

Decoders MUST accept fields in any order, unpacked repeated numbers, and unknown fields.

### 3.2 Byte by byte: a typical found report (23 bytes)

Vector `found-typical-gps`: `08071a0908b8e6ea041082875832080841100518012802`

| Bytes | Meaning |
|---|---|
| `08 07` | id = 7 |
| `1a 09` | pos, 9 bytes follow |
| `08 b8 e6 ea 04` | lat = 5069212 (50.69212), zigzag varint |
| `10 82 87 58` | lon = 721345 (7.21345) |
| `32 08` | found, 8 bytes follow |
| `08 41` | state = 0x41: consciousness conscious (1, bits 0-1), age child (1, bits 6-8) |
| `10 05` | injuries = hypothermia (bit 0) + fracture (bit 2) |
| `18 01` | needs = stretcher (bit 0) |
| `28 02` | responders = 2 |

The matching Accept (vector `ack-accepted`, 22 bytes): `62 14` ack, `08 07` ref 7, `15 d4c3b2a1` node !a1b2c3d4 (fixed32, little endian), `18 01` accepted, `20 0f` ETA 15 min, `2a 07 "Trupp 3"` team label.

### 3.3 Common fields

- **id**: a counter per sender, 1 to 16383 (at most 2 bytes). The message key is (Meshtastic sender node, id). Senders SHOULD keep the counter across restarts; if they cannot, they start at a random value. After 16383 comes 1. `found`, `request`, `hazard` and `mayday` MUST have an id, because they can be answered. Senders SHOULD also give every other message an id, except an `ack`: it costs 1 to 2 bytes and it lets a receiver tell a second identical text, track or sector from a repeated packet (section 14). An `ack` carries none, because sending the same Accept twice is a repeat on purpose.
- **Same id, same message.** An id seen again from the same sender is a retransmit, never an update. A sender who wants to change a report sends a new one with a new id. The task key stays valid only for the first. A sender that loses its counter (app data cleared) starts at a random value, so a clash with an id from the last 24 hours is unlikely (about N in 16383 after N earlier messages) but possible. Test devices should keep their app data between runs, or the receiver should start with a fresh database.
- **priority**: see section 2. Leave it out to use the type default. A sender writes it only when it differs from the default (writing the default is allowed and costs 2 bytes).
- **pos**: latitude and longitude as integers, degrees x 100000 (about 1.1 m), zigzag (`sint32`). `acc` says how good it is: `gps` (0, default, a normal fix, about 10 m), `gps_poor` (about 50 m), `estimated` (spoken or picked on a map, about 250 m), `area` (rough area, about 1 km). If you do not know, send `estimated`. Position 0,0 is valid: the sub-message is then present but empty.
- **time**: minute of the UTC day when it happened (0 to 1439). The date is implied. The receiver picks the most recent matching minute that is at most 10 minutes after its own clock. Example: at 00:05 a "23:58" means yesterday 23:58. Leave `time` out when the message is sent at once; the receive time is then good enough. Python helper: `resolve_time()`.

### 3.4 Message bodies

**found** (person found)

| Field | Meaning |
|---|---|
| `state` | Packed small states: bits 0-1 consciousness (1 conscious, 2 confused, 3 unconscious), bits 2-3 breathing (1 normal, 2 difficult, 3 none), bits 4-5 mobility (1 walks, 2 cannot walk, 3 trapped), bits 6-8 age group (1 child 0-12, 2 teen 13-17, 3 adult, 4 senior 65+). 0 = unknown. Bits 9 and up are reserved: write 0, ignore on read. |
| `injuries` | Bit flags: 0 hypothermia, 1 bleeding, 2 fracture, 3 head, 4 spine (back or neck), 5 exhaustion, 6 other, 7 cardiac (chest pain), 8 heat, 9 burn, 10 allergy. |
| `needs` | Bit flags: 0 stretcher, 1 medic, 2 helicopter, 3 warmth, 4 water, 5 vehicle, 6 other, 7 AED, 8 oxygen, 9 first aid kit, 10 rope team, 11 light, 12 food, 13 radio. |
| `count` | Persons found. 0 = not stated. Use one message per person when their states differ. |
| `responders` | More helpers needed on site. 0 = not stated. |

The packing keeps a typical found at 23 bytes. Five separate enum fields would cost 5 bytes more. The most common flags are in bits 0 to 6, so they fit in one byte.

**track** (batch of GPS points from one searcher, oldest first, 1 to 16 points)

`lat` and `lon` are packed `sint32` lists. Entry 0 is absolute (degrees x 1e5). Every later entry is the difference to the previous point, so a step of 40 m costs about 1 byte. `dt` is empty (no times) or has one entry per point: `dt[0]` is seconds after the start of the `time` minute (then `time` is required), every later entry is seconds after the previous point. All three lists have the same length (or `dt` is empty).

**sector**: `sector` number from the search plan (1 to 65535, required), `status` (1 cleared, 2 partial, 3 blocked; required), `coverage` percent searched (0 = not stated).

**request**: `resource` (required; 1 stretcher, 2 medic, 3 helicopter, 4 vehicle, 5 searchers, 6 dog team, 7 drone, 8 light, 9 water, 10 food, 11 warmth, 12 batteries, 13 rope team, 14 ambulance, 15 fire brigade, 16 other), `count` (0 = not stated, one), `urgency` (1 now, 2 within about 30 min, 3 later).

**hazard**: `kind` (required; 1 blocked path, 2 rockfall, 3 high water, 4 fire or smoke, 5 cliff, 6 ice or snow, 7 dangerous animal, 8 power line down, 9 hunting, 10 storm or lightning, 11 other), `severity` (1 low, 2 medium, 3 high, keep out), `radius` in metres around `pos` (0 = not stated, at most 100000).

**mayday** (a rescuer needs help): `reason` (1 injured, 2 lost, 3 team member missing, 4 medical, 5 trapped, 6 threat, 7 other; 0 = not stated), `persons` affected. An empty mayday (SOS button) is valid. Always send `pos` if the device has one.

**ack** (answer to a message with an id): `ref` = id of the answered message (required), `node` = Meshtastic node number of its sender (`fixed32`, required), `status` (1 accepted, 2 declined, 3 done; required), `eta` minutes (0 = not stated, at most 1440), `team` label (at most 12 bytes UTF-8, for example "Trupp 3", never a personal name). Acks are broadcast, so every team sees who took a task.

**text**: 1 to 180 bytes of UTF-8 for anything the codes cannot say.

## 4. Canonical JSON

Every library, the gateway API, MQTT and the test vectors use one JSON shape. Rules:
- Keys are the proto field names, except where noted below.
- Exactly one body key: `found`, `track`, `sector`, `request`, `hazard`, `mayday`, `ack`, `text`. A decoder that meets a body type it does not know returns the body key `unknown` with `{}`.
- Zero and "not stated" values are left out. An empty body is `{}`.
- Enum values are lowercase names: `"consciousness": "unconscious"`. A value a decoder does not know becomes `"unknown_<number>"`, for example `"unknown_6"`. Encoders accept that form, so a gateway can relay it.
- Bit flags are lists of names in bit order: `"injuries": ["hypothermia", "fracture"]`. An unknown bit becomes `"bit_<number>"`. Encoders accept any order and ignore duplicates.
- `found.state` is split into `consciousness`, `breathing`, `mobility` and `age`.
- `pos.lat` and `pos.lon` are degrees as JSON numbers. Encoders round to 5 decimals, half away from zero, on the decimal value. `acc` is left out when it is `gps`.
- `time` is `"HH:MM"` (UTC, two digits each).
- `track` is `{"points": [{"lat", "lon", "s"}]}` with absolute positions. `s` is seconds after the `time` minute, cumulative (never goes down). Give `s` on every point or on none.
- `ack.node` is the Meshtastic id string `"!a1b2c3d4"` (8 lowercase hex digits).
- Decoders of old v1 packets add `"v": 1` (section 7). v2 decoders MAY add `"v": 2`.

Example (the found report from section 3.2):

```json
{"id": 7, "pos": {"lat": 50.69212, "lon": 7.21345},
 "found": {"age": "child", "consciousness": "conscious",
           "injuries": ["hypothermia", "fracture"], "needs": ["stretcher"], "responders": 2}}
```

## 5. Validation and errors

Encoders and decoders apply the same value rules. A decoder MUST reject a packet that breaks them. Each rejection has a stable error code and, where it makes sense, a field path such as `pos.lat`:

| Code | When |
|---|---|
| `empty` | Zero bytes. |
| `too_large` | Encoder output over 200 bytes, or decoder input over 233 bytes. |
| `unsupported_version` | First byte 0x00 or 0x02..0x07 (reserved), or 0x01 given to a v2-only decoder. |
| `malformed` | Not valid protobuf (cut-off varint, length past the end, invalid UTF-8 in a string). |
| `no_body` | No body field at all, and no unknown field that could be a newer body type. |
| `multiple_bodies` | JSON with more than one body key. (On the wire protobuf keeps the last one.) |
| `unknown_key` | JSON key that the spec does not define. |
| `missing_field` | A required value is 0 or missing: id for found/request/hazard/mayday, `sector.sector`, `sector.status`, `request.resource`, `hazard.kind`, `ack.ref`, `ack.node`, `ack.status`, `text.text`, track points, `time` for a track with times. |
| `invalid_value` | Out of range or wrong type: id over 16383, lat outside -90..90, lon outside -180..180, time over 1439, counts over 255, coverage over 100, radius over 100000, eta over 1440, team over 12 bytes, text over 180 bytes, more than 16 track points, track lists of different length, unknown names in JSON. |

Forward compatibility comes first: unknown enum numbers, unknown flag bits, reserved state bits and unknown fields are **not** errors.

## 6. Limits

| Limit | Value |
|---|---|
| Packet, encoder | 200 bytes |
| Packet, decoder | 233 bytes |
| id, ack.ref | 1..16383 |
| Track points | 1..16 per packet |
| Text | 1..180 bytes UTF-8 |
| Team label | 0..12 bytes UTF-8 |
| count, responders, persons | 0..255 |

## 7. Version detection and v1 compatibility

The first byte tells the version:

| First byte | Meaning |
|---|---|
| `0x01` | v1 packet (the team's first prototype). Decode with the v1 rules. |
| `0x00`, `0x02` to `0x07` | Reserved for future incompatible versions. Reject with `unsupported_version`. |
| `0x08` to `0xFF` | v2 protobuf `Envelope`. |

Why this works: a protobuf field starts with a tag byte `(field number << 3) | wire type`. Field numbers start at 1, so the first byte of any protobuf message is at least `0x08`. Bytes `0x00` to `0x07` would mean field 0, which protobuf forbids. So a v2 packet can never be mistaken for v1, and we keep seven markers for later versions. Tests: `tests/test_v2_compat.py`.

v1 packets are decoded, never produced. They map to canonical JSON with `"v": 1`:

| v1 | v2 JSON |
|---|---|
| request id (32 bit) | `id` (may be above 16383, so do not re-encode) |
| urgency routine / urgent / critical | `priority` |
| location + quality 3 / 2 / 1 / 0 | `pos` with acc gps / estimated / area / estimated |
| timestamp (Unix seconds) | `time` "HH:MM" plus `v1_timestamp` |
| consciousness conscious / unconscious | `found.consciousness` |
| breathing yes / no | `found.breathing` normal / none |
| equipment oxygen mask, oxygen, first aid kit, AED, stretcher, water, radio, other | `found.needs` oxygen, oxygen, first_aid, aed, stretcher, water, radio, other |
| request medical / extraction / search, fire, other | `found.needs` medic / rope / other |
| person count, responders | `found.count`, `found.responders` |
| text packet | `text` |
| confirmation accepted / completed | `ack` accepted / done (no `node`) |

The v1 vectors are in `vectors/codec.json` under `v1`.

## 8. Versioning and forward compatibility

- The spec has a semantic version. The package name `rescuecodec.v2` stays for all 2.x versions.
- **Minor version (2.1, 2.2)**: only additions. New fields, new message types (body fields 14, 15, then 16 and up), new enum values, new flag bits, new state bits (9 and up). Old readers keep working.
- **Never** change the number, type or meaning of an existing field or value. Never reuse a removed number; mark it `reserved` in the proto.
- **Readers** MUST ignore unknown fields, keep unknown enum values as `unknown_<n>`, keep unknown flag bits as `bit_<n>`, ignore reserved state bits, and show an unknown body type as "message of a newer type" instead of dropping it.
- **Major version (v3)** only if something cannot be added. It will start with one of the reserved first bytes `0x02` to `0x07`.
- Libraries use their own semantic versions and say which spec version they pass.

## 9. Sizes (measured)

Bytes are measured from the test vectors. Airtime is **estimated** with the LoRa formula for LONG_FAST (SF11, 250 kHz, CR 4/5, 16 preamble symbols) plus 23 bytes of Meshtastic overhead. "As English text" is the readable line from section 10 sent as a normal text message.

| Message | Vector | RescueCodec | As English text | Airtime RescueCodec | Airtime text |
|---|---|---|---|---|---|
| Found, pitch sentence, no GPS | `found-pitch` | 11 B | 63 B | 0.52 s | 0.89 s |
| Found, typical, with GPS | `found-typical-gps` | 23 B | 122 B | 0.60 s | 1.34 s |
| Found, every field, GPS and time | `found-full` | 34 B | 270 B | 0.68 s | 2.44 s |
| Sector partial 60% | `sector-partial` | 8 B | 40 B | 0.48 s | 0.72 s |
| Request 10 searchers now, GPS | `request-searchers` | 23 B | 73 B | 0.60 s | 0.97 s |
| Hazard rockfall, radius, GPS | `hazard-rockfall` | 22 B | 80 B | 0.60 s | 1.01 s |
| Mayday (SOS button), GPS | `mayday-sos` | 15 B | 35 B | 0.52 s | 0.68 s |
| Mayday injured, GPS and time | `mayday-injured` | 22 B | 87 B | 0.60 s | 1.05 s |
| Ack accepted, ETA, team | `ack-accepted` | 22 B | 52 B | 0.60 s | 0.80 s |
| Ack done | `ack-done` | 11 B | 24 B | 0.52 s | 0.60 s |
| Text, 31 characters | `text-en` | 37 B | 41 B | 0.68 s | 0.72 s |
| Track, 1 point | `track-1` | 13 B | | 0.52 s | |
| Track, 10 points with times | `track-10` | 48 B | | 0.76 s | |
| Track, 16 points (maximum) | `track-16-max` | 64 B | | 0.89 s | |

**Batching wins.** 10 GPS points as 10 separate packets take about 5.2 s of airtime. As one `track` packet they take 0.76 s, about 7 times less. A single report is only about 2 times shorter on air than text, because the radio's own overhead dominates below about 20 bytes.

Compared with v1 (vectors `v1-pitch` and `v1-full`): the pitch sentence is 12 bytes in v1 and 11 in v2. The full v1 report with GPS, time, counts and an explicit critical priority is 25 bytes in v1 and 29 in v2 (the explicit priority alone costs 2 bytes). Protobuf costs nothing for small reports and a few bytes for full ones, and buys forward compatibility.

## 10. Readable text

Any receiver can rebuild a readable line from the codes. The words come from `i18n/<lang>.json`; the algorithm is fixed, so every library gives the same line (Level 2 conformance, section 12). Languages: `en` and `de` are reviewed; `uk`, `tr` and `ar` are **machine drafts** (`"status": "machine-draft"`) and need a native speaker before real use.

Algorithm for a message `m` and language file `L`:
1. Build `head` and a list `parts` for the body type:
   - found: head `found` (or `found_many` with `{n}` when count is 2 or more). Parts in this order: `value` of age, consciousness, breathing, mobility; each injury; `found.needs` with `{list}`; `found.responders` with `{n}`.
   - track: head `track`. Parts: `track.points_1` for one point, else `track.points`; `track.minutes` with `{n}` = round((last s - first s) / 60), half up, only if above 0; `track.last` with the last point.
   - sector: head `sector` with `{sector}`. Parts: status value; `sector.coverage` if set.
   - request: head `request`. Parts: resource value (or `request.count` with `{n}` and `{resource}` when count is 2 or more); urgency value.
   - hazard: head `hazard`. Parts: kind value; severity value; `hazard.radius`.
   - mayday: head `mayday`. Parts: reason value; `mayday.persons_1` or `mayday.persons`.
   - ack: head `ack.<status>` with `{ref}` (or `ack.other`). Parts: `ack.node`, `ack.team`, `ack.eta`.
   - text: head `text`. Parts: the text itself.
   - unknown body: head `unknown`, no parts.
2. A value is `L.value["<group>.<code>"]`. Groups: age, consciousness, breathing, mobility, injuries, needs, sector, resource, urgency, hazard, severity, mayday, priority, acc. A value `unknown_<n>` or `bit_<n>` gives `part.unknown_value` with `{n}`.
3. A list is joined with `punct.list_sep`, the last two with `punct.list_last` ("stretcher, medic and water").
4. `line = head`, plus `punct.head_sep` and the parts joined with `punct.part_sep` if there are parts. Add `punct.end` unless the line already ends with `.`, `!` or `?`.
5. If `priority` is set and differs from the type default: `"[" + value + "] "` in front.
6. If `pos` is set: a space, `part.pos` with `{lat}` and `{lon}`, then `" (" + acc value + ")"` if acc is not gps, then `punct.end`. Coordinates are printed from the integer value with exactly 5 decimals and a `.` (for example `50.69212`, `-0.00001`), never with locale formatting.
7. If `time` is set: a space, `part.time` with `{time}`, then `punct.end`.
8. Placeholders are replaced as plain text: `{n}`, `{list}`, `{lat}`, `{lon}`, `{time}`, `{ref}`, `{node}`, `{team}`, `{sector}`, `{resource}`.

Example: `Person gefunden: Kind, ansprechbar, Unterkühlung, Knochenbruch, braucht Trage, 2 weitere Helfer benötigt. Position 50.69212, 7.21345.`

The line is also what stock Meshtastic apps (and apps that only show text) can receive from the gateway's readable-text connector.

## 11. Extraction (speech or typing to codes)

`vocab/sar.yaml` lists every code with its wire id, meaning and German and English words. Rule-based extractors (Kotlin on the phone, Python on the gateway) use these words; the file is data, so new words do not need new code. It also lists negation words ("nicht", "kein", "not"), uncertainty words ("unklar", "maybe"), number words ("zwo" = 2) and words for helpers, radius and coverage.

Rules for extractors:
- Only what the sentence says. Unknown stays unknown. "Atmung unklar" leaves breathing unset.
- Negation turns a cue off: "nicht unterkühlt" sets nothing. "nicht ansprechbar" is unconscious.
- One message per report. If the sentence holds facts that one message cannot carry safely (two persons in different states, an object found instead of a person), the extractor MUST NOT guess. It offers the text fallback.
- No id and no device GPS: the app adds both. Spoken coordinates get `acc: estimated`.
- A person always checks the fields before sending.

`vectors/extraction.json` has 70 test sentences (42 German, 28 English) in volunteer style, with typos, negations and quantities; 10 are marked `unsupported` and expect the text fallback. Every extractor SHOULD pass them; the Kotlin and Python extractors MUST give the same fields for each.

## 12. Conformance: writing a new library

1. Generate code from `rescuecodec/v2/rescuecodec.proto` with the official protobuf tools for your language (for example `protoc --kotlin_out`, `protoc-gen-es` or `ts-proto` for TypeScript). Use the official runtime, so canonical encoding comes for free.
2. Write the JSON mapping from section 4 and the validation from section 5. Use `rescuecodec/v2/codec.py` as the reference.
3. Load `vectors/codec.json` in your test suite:
   - `valid`: `encode(input)` gives `hex` exactly, and `decode(hex)` gives `decoded` (compare as parsed JSON).
   - `decode_only`: `decode(hex)` gives `decoded`.
   - `invalid_encode`, `invalid_decode`: rejected. Error codes SHOULD match `error`; `field` MAY be compared.
   - `v1`: if you support v1, `decode_any(hex)` gives `decoded`.
4. Level 2 (optional): `render(decoded, "en")` and `"de"` give the `text` strings exactly.
5. Level 3 (optional): your extractor passes `vectors/extraction.json`.
6. State in your README: "Passes RescueCodec 2.0.0 conformance Level 1 (and 2, 3)". That is the proof. Never edit the vectors to make a library pass; report a bug instead.

The Python reference is `rescuecodec/v2/` (`encode`, `decode`, `decode_any`, `render`, `resolve_time`). It passes all levels except 3 (the extractor is a separate job). Regenerate its protobuf code with `scripts/gen-proto.sh` and the vectors with `spec/tools/gen_vectors.py`.

## 13. Port number

- **Now: `PRIVATE_APP` (256).** Every Meshtastic library can send on it and stock nodes relay it. Other private apps may use 256 too, which is why decoders validate strictly and never crash on junk (tested with every 1- and 2-byte payload).
- **Next: a registered port.** Meshtastic assigns application ports in `portnums.proto` (application range 64 to 127) through a pull request to `meshtastic/protobufs`, as GroupAlarm got 112. With an open spec, vectors and two libraries, we can ask for one. The detection rule (first byte) stays the same on any port.
- **Possible fit: port 38 `PAGING_APP`**, reserved in Sep 2026 for "acknowledged paging", with no format defined yet. Our found, request and mayday messages with Accept and Done are acknowledged paging for rescue teams. We would propose RescueCodec as its format to the Meshtastic team rather than use it without agreement.
- Receivers SHOULD listen on 256 and on any later registered port during a change-over.

## 14. Security and privacy

- **Encryption** comes from Meshtastic: every packet on a channel is encrypted with the channel key (AES-256 for a private channel). RescueCodec adds no crypto of its own. Use a private channel with a random key, share it by QR code only with the team, and never use the default key. Anyone with the key can read and send.
- **No personal names** in any field. Team labels are like "Trupp 3" or "DLRG 2". Free text SHOULD avoid names too. Found persons are described by age group and state only. Node long names show in the gateway's node list, so ask teams to name radios by team, not by person.
- **Spoofing**: a member of the channel can send fake reports or Acks. That is the same trust model as voice radio. The gateway logs the sender node of everything, and a person checks before acting.
- **Duplicates**: the mesh can deliver a packet twice. Receivers dedupe by (sender node, id) within 24 hours, and by (sender node, payload bytes) within 10 minutes for messages without an id. Because senders give every message but an Ack an id, the payload rule is a fallback for older senders.
- **Channel**: port 256 is free for any app, and a radio that also has the public LongFast channel hears other teams' packets on it. Receivers MUST accept RescueCodec packets only from their private channel (the Meshtastic channel index of the packet), and senders MUST send only on it. The gateway and the app find it by the name `RescueCodec`, else the only channel with its own key, and refuse to send when there is none.
- **Acks and the channel key**: the channel key is the only trust boundary. Anyone who holds it can send an Ack for any task, and a `done` closes it for everyone. Receivers bound the memory they spend on Acks for tasks they have not seen (500 placeholders, 30 minutes).
- **Strict parsing**: decoders reject anything outside the rules in section 5 and never pass unchecked data on.
- **Location** is sensitive. The gateway API listens on localhost by default. It refuses to listen on another address without a token (`--token`), checks the Origin of every WebSocket (CORS does not protect WebSockets), and limits how many messages the API may queue per minute.
- **Delivery wording**: with `want_ack` on a broadcast, Meshtastic's own acknowledgement only means that a neighbour repeated the packet. UIs and APIs say "relayed by a neighbour", never "delivered to the team". The RescueCodec `ack` from a person is the proof that someone received it.

## 15. Tasks and Accept (informative)

Receivers that show tasks (the gateway, the app) treat every `found`, `request`, `hazard` and `mayday` as a task with key `<sender node hex>-<id>`, for example `a1b2c3d4-7`. The `ack`s with that `ref` and `node` set the task state, using the newest answer of each responder node: `done` from anyone is final; else `accepted` while any responder's newest answer is `accepted`; else `declined` (another team may still accept) if any responder's newest answer is `declined`; else `open`. Example: team A accepts, then team B declines. The task stays `accepted`, because A still has it. Mayday always goes to the top. The API in `api/openapi.yaml` describes the gateway side.

### 15.1 Send queue and task rules (informative)

These rules come from the Python engine. The Kotlin and TypeScript engines SHOULD copy them so all three behave the same.

**Send order.** Mayday first. Then Found, Request and Ack. Then Hazard, Sector, Track, Text. Inside one rank the higher priority (critical, urgent, normal, routine) goes first, then first in, first out.

**Airtime budget.** Sliding window of 3600 s, 10 percent duty cycle = 360 s. All airtime numbers are estimates (16 preamble symbols, about 23 B Meshtastic overhead). An item is held when sending it would push the used share above its limit. Held items stay queued and go out when the window frees up.

| Type | Held above |
|---|---|
| mayday, ack | never held (airtime still counts) |
| found, request, hazard | 100 % |
| sector | 90 % |
| text, track | 70 % |

**Task states.** `open`, `accepted`, `declined`, `done`, `unknown`. `unknown` is a placeholder: an Ack arrived for a task we have not seen. It resolves when the task message arrives.

**Own moves** (the user taps Accept, Decline or Done):

| From | Allowed to |
|---|---|
| open | accepted, declined |
| accepted | done, declined |
| declined | accepted |
| done | nothing (final) |

Any other move is an `invalid_transition` error. A valid move produces an Ack to send.

**Incoming Acks.** Keep the newest answer of each responder node. Then: any `done` (from anyone, at any time) makes the task `done`, and it stays final. Else `accepted` if any responder's newest answer is `accepted`. Else `declined` if any responder's newest answer is `declined`. Else `open`. Your own Acks count as one more responder. Every Ack is also kept in the task history.

**Dedupe.** Same as section 14: (sender node, id) for 24 hours, (sender node, payload bytes) for 10 minutes when there is no id.

## 16. Files

| File | What |
|---|---|
| `spec/SPEC.md` | This document |
| `spec/rescuecodec/v2/rescuecodec.proto` | Wire format |
| `spec/vocab/sar.yaml` | Codes, meanings, German and English words |
| `spec/i18n/{en,de,uk,tr,ar}.json` | Words for readable text (uk, tr, ar are machine drafts) |
| `spec/vectors/codec.json` | 113 conformance cases (42 valid, 7 decode-only, 59 invalid, 5 v1) |
| `spec/vectors/extraction.json` | 73 extraction sentences |
| `spec/api/openapi.yaml` | Gateway REST and WebSocket API |
| `spec/tools/gen_vectors.py` | Regenerates `codec.json` from the reference |
| `rescuecodec/v2/` | Python reference library |
