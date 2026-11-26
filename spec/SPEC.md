# RescueCodec v2 specification

Version 2.3.0, 25 Nov 2026 (2.1 adds compact texts, section 3.6; 2.2 adds the compact frame, section 3.7, and word dictionary 2; 2.3 adds the domain frame with team vocabularies, section 3.8). Licence: Apache-2.0 (spec, vocabulary, test vectors and libraries).

## 1. Purpose

A volunteer in a forest with no phone signal says one sentence: "Kind gefunden, ansprechbar, stark unterkühlt, Knöchel verletzt. Wir brauchen eine Trage und zwei Leute."

RescueCodec turns that into **23 bytes**. The bytes ride on a Meshtastic LoRa mesh. The command post decodes them and shows "Person found: child, conscious, hypothermia, fracture, needs stretcher, 2 more helpers needed." in German or English. The command post presses Accept, and 22 bytes go back.

The spec is open so that any app, radio or control-room tool can send and read the same messages. Everything works offline.

What this spec fixes:
- the wire format (one protobuf file, `rescuecodec/v2/rescuecodec.proto`),
- one JSON shape for the same data (used by the REST API, MQTT and the test vectors),
- the codes and their words (`vocab/sar.yaml`, `i18n/*.json`),
- the shared dictionaries and team vocabularies (`vocab/`),
- test vectors that every library must pass (`vectors/codec.json`, `vectors/domain.json`, `vectors/extraction.json`),
- the gateway API (`api/openapi.yaml`).

The key words MUST, SHOULD and MAY have their usual meaning (RFC 2119).

## 2. Transport on Meshtastic

| Setting | Value |
|---|---|
| Port | `PRIVATE_APP` (256). See section 13 for a registered port later. |
| Destination | Broadcast (`^all`) on a private channel, so every team sees reports and who took them. |
| Channel | A private channel with its own random 256-bit key (AES-256). Never the default `LongFast` key. |
| Region and preset | EU_868, LONG_FAST on our radios. The format does not depend on them. |
| Payload | One serialized `Envelope`, one compact frame (first byte `0x02`, section 3.7) or one domain frame (first byte `0x03`, section 3.8). Nothing before or after it. |
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

### 2.1 Normal Meshtastic texts (port 1)

RescueCodec apps and gateways also show and send normal Meshtastic texts (`TEXT_MESSAGE_APP`, port 1), so a team member with the official Meshtastic app or a radio with a screen can take part. These texts are not RescueCodec packets: the payload is the UTF-8 text, nothing else.

| Rule | Value |
|---|---|
| Receive | Only a broadcast on a channel the receiver listens on (its private channels, section 2.2), or a direct message to the receiver's own node on any channel. Never a text on a public channel unless the person turns that on: at an event, dozens of radios chat on the public LongFast channel. |
| Store and show | As a normal `text` message (`{"text": {"text": "..."}}`) with sender, time and the radio facts (RSSI, SNR, hops, packet id). It has no id and is never a task. The gateway marks the record with `codec.version: 0`, the Android app with `source = "meshtastic"`, so a UI can show a small "via Meshtastic" hint. A received text may be up to 233 bytes and is kept as sent. |
| Duplicates | The same (sender node, Meshtastic packet id) within 24 hours is a repeat. Two texts with the same words but different packet ids are two messages. |
| Readable copies | A gateway that posts the readable line of a RescueCodec message on port 1 (`--text-over-air`, `also_plain`) MUST start it with `[RC] ` (bracket, R, C, bracket, space). Receivers MUST NOT show a port 1 text that starts with `[RC] `: they already have the message from port 256, which goes on the same channel. Stock apps show the copy as it is. A plain text is never copied again. |
| Send | A chat text goes out as a normal port 1 text or packed for RescueCodec on port 256, as the rule in section 2.3 says. A normal text carries only the words: no id, `pos` or `time`. It uses the same send queue, rank, priority (section 2 table), airtime budget and `want_ack` as a RescueCodec text. All other types always go out on port 256. |
| Size | At most 200 bytes of UTF-8 (Meshtastic allows 233). A longer text is cut to the longest prefix that ends on a whole character, trailing spaces go, and "…" is added (`fit_plain` in Python, `TextFit.fitPlain` in Kotlin). |

### 2.2 Several channels and direct messages

A radio can have up to 8 channels. Our base radio has four: the public LongFast, "MeshMeister", "draeger-srs" and "RescueCodec".

| Rule | Value |
|---|---|
| Name | A channel is known by its **name**. The index can differ from radio to radio. An empty primary name shows as the modem preset's name, for example "LongFast". |
| Public | A channel is public when it has no key or the 1-byte default key. Anyone can read it. |
| Listen | Receivers listen on port 256 and port 1 on **every private channel** (the gateway: `--channels all`, the default, or `--channels NAME,NAME`). Broadcasts on public channels are ignored unless the person turns them on (gateway `--show-public`). Direct messages to the receiver's own node are always kept, on any channel. |
| Record | Every stored message carries `channel: {index, name, public}` and `to`. `to` is the peer for a direct message we sent, our own node for one we received, and null for a broadcast. |
| Send | A message goes out on the default channel (the one named `RescueCodec`, section 14) unless the sender picks another one by name. Reports (port 256) never go out on a public channel. A direct message is always a text: packed on port 256 to that node, or a normal port 1 text (section 2.3). |
| Answer | An Ack always goes out on the **same channel** as the report it answers, so the reporting team hears it. |
| Duplicates | Unchanged. The same report heard on two channels is one report (same sender and id). |

Example record fields: `"channel": {"index": 2, "name": "draeger-srs", "public": false}, "to": null`.

Why a prefix and not content matching for copies: the copy and the original may arrive in any order and over different relays, and the readable line of the copy may be cut. A fixed prefix is checked on one packet, with no memory and no timing.

### 2.3 Packed or normal: the text rule

A chat text can go out two ways:
- **Packed for RescueCodec**: a `text` message on port 256 whose words are compressed (section 3.5), with id and `pos` like every RescueCodec message. About 40 % smaller. Stock Meshtastic apps cannot read it.
- **Normal Meshtastic text** on port 1: only the words. Every app and radio screen shows it.

Why not Meshtastic's own compressed text port (7, `TEXT_MESSAGE_COMPRESSED_APP`)? Stock firmware does not unpack it (the code is commented out in `src/mesh/Router.cpp`), so a stock app would show nothing. Port 256 is the honest choice: only RescueCodec receivers get packed texts, and the rule below sends them only to chats where everyone runs RescueCodec.

**Who runs RescueCodec.** Every app and gateway remembers, for 24 hours, what it heard:

| Heard | Means |
|---|---|
| Any valid RescueCodec packet (port 256) from a node, on any channel | The node runs RescueCodec. |
| A normal text (port 1) from a node on a channel, and no port 256 packet from it | The node is a **Meshtastic user** on that channel. |

A node not heard for 24 hours is forgotten. Readable copies (`[RC] ` texts) do not count. Our own node does not count. The app keeps this in its database (Room), the gateway in SQLite (`nodes_seen`), so it survives a restart.

**Mode per chat.** A chat is one channel or one direct chat with one node. Each has a mode: **Auto** (default), **Always compressed** or **Always normal**. The app shows it in the chat's menu; the gateway has `GET/PUT /v2/channels/{name}/mode`, `GET/PUT /v2/nodes/{node}/mode` and `--text-mode` for chats without their own mode.

**The rule**, in this order:
1. A public channel (section 2.2) always gets a normal text.
2. Always normal: normal text. Always compressed: packed.
3. Auto, direct chat: packed when the peer runs RescueCodec, else normal text.
4. Auto, channel: packed when at least one RescueCodec node and no Meshtastic user was heard on that channel in the last 24 hours. Otherwise normal text, also when nobody is known yet (reason `nobody_known`), so a new or foreign channel (for example a partner's) always gets text everyone can read.
5. Packed only when it saves at least **3 bytes**: the packed packet (with id, `pos`, `time`, priority) must be at least 3 bytes smaller than the normal text (the words, cut to 200 bytes like `fit_plain`). Else normal text. A GPS position costs 11 bytes, so short texts with a position stay normal.
6. **Compact text** (opt-in, off by default: app setting "Use compact encoding for text messages", gateway `--compact-text on`). Only when rules 1 to 4 say packed. Rule 5 is then replaced: the sender builds every complete payload, the normal text (port 1), the DEFLATE packet (3.5), the word tokens in the Envelope (3.6), the compact frame (3.7) and the domain frames of the team vocabulary setting (3.8.6; both frames only when the message has no `pos` and no `time`), and sends the **strictly smallest**. The normal text when it is smaller or equal. Ties between packed forms: compact frame, then words, then DEFLATE, then domain frames by lowest vocabulary id. Sizes are complete payloads (envelope, id, `pos`, dictionary fields included). Example: "hello world" is 11 bytes as a normal text and 6 bytes as a compact frame, so it goes compact; "Apply a tourniquet." is 19 bytes as a normal text, 22 as a compact frame and 7 as a Medical domain frame, so it goes as a domain frame; "Ok" is 2 bytes as a normal text and stays normal.
7. **Length.** A chat that packs (rules 2 to 4 say packed) takes up to **400 bytes** of typed text (`MAX_PACKED_TEXT_BYTES`); any other chat 180 bytes. The packet stays at most 200 bytes. A text that no form keeps whole in 200 bytes is cut (section 3.6.6) and the composer says "Too long: will be cut to fit one radio message". There is no multipart message.

Examples: "Ok" is 2 bytes as a normal text and 12 packed: normal. "We are at the hut, two people with us, waiting for the vehicle on the forest road" is 81 bytes as a normal text and 42 packed with an id (53 with a GPS position): packed on a RescueCodec channel. As soon as someone with the official app writes on that channel, the next text goes normal, so that person can read it.

The old setting "Send plain text as normal Meshtastic text" (app) and `--text-as-meshtastic` (gateway) are replaced by the mode. Migration: if the setting was off, channels start as Always compressed.

Libraries: `rescuecodec.engine.textmode` (`wants_packed`, `choose_form(..., compact, vocab)`, `fit_packed`) in Python and `TextMode` / `TextFit.fitPacked` in the Kotlin engine give the same answers.

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

**text**: 1 to 180 bytes of UTF-8 for anything the codes cannot say. Or the same words packed (section 3.5), or as compact word tokens (section 3.6).

### 3.5 Packed text

`Text` has five fields. Exactly one of `text`, `deflate` and `words` (section 3.6) is set.

| # | Field | Meaning |
|---|---|---|
| 1 | `text` | The words, 1 to 180 bytes of UTF-8. |
| 2 | `deflate` | The words packed: raw DEFLATE (RFC 1951, no zlib header or checksum) of the UTF-8 text, using the text dictionary as preset dictionary. Unpacked 1 to 1024 bytes of UTF-8. |
| 3 | `dict` | Number of the text dictionary, required with `deflate`. `1` = `vocab/text-dict-v1.txt`. |

Packing: zlib with window bits -15 (raw), level 9, memLevel 8, default strategy, and the dictionary set before the first byte: `zlib.compressobj(9, zlib.DEFLATED, -15, 8, zlib.Z_DEFAULT_STRATEGY, zdict=...)` in Python, `Deflater(9, true)` plus `setDictionary` in Java and Kotlin, `pako.deflateRaw(..., {level: 9, dictionary})` in TypeScript. Any valid DEFLATE stream unpacks to the same words everywhere, so only the dictionary bytes must be identical. Two compressors may make different bytes for the same words; both are valid.

Unpacking rules (decoders MUST follow them):
- Stop after 1024 bytes of output. More is `too_large` (field `text.deflate`): a packet of 233 bytes could otherwise unpack to about 240 KB.
- A broken stream, a stream that is cut off, bytes after the end of the stream, or output that is not UTF-8 is `malformed` (`text.deflate`).
- Empty output is `missing_field` (`text.text`). Both `text` and `deflate` set is `invalid_value` (`text.deflate`). `deflate` without `dict` is `missing_field` (`text.dict`); `dict` without `deflate` is `invalid_value` (`text.dict`).
- A `dict` number the decoder does not know (2 and up, from a newer spec) is not an error: the message is shown as "message of a newer type" (body `unknown`), like an unknown body.

**The dictionary** `vocab/text-dict-v1.txt` is 15835 bytes of UTF-8 text, SHA-256 `f1cc69167232438d0440ec8f2be9fa4270c4dd04c502dae13aa2d91bd31c6ec9`. It is frozen: never change a byte. A better one becomes dictionary 2. It is built by `tools/text_dict.py` from three sources, in this order, because DEFLATE codes near matches more cheaply, so the most useful words come last:
1. the German and English words of `vocab/sar.yaml` (typos, ASCII umlaut spellings and short single words left out),
2. the German and English strings of `i18n/de.json` and `en.json`,
3. `tools/text-dict-words.txt`: places, kit, weather, radio talk, times, directions, search status, common chat phrases, and the most common German and English sentence parts last.
A phrase that also comes later is left out. `.gitattributes` keeps the file byte for byte (no line ending changes). Libraries check the SHA-256 when they load it.

**Measured** (`tools/text_dict.py measure`, messages in `tools/text-corpus.json`). Bytes of the words only:

| Messages | UTF-8 | DEFLATE, no dictionary | DEFLATE with dictionary | Saved |
|---|---|---|---|---|
| 20 German, held out (not used for tuning) | 1183 B | 1180 B | 661 B | **44 %** |
| 20 English, held out | 1087 B | 1038 B | 618 B | **43 %** |
| 36 German, used for tuning | 1814 B | 1828 B | 770 B | 58 % |
| 36 English, used for tuning | 1721 B | 1681 B | 758 B | 56 % |

Without a dictionary DEFLATE saves nothing on texts this short. With the 3-byte rule (section 2.3), all 40 held-out messages go packed when the packet has only an id; with a GPS position 31 of 40 do (18 German, 13 English). Example: "We are at the hut, two people with us, waiting for the vehicle on the forest road" is 81 bytes, 34 bytes packed, 42 bytes as a whole packet with an id.

### 3.6 Compact text (word tokens)

Added in 2.1. A third form of `Text`: the words as **2-byte dictionary tokens**. Every word of the shared word dictionary costs 2 bytes, a single space between two such words costs nothing, and anything the dictionary cannot say (names, numbers, coordinates, emoji, other languages) goes as an exact **literal** of UTF-8 bytes. The receiver gets the text back **byte for byte**: `decode(encode(text)) == text` for every text. Nothing is ever replaced by a similar word.

#### 3.6.1 Fields

| # | Field | Meaning |
|---|---|---|
| 4 | `words` | The token stream (3.6.2). Decodes to 1 to 1024 bytes of UTF-8. |
| 5 | `words_dict` | Number of the word dictionary, required with `words`. `1` = `vocab/words-dict-v1.txt`, `2` = `vocab/words-dict-v2.txt` (2.2, used for new texts). |

Exactly one of `text`, `deflate` and `words` is set. The **mode** is told by the field that is set (field 4 = word tokens); the **dictionary version** by `words_dict`. The token codes and controls below hold for word dictionaries 1 and 2: a new token rule or a new control needs a new `words_dict` number. The two fields cost 4 bytes plus the stream, the same as `deflate` with `dict`.

#### 3.6.2 Token stream

A sequence of 16-bit **big-endian** units, read one after another:

| Unit | Meaning |
|---|---|
| `0x0000`..`0xEFFF` | Dictionary entry: line number minus 1 of the dictionary file. A number at or past the end of the dictionary is `malformed`. |
| `0xF001` CAP | The next entry is written with its first character `a`..`z` changed to `A`..`Z`. |
| `0xF002` UPPER | The next entry is written with every `a`..`z` changed to `A`..`Z`. |
| `0xF003` GLUE | No space before the next entry (where rule 3.6.3 would put one). |
| `0xF800 + N` | **Literal**: the next **N bytes** (N = 1..2047, counted in UTF-8 bytes, not characters) are UTF-8 and are copied exactly. Each literal is valid UTF-8 on its own. |
| `0xF000`, `0xF004`..`0xF7FF`, `0xF800` | Reserved. `malformed`. |

The literal length lives in the unit itself, so a literal costs 2 bytes plus its bytes, whatever its length: there is no length switch at 127 or 255 (vectors `stream-literal-127` to `-256`). A text is at most 1024 bytes, so a literal is never split. Case changes are ASCII only, never locale rules (no "ß" to "SS").

#### 3.6.3 Spaces, punctuation and capitals

The decoder writes the units in order and puts **one space** before a dictionary entry only when all of these hold:
- the unit before it (controls not counted) is a dictionary entry, not a literal and not the start,
- no GLUE was given for it,
- it does not start with one of `.` `,` `;` `:` `!` `?` `)` `]` `}` `…` (they attach to the left),
- the entry before it does not end with `(` `[` `{` (they attach to the right).

Never next to a literal: **a literal carries its own spaces**, before and after. Examples (token numbers from dictionary 1: `We` = 80, `found` = 248, `is` = 24, `safe` = 862, `meet` = 892, `at` = 61, `50` = 2092):

| Text | Stream | Bytes |
|---|---|---|
| `We found Aylin Demir` | `[We] [found] [literal 12 " Aylin Demir"]` = `004f 00f7 f80c 2041796c696e2044656d6972` | 18 instead of 20 |
| `Aylin Demir is safe` | `[literal 12 "Aylin Demir "] [is] [safe]` = `f80c 41796c696e2044656d697220 0017 035d` | 18 instead of 19 |
| `Meet at 50.6900, -1.2500` | `CAP [meet] [at] [50] [literal 14 ".6900, -1.2500"]` = `f001 037b 003c 082b f80e 2e36393030...` | 24 instead of 24 |
| `is safe.` | `[is] [safe] [.]` (`.` attaches left) | 6 instead of 8 |

So a name owns the space in front of it (" Aylin Demir"), and a word after a literal gets no space of its own (the space travels at the end of the literal: "Aylin Demir "). Without that trailing space the same tokens decode to "Aylin Demiris safe": the decoder never invents a space. Line breaks, tabs, two spaces in a row, a space before punctuation: all go into literals, so they come back exactly.

#### 3.6.4 Encoder (identical bytes in every library)

1. **Pieces.** Split the text into `gap0 piece1 gap1 piece2 ... pieceN gapN`. A gap is a run of space, tab, CR and LF (maybe empty). A piece is one of `. , ; : ! ? ( ) [ ] { } " …`, or a run of any other characters. Other Unicode spaces (for example U+00A0) are piece characters.
2. **Matches.** For pieces i..j (at most as many pieces as the longest entry has), the span text is `piece_i gap_i ... piece_j`. It matches: the entry equal to it (2 bytes); else, if it starts with `A`..`Z`, CAP + the entry equal to it with that letter lowered (4 bytes); else, if lowering every `A`..`Z` changes it and upper-casing the result gives it back, UPPER + that entry (4 bytes). Nothing else matches.
3. **Shortest stream.** Dynamic programming over positions 0..N with two states, TOK (the last unit is an entry) and LIT (a literal is open). From each position k (k ascending), for each state (TOK, or START at k = 0, before LIT), first every match starting at piece k+1 (shorter spans first), then the literal step:
   - entry after TOK with an implied space (3.6.3) and gap `" "`: entry cost; gap `""`: GLUE + entry; any other gap: new literal with the gap + entry;
   - entry otherwise: gap `""`: entry cost; else the gap goes into the open literal (state LIT) or a new one (2 + bytes) + entry;
   - literal step: gap + piece into the open literal, or a new one (2 bytes more).
   A step replaces the best way to a (position, state) only when it is **strictly shorter**. At the end the trailing gap goes into the open literal or a new one; on a tie the TOK ending wins.
4. **Write.** Follow the chosen steps; a literal is written when an entry follows it or at the end. Controls in this order: GLUE, then CAP or UPPER, then the entry.

`rescuecodec/v2/textwords.py` is the reference (`encode_words`, `decode_words`). Kotlin `TextWords` and TypeScript `textwords.ts` give the same bytes on every vector and on thousands of random texts.

#### 3.6.5 Decoder and errors

- A stream of 0 bytes, half a unit at the end, a literal cut off, a literal of 0 bytes, a reserved code, a token past the dictionary's end, a control at the end or before a literal, two case controls or two GLUEs in a row, or a literal that is not UTF-8: `malformed` (field `text.words`).
- More than 1024 bytes of output: `too_large` (`text.words`); stop decoding there.
- `words` without `words_dict`: `missing_field` (`text.words_dict`). `words_dict` without `words`: `invalid_value` (`text.words_dict`). `words` with `text` or `deflate`: `invalid_value` (`text.words`). `dict` with `words`: `invalid_value` (`text.dict`).
- JSON with both `dict` and `words`, or a `words` number the encoder does not have: `invalid_value` (`text.words`).
- **Dictionary mismatch is visible.** A `words_dict` this library does not have, **or whose bundled copy does not match the SHA-256 in 3.6.8**, is not an error and never shown as words: the body is `unknown` with `{"words": n}`, and the readable line is the head `unknown_dict`: "Message encoded with a dictionary this app does not have (word dictionary n), please update the app". Libraries check the SHA-256 when they load the file, so a damaged copy cannot produce plausible wrong text. A fingerprint on the air is not needed for that and would cost 2 to 4 bytes per message.

#### 3.6.6 Long texts: the cut

A chat that packs takes up to 400 bytes of typed text. `choose_form` first checks whether one form keeps the whole text in one packet (the normal text up to 200 bytes, the DEFLATE packet or, with compact on, the word packet, each at most 200 bytes). If none does, or the text is over 400 bytes, it is cut (`fit_packed` in Python, `TextFit.fitPacked` in Kotlin): the **longest prefix that ends on a whole UTF-8 character**, trailing whitespace removed, plus "…" (U+2026), that is at most 400 bytes and that one form keeps in 200 bytes. Then the forms are chosen as usual. Example: 380 bytes of random letters do not compress; they are cut to about 190 bytes plus "…".

#### 3.6.7 Older readers

A reader before 2.1 does not know fields 4 and 5 (a reader before 2.2 does not have word dictionary 2: it shows "dictionary this app does not have", 3.6.5). It sees a `Text` with an empty `text` and rejects the packet (`missing_field`, `text.text`): the 2.0 app shows "Unreadable message", the 2.0 gateway counts it as rejected. It **never shows wrong words**. The sender cannot see the reader's version, so compact text is off by default. A team with older apps keeps it off or sets the chat to Always normal.

GPS from the report flow stays in `Envelope.pos` (11 bytes), never in the words, so a compact text with a position is still on the map. Coordinates typed into the text are just text: every character comes back (`words-coordinates-prose`).

#### 3.6.8 The word dictionary

`vocab/words-dict-v1.txt`: 8192 entries, one per line, UTF-8, LF, ending with LF, 66607 bytes, SHA-256 `ffe93afb4623fcd364479169281ca745cc263317a4f83ce27f77d8d37b26a0d8`. Entry = line number minus 1. Entries are case-sensitive, have no tabs or line breaks and no leading or trailing space; 738 are two-word phrases ("do you"). It is frozen: a better one becomes word dictionary 2. `tools/words_dict.py` builds it reproducibly from two openly licensed corpora (English disaster-response messages, CC BY 4.0, and Tatoeba English sentences, CC BY 2.0 FR) plus the words of `vocab/sar.yaml`; sources, licences, privacy filter, selection and measurements are in `docs/COMPACT_TEXT.md`. The app and the gateway bundle the same file (the Android build copies it from `spec/vocab`, TypeScript has a generated copy checked by SHA-256 in its tests). In the APK it takes 31.6 KB (zip compressed).

**Measured** on held-out messages (`tools/words_dict.py measure`, complete payloads with an id, chat that packs, compact on):

| Held-out set | Messages | Normal text | Sent (best form) | Saved | Median per message | Sent as words / DEFLATE / normal |
|---|---|---|---|---|---|---|
| Conversational (Tatoeba) | 4998 | 199681 B | 137669 B | 31.1 % | 28.6 % | 83.3 % / 3.4 % / 13.2 % |
| Disaster messages | 2357 | 317307 B | 176659 B | 44.3 % | 43.6 % | 92.1 % / 6.3 % / 1.6 % |

Without compact text (DEFLATE or normal, the 2.0 rule) the same sets save 9.7 % and 25.4 %.

**Word dictionary 2** (2.2): `vocab/words-dict-v2.txt`, **61440 entries** (every code `0x0000`..`0xEFFF`), same file rules, 623992 bytes, SHA-256 `c88c6b6bd08821b150a248c41f10b99c71ee5f34ab40d6840685738eb8f48dee`. 22545 single words and punctuation pieces (capitalised forms such as "The", "We", "Please" are entries of their own) and 38895 two-word phrases. Built reproducibly by `tools/words_dict2.py` from the same two corpora and the same train / validation / test split as dictionary 1; SCOWL word lists (permissive licence) filter rare entries, so a word seen in fewer than 10 training messages must be an ordinary English word (no rare names, no typos). Sources and licences: `docs/COMPACT_TEXT.md`. In the APK it takes about 277 KB (zip compressed). Frozen like dictionary 1; dictionary 1 stays readable forever. New texts use dictionary 2.

### 3.7 Compact frame (no Envelope)

Added in 2.2. The shortest form of a chat text between RescueCodec apps: the word stream of 3.6 **without the Envelope**.

```
byte 0     0x02            marker (a reserved first byte in 2.0 and 2.1, section 7)
byte 1     1..255          word dictionary version (2 for new texts; 1 is allowed)
byte 2..   word stream     exactly as 3.6.2 to 3.6.4: tokens, literals, CAP / UPPER / GLUE, spacing rules
```

"hello world" = `02 02 033e 0124` = **6 bytes** (11 as a normal text, 13 as words in the Envelope with a 2-byte id). Every word of dictionary 2 costs 2 bytes, a single space between two words costs nothing, and the frame adds 2 bytes. Meshtastic adds its own header (about 16 bytes plus the `Data` wrapper) to every packet on air, as for any payload.

| Rule | Value |
|---|---|
| What it carries | Only a chat text. No id, no `pos`, no `time`, no priority. A text with a position or time goes in the Envelope (3.6). |
| Duplicates and delivery | Like a normal port 1 text (2.1): the same (sender node, Meshtastic packet id) within 24 hours is a repeat; delivery status comes from the Meshtastic packet. |
| Priority | Not on the air; the sender's queue and `MeshPacket.priority` use the text's priority (normal by default), like a normal text. |
| Size | Encoders: at most 200 bytes. Decoders accept up to 233. An empty text has no frame (`missing_field`). |
| Canonical JSON | `{"text": {"text": "hello world", "words": 2}}` (no `id`). `decode_any` adds `"v": 2`. |
| Unknown dictionary | Version byte this library does not have, or whose copy fails the SHA-256 check: `{"unknown": {"words": n}}`, shown as "Message encoded with a dictionary this app does not have (word dictionary n), please update the app". Never guessed words. |
| Errors | `malformed`: only the marker, no stream, version 0, or any stream error of 3.6.5 (half a token, a literal cut off, a reserved code, a token past the end, a control at the end). `too_large`: over 233 bytes, or a stream that decodes to over 1024 bytes. |
| Cut packets | A cut inside a token or a literal is `malformed`. The frame has no length field: a cut exactly between two units would read as a shorter text. The radio never delivers a cut packet (LoRa CRC and Meshtastic's packet length), the same as for normal port 1 texts, which have no length either. |
| Older readers | Apps and gateways before 2.2 treat `0x02` as a reserved version: `unsupported_version`. The app shows an unreadable-message line, the gateway counts the packet as rejected. **Never wrong words.** If someone in the chat has an older app, leave compact text off or set the chat to Always normal. |

Libraries: `encode_compact` / `decode_compact` / `decode_any` (Python), `RescueCodec.encodeCompact` / `decodeCompact` / `decodeAny` (Kotlin), `encodeCompact` / `decodeCompact` / `decodeAny` (TypeScript). Vectors: `compact_frames` and `compact_frames_invalid` in `vectors/codec.json` (hello world, a name with coordinates, a poem with line breaks, emoji, a 129-byte literal, a 385-byte text, exactly 200 bytes, dictionary 1, an unknown dictionary, empty, truncated, version 0, too large).

**Measured** on the held-out sets of 3.6.8 (`tools/words_dict2.py measure`, chat that packs, compact on, no position):

| | Conversational (Tatoeba test) | Disaster (test split) |
|---|---|---|
| Messages (up to 400 B typed) | 4998 | 2357 |
| Words found in the dictionary: v1 / v2 | 93.9 % / 98.5 % | 89.6 % / 93.8 % |
| Bytes per word: typed / words v1 in the Envelope / compact frame v2 | 5.21 / 3.67 / **2.04** | 6.14 / 3.42 / **2.40** |
| Sent, 2.1 rule (DEFLATE, words v1, plain) | 137669 B, 31.1 % saved | 176659 B, 44.3 % saved |
| Sent, 2.2 rule (strictly smallest) | 78139 B, **60.9 % saved** | 123914 B, **60.9 % saved** |
| Median saving per message: 2.1 / 2.2 | 28.6 % / 61.5 % | 43.6 % / 62.2 % |
| Sent compact, not plain: 2.1 / 2.2 | 86.8 % / 99.7 % | 98.4 % / 99.5 % |
| Fits one packet (200 B): typed / 2.1 / 2.2 | 99.5 % / 99.9 % / 100 % | 82.6 % / 99.5 % / 100 % |

The conversational test sentences come from the same collection as the training sentences (split by hash), so real chat with slang and typos will find fewer words than 98.5 %; the disaster set (real messages, many names and typos) is the harder case.

### 3.8 Domain frame (team vocabularies)

Added in 2.3. A chat text in a team's own words. Each team vocabulary holds the 1016 most useful words and short phrases of one domain: Emergency, Search & Rescue, Firefighting, Hiking / Mountain, Medical. A word of the vocabulary costs **10 bits** instead of 16. Any other word of word dictionary 2 costs 26 bits (an escape plus its 16-bit token). Names, numbers, coordinates, emoji and other languages go as exact **literals**. The receiver gets the text back **byte for byte**: `decode(encode(text)) == text` for every text. Nothing is ever replaced by a similar word.

"Apply a tourniquet." = `03 05 e3 00 60 9c 00` = **7 bytes** (19 as a normal text, 22 as a compact frame 0x02).

#### 3.8.1 Frame

```
byte 0     0x03            marker (a reserved first byte before 2.3, section 7)
byte 1     1..255          vocabulary id: domain and version; the code width N follows from the id (3.8.7)
byte 2..   bit stream      N-bit codes, most significant bit first, the last byte filled up with 0 bits
```

| Rule | Value |
|---|---|
| What it carries | Only a chat text, like the compact frame (3.7). No id, no `pos`, no `time`, no priority. A text with a position or time goes in the Envelope (3.6). |
| Port, duplicates, delivery, priority | As the compact frame (3.7): port 256; the same (sender node, Meshtastic packet id) within 24 hours is a repeat; the priority is only the radio's `MeshPacket.priority`. |
| Size | Encoders: at most 200 bytes. Decoders accept up to 233. An empty text has no frame (`missing_field`). |
| Canonical JSON | `{"text": {"text": "Apply a tourniquet.", "vocab": 5}}` (no `id`). `decode_any` adds `"v": 2`. `vocab` is the id on the air. `encode` rejects `vocab` (`invalid_value`, `text.vocab`): there is no Envelope form; `encode_domain(text, vocab)` builds the frame. |
| Libraries | `encode_domain` / `decode_domain` / `decode_any` (Python, `rescuecodec/v2/codec.py`; the stream in `rescuecodec/v2/textdomain.py`), the same names in Kotlin and TypeScript. |

#### 3.8.2 Codes

Every code has N bits. The top 8 codes of the code space are controls; `base` = 2^N − 8 (1016 for N = 10).

| Code | N = 10 | Name | Meaning |
|---|---|---|---|
| 0 .. base − 1 | 0 .. 1015 | entry | Vocabulary entry: line number minus 1 of the vocabulary file. |
| base + 0 | 1016 | ESC16 | The next **16 bits** are a token of word dictionary 2 (`0x0000`..`0xEFFF`, 3.6.8). It is written like an entry. `0xF000` and above: `malformed`. |
| base + 1 | 1017 | CAP | The next entry (or escaped token) with its first character `a`..`z` changed to `A`..`Z`. |
| base + 2 | 1018 | UPPER | The next entry (or escaped token) with every `a`..`z` changed to `A`..`Z`. |
| base + 3 | 1019 | GLUE | No space before the next entry (where 3.6.3 would put one). |
| base + 4 | 1020 | LIT | The next **8 bits** are a length L = 1..255, then L bytes of UTF-8, copied exactly. Each literal is valid UTF-8 on its own. L = 0: `malformed`. |
| base + 5 .. base + 7 | 1021 .. 1023 | reserved | `malformed`. |

Costs: an entry N bits, an escaped token N + 16, CAP, UPPER and GLUE N each, a literal N + 8 bits plus 8 per byte.

**End of the stream.** The decoder reads codes while at least N bits are left. The bits left after that are padding: they MUST be fewer than 8 and all 0. So there is no length field and no end code. Case changes are ASCII only, as in 3.6.

#### 3.8.3 Spaces, punctuation and capitals

Exactly as 3.6.3. An entry and an escaped token are both "entries". The decoder puts one space before an entry only when the unit before it is an entry, no GLUE was given, the entry does not start with `.` `,` `;` `:` `!` `?` `)` `]` `}` `…`, and the entry before does not end with `(` `[` `{`. **A literal carries its own spaces**: "Patient Aylin Demir is conscious." is `CAP [patient] [LIT " Aylin Demir "] [is] [conscious] [.]`.

#### 3.8.4 Encoder (identical bytes in every library)

The shortest stream, found exactly as in 3.6.4 (same pieces, same two states, same order, a step wins only when strictly shorter, the TOK ending wins a tie at the end), with three changes:
1. **Matches.** A span first matches the vocabulary (the equal entry, else CAP + entry, else UPPER + entry, as in 3.6.4 step 2). Only when the vocabulary has no match, word dictionary 2 is tried the same way, as ESC16 + token. The vocabulary always wins because it is cheaper: at most 2N = 20 bits against at least N + 16 = 26.
2. **Costs in bits** (3.8.2): a new literal costs N + 8 + 8 per byte; adding to the open literal 8 per byte; GLUE N.
3. **Write.** Controls in this order: GLUE, then CAP or UPPER, then ESC16 + token or the entry. A literal run is written when an entry follows or at the end. A run longer than 255 bytes is written as several LIT codes, each the longest piece of at most 255 bytes that ends on a whole character (`Q` × 254 + `é` = LIT 254, LIT 2). The search counts one LIT per run; such runs only occur in streams too long for one packet. The last byte is filled up with 0 bits.

`rescuecodec/v2/textdomain.py` is the reference (`encode_stream`, `decode_stream`). Its sizes match the independent bit model of `tools/domain_vocab.py` on 7265 random texts.

#### 3.8.5 Decoder and errors

- `malformed` (field `text.vocab`): only the marker (no vocabulary id), no codes, a reserved code, ESC16 cut off or a token `0xF000` or above, a LIT length cut off, a literal of length 0, a literal cut off, a literal that is not UTF-8, two case controls or two GLUEs before one entry, a control before a literal or at the end, 8 or more bits left at the end, or a 1 bit in the padding.
- `too_large`: a frame over 233 bytes, or a stream that decodes to more than 1024 bytes (stop decoding there).
- **Unknown vocabulary is visible.** An id this library does not have (0 and 6..255 in 2.3), **or whose bundled file does not match its SHA-256** (3.8.7), or a damaged word dictionary 2, is not an error and never shown as words: the body is `unknown` with `{"vocab": n}`, and the readable line is the head `unknown_vocab`: "Message encoded with a team vocabulary this app does not have (unsupported vocabulary n), please update the app".
- **Cut packets**, as in 3.7: a cut inside an escape or a literal is `malformed`. A cut between two codes whose leftover bits are 0 reads as a shorter text. The radio never delivers a cut packet (LoRa CRC and Meshtastic's packet length).

#### 3.8.6 Which form goes out: the team vocabulary setting

With compact text on (2.3, rule 6) the sender also builds the domain frames and sends the **strictly smallest** complete payload; the normal text when it is smaller or equal. Like the compact frame, no domain frame for a message with `pos` or `time`.

| Setting | App "Team vocabulary" | Gateway `--vocabulary` | Domain frames built |
|---|---|---|---|
| Auto (default) | Auto | `auto` | every vocabulary |
| General | General | `general` | none (only the 0x02 frame and the Envelope forms) |
| Pinned | Search & Rescue, Firefighting, Hiking, Medical, Emergency | `sar`, `fire`, `hiking`, `medical`, `emergency` | only that one |

The normal text, DEFLATE, the words in the Envelope and the compact frame are always built, so a pinned vocabulary never makes a message larger. Ties between packed forms: compact frame, then words, then DEFLATE, then the domain frame with the lowest vocabulary id. Every app and the gateway bundle **every** vocabulary, so receiving works whatever the setting is.

Examples (complete payloads, a message with id 3, `vectors/domain.json` → `choice`):

| Text | Setting | Normal | 0x02 frame | Best domain frame | Goes out |
|---|---|---|---|---|---|
| Patient is conscious, bleeding from the leg, apply a tourniquet. | Auto | 64 B | 38 B | Medical 19 B | **domain, Medical, 19 B** |
| SAR team 2 at the trailhead | Auto | 27 B | 26 B | Hiking 18 B (S&R 20 B) | **domain, Hiking, 18 B** |
| SAR team 2 at the trailhead | Search & Rescue | 27 B | 26 B | S&R 20 B | **domain, S&R, 20 B** |
| Fire is spreading up the ridge, need two more engines and water. | Auto | 64 B | 24 B | Fire 25 B | **0x02 frame, 24 B** |
| Patient is conscious, bleeding from the leg, apply a tourniquet. | Search & Rescue | 64 B | 38 B | S&R 38 B | **0x02 frame, 38 B** (tie) |
| Ok | Auto | 2 B | 4 B | 6 B | **normal text, 2 B** |
| (fire) | Auto | 6 B | 8 B | Fire 6 B | **normal text, 6 B** (tie) |

How to choose the setting in the app and the gateway, and what it saves: `docs/COMPACT_TEXT.md`, section "Domain vocabularies".

**Labels.** UIs show the vocabulary of a received or sent domain frame, for example "compact · S&R · 9 B". Gateway records have `codec.form: "domain"`, `codec.vocab: "sar"`, `codec.vocab_id: 2` and `codec.vocab_label: "S&R"`; `GET /v2/health` shows `vocabulary` and `vocabularies`.

#### 3.8.7 The vocabularies

| Id | Domain | Label (short) | N | Entries | File | SHA-256 |
|---|---|---|---|---|---|---|
| 1 | `emergency` | Emergency (Emergency) | 10 | 1016 | `vocab/domain-emergency-v1.txt` | `e98e8c9026b9f75b29a681dfaee2a49ebac39fbbea4d1471e60949ac71164664` |
| 2 | `sar` | Search & Rescue (S&R) | 10 | 1016 | `vocab/domain-sar-v1.txt` | `444798678a105827cf783b990c0ceda05b4cf927b4846c15e0345213f60092ee` |
| 3 | `fire` | Firefighting (Fire) | 10 | 1016 | `vocab/domain-fire-v1.txt` | `d37f489ed55a944d59fcc3b53c985da2facf3aefa81cf9053240d80cf62dc9a2` |
| 4 | `hiking` | Hiking / Mountain (Hiking) | 10 | 1016 | `vocab/domain-hiking-v1.txt` | `3e03a004a0696afc41738d71280e13125390eff85f3ab073d6b9c7b510e6464a` |
| 5 | `medical` | Medical / First aid (Medical) | 10 | 1016 | `vocab/domain-medical-v1.txt` | `21a76e1f01d3aac7291875f6b863244fc873d724cdae4afddd7a3cfcade6be3b` |

Ids 0 and 6..255 are not assigned. The same table, with sources, licences and measurements, is `vocab/domains.yaml`. Files: one entry per line, printable ASCII, LF, ending with LF, no leading or trailing space, no repeats; entry = line number minus 1; 2^N − 8 entries. They are **frozen**: a better vocabulary gets a new id (the code width comes with the id, so a later one may use 8 or 9 bits). Libraries check the SHA-256 when they load a file. `tools/domain_vocab.py` builds them reproducibly from openly licensed and public-domain text (US federal manuals and web pages, Disaster Response Messages CC BY 4.0, Tatoeba CC BY 2.0 FR); raw text is never in the repo, and a privacy filter drops rare entries, names and numbers.

**Why 10 bits.** 8, 9 and 10 bits were measured on held-out text (never used to build). 10 bits saved the most in every domain (mean of sent ÷ plain over the sources, lower is better):

| Domain | 8 bits | 9 bits | 10 bits |
|---|---|---|---|
| Emergency | 0.3779 | 0.3750 | **0.3694** |
| S&R | 0.3830 | 0.3829 | **0.3791** |
| Fire | 0.4161 | 0.4141 | **0.4090** |
| Hiking | 0.3867 | 0.3841 | **0.3791** |
| Medical | 0.3993 | 0.3930 | **0.3837** |

**Measured** (held-out units; "base" = the best of the normal text and the 0x02 frame; "sent" = the best of the normal text, the 0x02 frame and this domain frame):

| Domain | Units | Words in the vocabulary | Bytes per word: normal → base → sent | Saved against base |
|---|---|---|---|---|
| Emergency | 4737 | 51.7 % | 5.99 → 2.29 → 2.24 | 2.1 % |
| S&R | 1607 | 61.4 % | 5.61 → 2.21 → 2.14 | 3.0 % |
| Fire | 2287 | 60.7 % | 5.96 → 2.58 → 2.44 | 5.5 % |
| Hiking | 1848 | 61.2 % | 5.56 → 2.23 → 2.16 | 3.0 % |
| Medical | 3963 | 60.5 % | 5.99 → 2.52 → 2.33 | 7.5 % |

The domain frame helps most on domain language: 5 to 20 % against base on single manuals, 0 to 3 % on chat messages. An escaped word costs N + 16 = 26 bits, more than its 16 bits in the 0x02 frame, so the domain frame wins only when enough words are in the vocabulary. The send rule picks the smaller one, so a message never gets larger.

Through the full send rule (`tools/measure_domain.py`: the real sender code, settings Auto, General and pinned, 14,421 held-out domain texts and 6,228 general ones), Auto sends **4.9 %** fewer bytes than General over the five domain sets (2.37 → 2.26 bytes per word), 6 to 12 % on manual sentences, under 3 % on domain messages and under 1 % on everyday chat. One-packet share does not change. The five lists take 18 KB in the APK. Results, sources, licences and how to turn it on: `docs/COMPACT_TEXT.md`, section "Domain vocabularies".

#### 3.8.8 Byte by byte

One example per domain, plus an escape, a literal and GLUE (`vectors/domain.json` → `frames`; codes in decimal, `[x]` = entry, `ESC16 [x]` = escaped token of word dictionary 2):

| Vocabulary | Text | Codes | Frame | Bytes (normal / 0x02) |
|---|---|---|---|---|
| 1 Emergency | Evacuate now, the river is flooding. | CAP `[evacuate]`560 `[now]`477 `[,]`2 `[the]`1 `[river]`455 `[is]`11 `[flooding]`129 `[.]`0 | `0301fe63077402005c702c810000` | **14** (36 / 18) |
| 2 S&R | Missing person found, alive. | CAP `[missing]`31 `[person]`125 `[found]`57 `[,]`2 `[alive]`889 `[.]`0 | `0302fe41f1f43900b79000` | **11** (28 / 16) |
| 3 Fire | Smoke on the ridge, wildfire. | `[Smoke]`81 `[on the]`91 ESC16 `[ridge]`0x958B `[,]`2 `[wildfire]`22 `[.]`0 | `03031445bfe2562c02058000` | **12** (29 / 16) |
| 4 Hiking | Trail closed, avalanche danger. | `[Trail]`516 `[closed]`254 `[,]`2 `[avalanche]`11 `[danger]`360 `[.]`0 | `0304810fe0080b5a0000` | **10** (31 / 16) |
| 5 Medical | Apply a tourniquet. | `[Apply]`908 `[a]`6 `[tourniquet]`39 `[.]`0 | `0305e300609c00` | **7** (19 / 22) |
| 3 Fire | RIDGE closed. | UPPER ESC16 `[ridge]`0x958B `[closed]`363 `[.]`0 | `0303febf8958b5ac00` | **9** (13 / 10) |
| 2 S&R | Missing person found at 50.69212, 7.21345. | CAP `[missing]` `[person]` `[found]` `[at]`30 LIT 19 `" 50.69212, 7.21345."` | `0302fe41f1f43907bfc132035302e36393231322c20372e32313334352e0` (the literal bytes are not on byte boundaries) | **30** (42 / 32) |
| 3 Fire | "Fire" on the ridge | `["]`47 GLUE `[Fire]`54 GLUE `["]`47 `[on the]`91 ESC16 `[ridge]` | `03030bffb0dbfb0bc5bfe2562c` | **13** (19 / 15) |

Bit by bit, "Apply a tourniquet." in vocabulary 5: the codes 908, 6, 39, 0 are `1110001100 0000000110 0000100111 0000000000`, 40 bits, read 8 at a time: `11100011 00000000 01100000 10011100 00000000` = `e3 00 60 9c 00`. No padding. With the marker and the id: `03 05 e3 00 60 9c 00`.

"Smoke on the ridge, wildfire." in vocabulary 3: `0001010001` (81) `0001011011` (91) `1111111000` (ESC16) `1001010110001011` (0x958B) `0000000010` (2) `0000010110` (22) `0000000000` (0) = 76 bits, plus 4 padding bits `0000` = 10 bytes `14 45 bf e2 56 2c 02 05 80 00`.

#### 3.8.9 Older readers

Apps and gateways before 2.3 treat `0x03` as a reserved version: `unsupported_version`. The app shows an unreadable-message line, the gateway counts the packet as rejected. **Never wrong words.** Compact text is off by default. If someone in the chat has an older app, set Team vocabulary to General (the 0x02 frame needs 2.2), leave compact text off, or set the chat to Always normal.

Vectors: `vectors/domain.json` (`tools/gen_domain_vectors.py`): each vocabulary's SHA-256, 24 frames (each domain, escapes with CAP and UPPER, literals, GLUE, line breaks, emoji, a 190-byte literal), 5 long streams (literals of 254, 255, 256 and 600 bytes, a split before a 2-byte character), 40 seeded random texts, 21 invalid frames (cut escape, cut literal, length 0, reserved codes, bad padding, controls at the end, too large), 5 invalid encodes (empty text, unknown vocabulary, over 200 bytes), 3 unknown vocabularies (0, 6, 255) and 10 send-rule choices (domain wins, the 0x02 frame wins, plain wins, pinned, general, ties).

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
- `text` is always the unpacked words. A compact text (section 3.6) adds `"words": 1` (encoders build word tokens when `words` is given); an unknown word dictionary decodes as `{"unknown": {"words": 2}}`. A domain frame (section 3.8) decodes as `{"text": {"text": "...", "vocab": 5}}`, an unknown team vocabulary as `{"unknown": {"vocab": 9}}`. A packed text (section 3.5) adds `"dict": 1`: `{"text": {"text": "Bin unterwegs", "dict": 1}}`. Encoders pack when `dict` is given. Example of a decoded packed text: `{"id": 3, "text": {"text": "We are at the hut, ...", "dict": 1}}`.
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
| `too_large` | Encoder output over 200 bytes, decoder input over 233 bytes, or a packed text that unpacks to more than 1024 bytes (`text.deflate`). |
| `unsupported_version` | First byte 0x00 or 0x04..0x07 (reserved), 0x02 or 0x03 given to an Envelope decoder (`decode`; `decode_any` reads them, sections 3.7 and 3.8), or 0x01 given to a v2-only decoder. |
| `malformed` | Not valid protobuf (cut-off varint, length past the end, invalid UTF-8 in a string), a packed text that is not a complete DEFLATE stream of UTF-8 (`text.deflate`), or a broken word-token stream (`text.words`, section 3.6.5), or a broken domain frame (`text.vocab`, section 3.8.5). |
| `no_body` | No body field at all, and no unknown field that could be a newer body type. |
| `multiple_bodies` | JSON with more than one body key. (On the wire protobuf keeps the last one.) |
| `unknown_key` | JSON key that the spec does not define. |
| `missing_field` | A required value is 0 or missing: id for found/request/hazard/mayday, `sector.sector`, `sector.status`, `request.resource`, `hazard.kind`, `ack.ref`, `ack.node`, `ack.status`, `text.text`, `text.dict` with `deflate`, track points, `time` for a track with times. |
| `invalid_value` | Out of range or wrong type: id over 16383, lat outside -90..90, lon outside -180..180, time over 1439, counts over 255, coverage over 100, radius over 100000, eta over 1440, team over 12 bytes, text over 180 bytes (packed: over 1024), more than 16 track points, track lists of different length, unknown names in JSON, an unknown `text.dict` in JSON, `text` and `deflate` both set, `dict` without `deflate`. |

Forward compatibility comes first: unknown enum numbers, unknown flag bits, reserved state bits and unknown fields are **not** errors.

## 6. Limits

| Limit | Value |
|---|---|
| Packet, encoder | 200 bytes |
| Packet, decoder | 233 bytes |
| id, ack.ref | 1..16383 |
| Track points | 1..16 per packet |
| Text | 1..180 bytes UTF-8 |
| Packed text | 1..1024 bytes UTF-8 after unpacking, packet still at most 200 bytes |
| Compact text (word tokens) | 1..1024 bytes UTF-8 after decoding, packet still at most 200 bytes |
| Domain frame | 1..1024 bytes UTF-8 after decoding, frame at most 200 bytes (decoders: 233), a literal 1..255 bytes |
| Team vocabulary | 2^N − 8 entries, N = 8, 9 or 10 (every version 1 vocabulary: N = 10, 1016 entries) |
| Typed text in a chat that packs | 1..400 bytes UTF-8 (`MAX_PACKED_TEXT_BYTES`), cut to fit one packet (3.6.6) |
| Word dictionary | at most 61440 entries (v1: 8192) |
| Text dictionary | under 16 KB |
| Team label | 0..12 bytes UTF-8 |
| count, responders, persons | 0..255 |

## 7. Version detection and v1 compatibility

The first byte tells the version:

| First byte | Meaning |
|---|---|
| `0x01` | v1 packet (the team's first prototype). Decode with the v1 rules. |
| `0x02` | Compact frame (2.2, section 3.7): a chat text as word tokens without an Envelope. Readers before 2.2 reject it with `unsupported_version`. |
| `0x03` | Domain frame (2.3, section 3.8): a chat text as N-bit codes of a team vocabulary without an Envelope. Readers before 2.3 reject it with `unsupported_version`. |
| `0x00`, `0x04` to `0x07` | Reserved for future incompatible versions. Reject with `unsupported_version`. |
| `0x08` to `0xFF` | v2 protobuf `Envelope`. |

Why this works: a protobuf field starts with a tag byte `(field number << 3) | wire type`. Field numbers start at 1, so the first byte of any protobuf message is at least `0x08`. Bytes `0x00` to `0x07` would mean field 0, which protobuf forbids. So a v2 packet can never be mistaken for v1, a compact frame or a domain frame, and we keep five markers for later versions. Tests: `tests/test_v2_compat.py`.

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
- **Major version (v3)** only if something cannot be added. It will start with one of the reserved first bytes `0x04` to `0x07` (`0x02` is the compact frame, 3.7, `0x03` the domain frame, 3.8).
- **A new team vocabulary**, or a better version of one, gets a new vocabulary id (3.8.7). Old ids stay readable forever.
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
| Packed text, 81 characters | `packed-en-hut` | 42 B | 81 B | 0.72 s | 1.01 s |
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
   - unknown body: head `unknown`, no parts (`unknown_dict` with `{n}` for `{"words": n}`, `unknown_vocab` with `{n}` for `{"vocab": n}`).
2. A value is `L.value["<group>.<code>"]`. Groups: age, consciousness, breathing, mobility, injuries, needs, sector, resource, urgency, hazard, severity, mayday, priority, acc. A value `unknown_<n>` or `bit_<n>` gives `part.unknown_value` with `{n}`.
3. A list is joined with `punct.list_sep`, the last two with `punct.list_last` ("stretcher, medic and water").
4. `line = head`, plus `punct.head_sep` and the parts joined with `punct.part_sep` if there are parts. Add `punct.end` unless the line already ends with `.`, `!` or `?`.
5. If `priority` is set and differs from the type default: `"[" + value + "] "` in front.
6. If `pos` is set: a space, `part.pos` with `{lat}` and `{lon}`, then `" (" + acc value + ")"` if acc is not gps, then `punct.end`. Coordinates are printed from the integer value with exactly 5 decimals and a `.` (for example `50.69212`, `-0.00001`), never with locale formatting.
7. If `time` is set: a space, `part.time` with `{time}`, then `punct.end`.
8. Placeholders are replaced as plain text: `{n}`, `{list}`, `{lat}`, `{lon}`, `{time}`, `{ref}`, `{node}`, `{team}`, `{sector}`, `{resource}`.

Example: `Person gefunden: Kind, ansprechbar, Unterkühlung, Knochenbruch, braucht Trage, 2 weitere Helfer benötigt. Position 50.69212, 7.21345.`

The line is also what stock Meshtastic apps (and apps that only show text) can receive from the gateway's readable-text connector, with `[RC] ` in front (section 2.1).

## 11. Extraction (speech or typing to codes)

`vocab/sar.yaml` lists every code with its wire id, meaning and German and English words. Rule-based extractors (Kotlin on the phone, Python on the gateway) use these words; the file is data, so new words do not need new code. It also lists negation words ("nicht", "kein", "not"), uncertainty words ("unklar", "maybe"), number words ("zwo" = 2) and words for helpers, radius and coverage.

Rules for extractors:
- Only what the sentence says. Unknown stays unknown. "Atmung unklar" leaves breathing unset.
- Negation turns a cue off: "nicht unterkühlt" sets nothing. "nicht ansprechbar" is unconscious.
- One message per report. If the sentence holds facts that one message cannot carry safely (two persons in different states, an object found instead of a person), the extractor MUST NOT guess. It offers the text fallback.
- No id and no device GPS: the app adds both. Spoken coordinates get `acc: estimated`.
- A person always checks the fields before sending.

`vectors/extraction.json` has 73 test sentences (44 German, 29 English) in volunteer style, with typos, negations and quantities; 10 are marked `unsupported` and expect the text fallback. Every extractor SHOULD pass them; the Kotlin and Python extractors MUST give the same fields for each.

## 12. Conformance: writing a new library

1. Generate code from `rescuecodec/v2/rescuecodec.proto` with the official protobuf tools for your language (for example `protoc --kotlin_out`, `protoc-gen-es` or `ts-proto` for TypeScript). Use the official runtime, so canonical encoding comes for free.
2. Write the JSON mapping from section 4 and the validation from section 5. Use `rescuecodec/v2/codec.py` as the reference.
3. Load `vectors/codec.json` in your test suite:
   - `valid`: `encode(input)` gives `hex` exactly, and `decode(hex)` gives `decoded` (compare as parsed JSON).
   - `decode_only`: `decode(hex)` gives `decoded`.
   - `invalid_encode`, `invalid_decode`: rejected. Error codes SHOULD match `error`; `field` MAY be compared.
   - `v1`: if you support v1, `decode_any(hex)` gives `decoded`.
   - `packed_text`: `unpack(deflate, dict)` gives `text` (inflate the reference bytes), `decode(hex)` gives `decoded`, and `decode(encode(decoded))` gives `decoded` again. The bytes of your own packing may differ from `deflate`.
   - `text_dicts`: the SHA-256 of your bundled dictionary matches.
   - `compact_text`: `encode(input)` gives `hex` exactly, `decode(hex)` gives `decoded`, `encode_words(text, 1)` gives `words` exactly, `decode_words(words, 1)` gives `text`, and `render` gives `render.en` / `render.de`.
   - `word_streams`: token streams only (no packet), both ways. `word_streams_invalid`: rejected with `error`.
   - `word_dicts`: the SHA-256 and entry count of your bundled word dictionary match.
   - `vectors/domain.json` (section 3.8): `frames`: `encode_domain(text, vocab)` gives `hex` exactly and `decode_domain(hex)` gives `decoded`; `streams` and `random`: the bit stream both ways; `invalid_decode` / `invalid_encode`: rejected with `error`; `unknown`: decodes to `{"unknown": {"vocab": n}}`; `choice`: your send rule picks the same `form`, `vocab` and bytes; `vocabularies`: the SHA-256 of each bundled vocabulary matches.
4. Level 2 (optional): `render(decoded, "en")` and `"de"` give the `text` strings exactly.
5. Level 3 (optional): your extractor passes `vectors/extraction.json`.
6. State in your README: "Passes RescueCodec 2.3.0 conformance Level 1 (and 2, 3)". That is the proof. Never edit the vectors to make a library pass; report a bug instead.

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
- **Channel**: port 256 is free for any app, and a radio that also has the public LongFast channel hears other teams' packets on it. Receivers MUST accept RescueCodec packets only from private channels (the Meshtastic channel index of the packet, section 2.2), or as a direct message to their own node, and senders MUST NOT send reports on a public channel. The default channel for sending is the one named `RescueCodec`, else the only channel with its own key. When there is none, sending without a named channel is refused.
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
| `spec/vectors/codec.json` | 207 conformance cases (43 valid, 15 decode-only, 89 invalid, 5 v1, 5 packed texts, 14 compact texts, 8 word streams, 11 invalid word streams, 9 compact frames, 8 invalid compact frames) and the SHA-256 of each text and word dictionary |
| `spec/vocab/text-dict-v1.txt` | Text dictionary 1 for packed texts (frozen) |
| `spec/vocab/words-dict-v1.txt` | Word dictionary 1 for compact texts (frozen, section 3.6.8) |
| `spec/vocab/words-dict-v2.txt` | Word dictionary 2, 61440 entries (frozen, section 3.6.8) |
| `spec/vocab/domain-{emergency,sar,fire,hiking,medical}-v1.txt` | Team vocabularies 1 to 5, 1016 entries each (frozen, section 3.8.7) |
| `spec/vocab/domains.yaml` | Vocabulary ids, code widths, SHA-256, sources, licences and measurements |
| `spec/vectors/domain.json` | 108 domain frame cases (24 frames, 5 streams, 40 random, 21 invalid decodes, 5 invalid encodes, 3 unknown vocabularies, 10 send-rule choices) and the SHA-256 of each vocabulary |
| `spec/tools/domain_vocab.py` | Builds, chooses and measures the team vocabularies (corpora are downloaded, not in the repo) |
| `spec/tools/measure_domain.py` | Measures the domain frame through the real send rule on held-out text (bytes per word, what Auto picks, one-packet share, bytes in the APK) |
| `spec/tools/gen_domain_vectors.py` | Regenerates `domain.json` from the reference |
| `spec/tools/words_dict.py` | Builds, chooses and measures word dictionary 1 (corpora are downloaded, not in the repo) |
| `spec/tools/words_dict2.py` | Builds, chooses and measures word dictionary 2 and the compact frame |
| `spec/tools/text_dict.py` | Builds and measures the text dictionary (`text-dict-words.txt`, `text-corpus.json`) |
| `spec/vectors/extraction.json` | 73 extraction sentences |
| `spec/api/openapi.yaml` | Gateway REST and WebSocket API |
| `spec/tools/gen_vectors.py` | Regenerates `codec.json` from the reference |
| `rescuecodec/v2/` | Python reference library |
