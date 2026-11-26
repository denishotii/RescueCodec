# Integrate RescueCodec in 10 minutes

RescueCodec turns a rescue report into about 23 bytes and back into a readable German or English line. This page shows how other software can send and read those messages. Every example below was run, and the output printed here is the real output.

Pick the way that fits you:

| You have | Use | Needs a radio? |
|---|---|---|
| A Python program | [Python](#python) | No |
| A web app or Node program | [TypeScript](#typescript) | No |
| A JVM or Android app | [Kotlin](#kotlin) | No |
| Any language, a radio on a Mac or Pi | [REST](#rest-and-websocket) and [WebSocket](#rest-and-websocket) | Yes, via the gateway |
| A broker, Node-RED, Home Assistant | [MQTT](#mqtt) | Yes, via the gateway |
| ATAK, WinTAK or iTAK | [TAK](#tak-cot) | Yes, via the gateway |
| QGIS, Leaflet, any GIS | [GeoJSON](#geojson) | Yes, via the gateway |
| A log, a chat bot, a pager | [Readable text](#readable-text) | Yes, via the gateway |
| Chat texts in fewer bytes | [Compact text](#compact-text-and-team-vocabularies) | No (libraries), or via the gateway |
| Another Android app on the same phone | [Android Intent](#android-intent) | Yes, in the RescueCodec app |

The rules for every message are in `spec/SPEC.md`. The JSON shape is in section 4 of it. All libraries pass the same test vectors (`spec/vectors/`), so they give the same bytes.

One rule you will meet at once: `found`, `request`, `hazard` and `mayday` messages need an `id` (1 to 16383). `POST /v2/messages` and the app add it for you. The libraries and `POST /v2/encode` do not: without an id they answer `missing_field` (field `id`).

## Start a demo gateway (no hardware)

The sections from REST to Readable text talk to a gateway. This one needs no radio. It has a pretend team that reports and accepts:

```bash
python3 -m venv .venv && .venv/bin/pip install -e '.[gateway]'      # once
.venv/bin/rescuecodec-gateway --emulator --emulator-demo --db :memory:
```

Open http://127.0.0.1:8000/ for the dashboard and http://127.0.0.1:8000/docs for the API. Port 8000 busy? Add `--port 8765`, and use that port in the examples below.

The first `pip install` downloads the packages, so it needs the internet. After that the gateway runs offline.

With a real radio, use one of these instead of `--emulator --emulator-demo`:

- `--serial auto`: a radio on USB, for example a Heltec V3.
- `--ble auto` or `--ble NAME`: a radio over Bluetooth, for example a Heltec V3 on a USB charger. `auto` takes the first Meshtastic radio it finds. A name (for example `--ble Base_65b0`) is safer in a busy hall.
- `--tcp HOST:PORT`: a Wi-Fi radio, or the simulator in `sim/`.

See `rescuecodec/gateway/README.md`.

## Python

Install from the repo: `pip install -e .` (the package is `rescuecodec`, Python 3.10 or newer).

```python
from rescuecodec.v2 import encode, decode, decode_any, render, CodecError

msg = {"id": 7, "pos": {"lat": 50.69212, "lon": 7.21345},
       "found": {"age": "child", "consciousness": "conscious",
                 "injuries": ["hypothermia", "fracture"], "needs": ["stretcher"], "responders": 2}}
data = encode(msg)
print(len(data), data.hex())
print(decode(data) == msg)
print(render(decode_any(data), "en"))
print(render(decode_any(data), "de"))      # decode_any reads every form: Envelope, 0x02 and 0x03 frames, v1
try:
    encode({"found": {"age": "giant"}})
except CodecError as e:
    print(e.code, e.field)
```

Output:

```
23 08071a0908b8e6ea041082875832080841100518012802
True
Person found: child, conscious, hypothermia, fracture, needs stretcher, 2 more helpers needed. Position 50.69212, 7.21345.
Person gefunden: Kind, ansprechbar, Unterkühlung, Knochenbruch, braucht Trage, 2 weitere Helfer benötigt. Position 50.69212, 7.21345.
invalid_value found.age
```

Send the 23 bytes on Meshtastic port 256 (`PRIVATE_APP`) on a private channel. The gateway does this for you.

Spoken or typed text to a message, with the rule-based extractor (no model, no network):

```python
from rescuecodec.engine.extract import extract

r = extract("Kind gefunden, ansprechbar, stark unterkühlt. Wir brauchen eine Trage.")
print(r.message, r.unsupported)
r = extract("Wir treffen uns um acht am Parkplatz")
print(r.message, r.unsupported)
```

```
{'found': {'age': 'child', 'consciousness': 'conscious', 'injuries': ['hypothermia'], 'needs': ['stretcher']}} False
{'text': {'text': 'Wir treffen uns um acht am Parkplatz'}} True
```

When `unsupported` is true the sentence could not be coded safely. The message is then the plain text fallback. Let a person check the fields before you send. Add an `id` and a position yourself.

A text fallback of 180 bytes plus a position and an `id` can be 201 to 204 bytes, over the 200-byte limit. `fit_text(message)` (from `rescuecodec.engine`) cuts the text to fit and adds "…". The gateway does this for you.

## TypeScript

The package is not on npm yet. Build it once, then install it from the folder:

```bash
cd ts && npm install && npm run build && cd ..
npm install ./ts            # in your own project, adjust the path
```

It runs in Node 18 or newer and in browsers, with no network. The package is ESM only: `import` works, `require` does not. In Node, save the examples as a `.mjs` file, or set `"type": "module"` in your `package.json`.

```js
import { encode, decode, decodeAny, render } from "@rescuecodec/core";

const bytes = encode({ id: 7, pos: { lat: 50.69212, lon: 7.21345 },
  found: { age: "child", consciousness: "conscious", injuries: ["hypothermia", "fracture"], needs: ["stretcher"], responders: 2 } });
console.log(bytes.length, Buffer.from(bytes).toString("hex"));
console.log(render(decode(bytes), "en"));
console.log(render(decodeAny(bytes), "de"));
try { encode({ found: { age: "giant" } }); } catch (e) { console.log(e.code, e.field); }
```

```
23 08071a0908b8e6ea041082875832080841100518012802
Person found: child, conscious, hypothermia, fracture, needs stretcher, 2 more helpers needed. Position 50.69212, 7.21345.
Person gefunden: Kind, ansprechbar, Unterkühlung, Knochenbruch, braucht Trage, 2 weitere Helfer benötigt. Position 50.69212, 7.21345.
invalid_value found.age
```

More in `ts/README.md`.

## Kotlin

Two pure JVM libraries, no Android code: `:codec` (bytes, JSON, readable text) and `:engine` (text extractor, airtime send queue, task tracker, the rule that picks a text's form). The Android app uses the same two.

Use them from your Gradle build as a composite build (no publishing needed). In your `settings.gradle.kts`:

```kotlin
includeBuild("/path/to/RescueCodec/android")
```

This includes the whole `android/` build. Its `settings.gradle.kts` always includes the app (`:app`) next to `:codec` and `:engine`. So Gradle needs the Android Gradle plugin (it downloads it on the first build) and an Android SDK, even if you only use the JVM libraries. Set `ANDROID_HOME`, or put `sdk.dir=/path/to/sdk` in `android/local.properties`. How to install the SDK: `docs/SETUP-NEW-DEVICE.md`, part G.

In your `build.gradle.kts` (Kotlin 2.4 and JDK 17 or newer):

```kotlin
dependencies { implementation("org.rescuecodec:engine:0.1.0") }   // brings :codec with it
```

```kotlin
import org.rescuecodec.codec.CodecException
import org.rescuecodec.codec.RescueCodec
import org.rescuecodec.engine.Extractor

val bytes = RescueCodec.encode(mapOf(
    "id" to 7,
    "pos" to mapOf("lat" to 50.69212, "lon" to 7.21345),
    "found" to mapOf("age" to "child", "consciousness" to "conscious",
        "injuries" to listOf("hypothermia", "fracture"), "needs" to listOf("stretcher"), "responders" to 2),
))
println(bytes.size)                                                   // 23
val msg = RescueCodec.decodeAny(bytes)
println(RescueCodec.reconstruct(msg, "en"))   // Person found: child, conscious, hypothermia, fracture, needs stretcher, 2 more helpers needed. Position 50.69212, 7.21345.
println(RescueCodec.reconstruct(msg, "de"))   // Person gefunden: Kind, ansprechbar, Unterkühlung, Knochenbruch, braucht Trage, 2 weitere Helfer benötigt. ...

try { RescueCodec.encode(mapOf("found" to mapOf("age" to "giant"))) }
catch (e: CodecException) { println("${e.code} ${e.field}") }        // invalid_value found.age

val r = Extractor().extract("Kind gefunden, ansprechbar, stark unterkühlt. Wir brauchen eine Trage.")
val small = RescueCodec.encode(r.message + ("id" to 7))
println("${small.size} bytes: " + RescueCodec.reconstruct(RescueCodec.decodeAny(small), "de"))
// 10 bytes: Person gefunden: Kind, ansprechbar, Unterkühlung, braucht Trage.
```

The first part is a unit test that runs on every build: `android/engine/src/test/kotlin/org/rescuecodec/engine/IntegrateExamplesTest.kt` (`cd android && ./gradlew :engine:test`). The composite-build setup and the last two lines were run in a separate demo project. See `android/codec/README.md` and `android/engine/README.md`.

## REST and WebSocket

The gateway offers the API of `spec/api/openapi.yaml`. Swagger UI is built in at `/docs` and works offline.

Send a report. The gateway adds the id and the airtime check:

```bash
curl -s -X POST localhost:8000/v2/messages -H 'content-type: application/json' \
  -d '{"message":{"pos":{"lat":50.69212,"lon":7.21345},"found":{"age":"child","consciousness":"conscious","needs":["stretcher"]}}}'
```

Pick a channel by name with `channel`, or send a direct message with `to` (a text only). Without them it goes out on the default channel (RescueCodec):

```bash
curl -s -X POST localhost:8000/v2/messages -H 'content-type: application/json' \
  -d '{"message":{"text":{"text":"Trage kommt"}},"channel":"RescueCodec"}'
curl -s -X POST localhost:8000/v2/text -H 'content-type: application/json' -d '{"text":"Bitte melden","to":"!a1b2c3d4"}'
curl -s 'localhost:8000/v2/messages?channel=RescueCodec'     # also /v2/tasks?channel=... and /v2/export.geojson?channel=...
```

The demo gateway has one channel, RescueCodec. On a real radio, use the name of a channel on that radio, for example `"channel":"draeger-srs"`. A name the radio does not have gives `invalid_value` with the list: `no channel "draeger-srs" to send on (channels: RescueCodec)`.

Every record has `"channel": {"index": 0, "name": "RescueCodec", "public": false}` and `"to": null` (or the peer of a direct message). The index is the channel's slot on the radio: 0 on the demo gateway. `GET /v2/health` lists the channels the gateway hears in `radio.channels`. An Accept (`/v2/tasks/{key}/ack`) always goes out on the task's channel.

The answer is `202` with a record. It starts as `"status":"queued"` and moves to `sent` and then `delivered` or `failed`. Here is the start of it:

```json
{"seq":4,"key":"0000e001-1","direction":"out","type":"found","priority":"urgent","status":"queued",
 "codec":{"version":2,"size":19,"hex":"08011a0908b8e6ea0410828758320408411801"},
 "text":{"en":"Person found: child, conscious, needs stretcher. Position 50.69212, 7.21345.",
         "de":"Person gefunden: Kind, ansprechbar, braucht Trage. Position 50.69212, 7.21345."},
 "task_key":"0000e001-1"}
```

Other calls:

| Call | What it does |
|---|---|
| `GET /v2/messages?limit=20&type=found` | Everything sent and heard, with bytes, RSSI, SNR and hops |
| `GET /v2/tasks` | Open, accepted and done tasks |
| `POST /v2/tasks/{key}/ack` with `{"status":"accepted","eta":15,"team":"Trupp 3"}` | Accept (or `done`). The Ack goes over the radio. |
| `POST /v2/text` with `{"text":"Treffpunkt am Gasthaus"}` | Send a free text. Add `channel` or `to` like above. It goes out in the smallest safe form of that chat (see [Readable text](#readable-text)). |
| `POST /v2/extract` with `{"text":"Apply a tourniquet.","channel":"RescueCodec"}` | A preview, nothing is sent. The sentence rules read the text. If it stays a text, and the body has `channel` or `to`, the answer shows the form that really goes out: `form`, `vocab`, `coded_bytes`, `send_as`. A sentence the rules read as a report shows the report instead. |
| `GET /v2/channels/{name}/mode`, `PUT` with `{"mode":"compressed"}` | How texts go out in a channel: `auto`, `compressed` or `normal`. GET also gives `send_as` and the `reason`. |
| `GET /v2/nodes/{node}/mode`, `PUT` with `{"mode":"normal"}` | The same for a direct chat with one node, for example `!a1b2c3d4` |
| `POST /v2/encode` with `{"message":{...}}` | Bytes, size and readable text, nothing is sent. On a text it shows the Envelope (`hello world`: 15 bytes), not the form that goes out. Use `/v2/extract` for that. |
| `POST /v2/decode` with `{"hex":"0807..."}` | Bytes to message and readable text. Reads every form: Envelope, compact frame, domain frame, v1. |
| `GET /v2/health`, `GET /v2/stats`, `GET /v2/nodes` | Radio, counters, nodes |

Encode and decode without a radio:

```bash
curl -s -X POST localhost:8000/v2/decode -H 'content-type: application/json' \
  -d '{"hex":"08071a0908b8e6ea041082875832080841100518012802"}'
```

```json
{"v":2,"size":23,"message":{"id":7,"pos":{"lat":50.69212,"lon":7.21345},"found":{"consciousness":"conscious","age":"child","injuries":["hypothermia","fracture"],"needs":["stretcher"],"responders":2}},"text":{"en":"Person found: child, ...","de":"Person gefunden: Kind, ..."}}
```

Accept a task:

```bash
curl -s -X POST localhost:8000/v2/tasks/0000e002-1/ack -H 'content-type: application/json' \
  -d '{"status":"accepted","eta":15,"team":"Trupp 3"}'
```

The answer has the task with `"state":"accepted"`. Team labels are at most 12 bytes.

Errors have one shape, `{"error":{"code":"...","message":"...","field":"..."}}`. `429 rate_limited` means more than 30 sends in a minute. `429` with `retry_after_s` means the airtime budget (10 percent per hour) is used up. A Mayday is never held back.

**WebSocket.** `ws://127.0.0.1:8000/v2/stream` sends one JSON frame per event: `{"event": "message" | "message_status" | "task" | "node" | "stats" | "health" | "hello", "data": {...}}`. Add `?since_seq=N` after a reconnect to replay what you missed.

Python (`pip install websockets`):

```python
import asyncio, json, websockets

async def main():
    async with websockets.connect("ws://127.0.0.1:8000/v2/stream") as ws:
        for _ in range(4):
            event = json.loads(await asyncio.wait_for(ws.recv(), 20))
            print(event["event"], "|", json.dumps(event)[:100])

asyncio.run(main())
```

```
hello | {"event": "hello", "data": {"last_seq": 6, "health": {"status": "ok", "version": "2.0.0", "spec_version": "2.
stats | {"event": "stats", "data": {"since": "2026-11-25T06:57:42Z", "messages_in": {"value": 5, "unit": "count", "
message | {"event": "message", "data": {"seq": 7, "key": null, "direction": "out", "from": "!0000e001", "type": "ack
task | {"event": "task", "data": {"key": "0000e002-1", "from": "!0000e002", "id": 1, "type": "found", "priority": "
```

Node 22 or a browser (no library, `WebSocket` and `fetch` are built in). It uses top-level `await`, so in Node save it as a `.mjs` file:

```js
const base = "http://127.0.0.1:8000";
const sent = await fetch(`${base}/v2/messages`, {
  method: "POST", headers: { "content-type": "application/json" },
  body: JSON.stringify({ message: { found: { age: "child", needs: ["stretcher"] }, pos: { lat: 50.69212, lon: 7.21345 } } }),
}).then((r) => r.json());
console.log(sent.key, sent.codec.size, "bytes");

const ws = new WebSocket(base.replace("http", "ws") + "/v2/stream");
ws.onmessage = (e) => {
  const { event, data } = JSON.parse(e.data);
  if (event === "task") console.log(data.key, data.state, data.text.en);
  if (event === "task" && data.state === "done") ws.close();
};
```

```
0000e001-2 19 bytes
0000e001-2 accepted Person found: child, needs stretcher. Position 50.69212, 7.21345.
0000e001-2 done Person found: child, needs stretcher. Position 50.69212, 7.21345.
```

(With the demo team, the pretend team accepts and finishes your report a few seconds later.)

If the gateway runs on another computer, start it with `--host 0.0.0.0 --token SECRET`. It refuses a non-local address without a token. Then send `Authorization: Bearer SECRET`, or add `?token=SECRET` to the WebSocket URL. Details: `rescuecodec/gateway/README.md`, section "Who may talk to the gateway".

## MQTT

Start a local broker (`brew install mosquitto`, then `scripts/mqtt-up.sh`; stop it later with `scripts/mqtt-down.sh`) and tell the gateway where it is:

```bash
.venv/bin/rescuecodec-gateway --emulator --emulator-demo --db :memory: --mqtt 127.0.0.1
mosquitto_sub -h 127.0.0.1 -t 'rescuecodec/v2/#' -v
```

| Topic | Payload |
|---|---|
| `rescuecodec/v2/messages` | Every message record (same JSON as the REST API) |
| `rescuecodec/v2/found`, `track`, `sector`, `request`, `hazard`, `mayday`, `ack`, `text` | The same record, split by type |
| `rescuecodec/v2/channels/<name>` | The same record, split by channel (for example `channels/draeger-srs`) |
| `rescuecodec/v2/tasks` | A task each time it changes |
| `rescuecodec/v2/readable` | One plain English line |
| `rescuecodec/v2/status` | `online` or `offline` (last will) |

Output of `mosquitto_sub` after one POST (cut to 100 characters):

```
rescuecodec/v2/status online
rescuecodec/v2/messages {"seq":4,"key":"0000e001-1","direction":"out","from":"!0000e001","type":"found","priority":"urgent",...
rescuecodec/v2/readable Person found: child, conscious, needs stretcher. Position 50.69212, 7.21345.
rescuecodec/v2/tasks {"key":"0000e001-1","from":"!0000e001","id":1,"type":"found","priority":"urgent","state":"open",...
rescuecodec/v2/readable Accepted #1: from !0000e001, by Demo 1, ETA 10 min.
rescuecodec/v2/tasks {"key":"0000e001-1","from":"!0000e001","id":1,"type":"found","priority":"urgent","state":"accepted",...
```

In Python (`pip install paho-mqtt`):

```python
import json
import paho.mqtt.client as mqtt

def on_connect(client, userdata, flags, reason_code, properties):
    client.subscribe("rescuecodec/v2/readable")
    client.subscribe("rescuecodec/v2/tasks")

def on_message(client, userdata, msg):
    if msg.topic.endswith("/readable"):
        print(msg.payload.decode())
    else:
        task = json.loads(msg.payload)
        print(task["key"], task["state"])

client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
client.on_connect, client.on_message = on_connect, on_message
client.connect("127.0.0.1", 1883)
client.loop_forever()
```

```
Hazard: rockfall, high risk, keep out, radius 50 m. Position 50.69300, 7.21600.
0000e001-4 open
```

Use `--mqtt-user`, `--mqtt-password` and `--mqtt-prefix` for a real broker. The gateway never blocks the radio when the broker is slow.

## TAK (CoT)

```bash
.venv/bin/rescuecodec-gateway --serial auto --cot                 # UDP multicast 239.2.3.1:6969
.venv/bin/rescuecodec-gateway --serial auto --cot-tcp 192.168.1.5:8087    # a TAK server over TCP
```

`--cot-udp GROUP:PORT` changes the multicast group, `--cot-iface IP` picks the network card. ATAK, WinTAK and iTAK on the same network show the markers with no setup. The remarks end with the channel, for example `[OPEN] (channel draeger-srs)`.

Try it with no radio, all on this computer:

```bash
nc -l 127.0.0.1 18087        # in a second terminal: plays the TAK server and prints what arrives (macOS nc)
.venv/bin/rescuecodec-gateway --emulator --emulator-demo --db :memory: --cot --cot-iface 127.0.0.1 --cot-tcp 127.0.0.1:18087
```

The demo team's first report arrives at `nc` within a few seconds. Without anything on port 18087 the gateway logs "TAK server ... not reachable" and tries again every 5 seconds. The UDP multicast goes on.

One event, as sent for the Found report above (the first line of a test run):

```xml
<event version="2.0" uid="RescueCodec.0000e001-1" type="b-m-p-s-m" time="2026-11-25T06:57:55.434Z" start="2026-11-25T06:57:55.434Z" stale="2026-11-25T12:57:55.434Z" how="h-g-i-g-o"><point lat="50.69212" lon="7.21345" hae="9999999.0" ce="10.0" le="9999999.0" /><detail><status readiness="true" /><contact callsign="FOUND 0000e001-1" /><remarks source="RescueCodec">Person found: child, conscious, needs stretcher. Position 50.69212, 7.21345. [OPEN] (channel RescueCodec)</remarks><color argb="-65536" /><usericon iconsetpath="COT_MAPPING_SPOTMAP/b-m-p-s-m/-65536" /></detail></event>
```

The uid is `RescueCodec.<task key>`, so Accept and Done update the same marker. A Mayday is the ATAK emergency alert (`b-a-o-tbl`, cancelled with `b-a-o-can` when done). The full table is in `rescuecodec/gateway/README.md`.

Check the UDP multicast without TAK, with a short listener:

```python
import socket
IFACE = "127.0.0.1"   # with --cot-iface 127.0.0.1, or a computer with no network. On a normal network: "0.0.0.0"
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("", 6969))
s.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, socket.inet_aton("239.2.3.1") + socket.inet_aton(IFACE))
print(s.recvfrom(65535)[0].decode())
```

The listener must join the group on the interface the gateway sends on. Without a network (Wi-Fi off in the field) the gateway falls back to the loopback interface by itself, so then use `127.0.0.1` too. A listener that joins on `0.0.0.0` hears nothing from a gateway that sends on loopback.

## GeoJSON

```bash
curl -s localhost:8000/v2/export.geojson > rescue.geojson
curl -s 'localhost:8000/v2/export.geojson?type=found' | head -c 100
```

It is a `FeatureCollection`. Finds, hazards, maydays and requests are points, tracks are lines. Every feature has these properties: `seq`, `key`, `type`, `from`, `priority`, `state`, `acc`, `channel`, `to`, `time`, `text_en`, `text_de`. Real output:

```json
{"type": "Feature", "geometry": {"type": "Point", "coordinates": [7.21345, 50.69212]},
 "properties": {"seq": 1, "key": "0000e002-1", "type": "found", "from": "!0000e002", "priority": "urgent",
  "state": "open", "acc": "gps", "time": "2026-11-25T06:57:45Z",
  "text_en": "Person found: child, conscious, hypothermia, fracture, needs stretcher, 2 more helpers needed. Position 50.69212, 7.21345.",
  "text_de": "Person gefunden: Kind, ansprechbar, Unterkühlung, Knochenbruch, braucht Trage, 2 weitere Helfer benötigt. Position 50.69212, 7.21345."}}
```

Coordinates are `[lon, lat]` as GeoJSON requires. Open the file in QGIS, geojson.io (needs internet) or Leaflet.

## Readable text

Every record carries the same report in English and German, so a log, a chat bot or a pager needs no codec:

- REST and WebSocket: `record.text.en` and `record.text.de`.
- MQTT: topic `rescuecodec/v2/readable`.
- Any bytes you have: `POST /v2/decode` (above), or `render(msg, "en")` in the libraries. Languages: `en`, `de` (checked), `uk`, `tr`, `ar` (machine drafts, need a native check).
- Stock Meshtastic apps do not show port 256. To let them show the line anyway, start the gateway with `--text-over-air out` (what it sends) or `both`. It costs extra airtime, so it is off by default. The copy starts with `[RC] `, and RescueCodec receivers skip such texts (SPEC section 2.1).
- Free text is different. A message with only a text goes out per chat, in the smallest safe form (SPEC section 2.3):
  - a normal Meshtastic text (port 1), which every app shows;
  - a packed text (DEFLATE, port 256, about 40 % smaller);
  - with `--compact-text on`: a compact frame (`0x02`, 2-byte words) or a domain frame (`0x03`, a team vocabulary, `--vocabulary`). See [Compact text](#compact-text-and-team-vocabularies).

  Auto packs only where everyone runs RescueCodec, so a person with the official app can always read the chat. Set it with `PUT /v2/channels/{name}/mode` or `PUT /v2/nodes/{node}/mode` (`auto`, `compressed`, `normal`), or in the app's chat menu.

  The record says which form went out. A normal text has `codec.version: 0` and no `codec.form`. A packed one has `codec.packed: true` and `codec.form`: `deflate` (DEFLATE), `words` (word tokens in the Envelope), `lean` (compact frame) or `domain` (domain frame, with `codec.vocab`, for example `medical`). The words are always already unpacked in `message.text.text`.

Example lines:

```
Person found: child, conscious, hypothermia, fracture, needs stretcher, 2 more helpers needed. Position 50.69212, 7.21345.
Hazard: rockfall, high risk, keep out, radius 50 m. Position 50.69300, 7.21600.
Accepted #1: from !0000e001, by Demo 1, ETA 10 min.
```

### Packed texts in your own code

Every library unpacks them; you only need the dictionary file, which ships with the library.

```python
from rescuecodec.v2 import encode, decode_any
data = encode({"id": 3, "text": {"text": "We are at the hut, two people with us, waiting for the vehicle on the forest road", "dict": 1}})
print(len(data))                    # 42 (81 bytes as a normal text)
print(decode_any(data)["text"])     # {'text': 'We are at the hut, ...', 'dict': 1}
```

Read every port 256 payload with `decode_any` (Python), `decodeAny` (TypeScript) or `RescueCodec.decodeAny` (Kotlin). They read the Envelope, the compact frame (`0x02`), the domain frame (`0x03`) and old v1 packets, and give the same JSON. Plain `decode` reads only the Envelope: it rejects the two frames with `unsupported_version`.

Only send packed texts on port 256 to nodes that run RescueCodec: stock Meshtastic apps cannot unpack them.

## Compact text and team vocabularies

Chat texts can go out in even fewer bytes. This is off by default. Two more forms:

- **Compact frame** (`0x02`): every known English word is 2 bytes. `hello world` is 6 bytes instead of 11.
- **Domain frame** (`0x03`): a team vocabulary of 1016 words for one kind of work. Each of these words costs 10 bits. `Apply a tourniquet.` is 7 bytes with the Medical list, instead of 19.

Names, numbers, coordinates and emoji are copied byte for byte, so the receiver gets the exact text. The sender builds every form and sends the strictly smallest; on a tie the normal text wins. Measured results: [COMPACT_TEXT.md](COMPACT_TEXT.md).

### On the gateway

```bash
.venv/bin/rescuecodec-gateway --emulator --compact-text on --vocabulary auto --db :memory:
```

- `--compact-text on` turns it on. Default: off.
- `--vocabulary` picks the team vocabulary: `auto` (default: try every list), `general` (no list, the compact frame only), or one list: `emergency`, `sar`, `fire`, `hiking`, `medical`.
- `GET /v2/health` shows `compact_text`, `vocabulary` and the lists in `vocabularies`.

Compact text is used only in a chat that packs. A fresh emulator has heard nobody yet, so Auto sends a normal text there (`reason` `nobody_known` in `GET /v2/channels/RescueCodec/mode`). Set the chat to compressed first:

```bash
curl -s -X PUT localhost:8000/v2/channels/RescueCodec/mode -H 'content-type: application/json' -d '{"mode":"compressed"}'
curl -s -X POST localhost:8000/v2/text -H 'content-type: application/json' -d '{"text":"Apply a tourniquet."}'
```

The record says what went out:

```json
"codec":{"version":2,"size":7,"hex":"0305e300609c00","packed":true,"form":"domain","vocab":"medical","vocab_id":5,"vocab_label":"Medical"}
```

`POST /v2/extract` with `{"text":"Apply a tourniquet.","channel":"RescueCodec"}` shows the same without sending: `"form":"domain"`, `"vocab":"medical"`, `"coded_bytes":7`.

### In your own code

Python:

```python
from rescuecodec.v2 import encode_compact, encode_domain, decode_any, render

lean = encode_compact("hello world")
print(len(lean), lean.hex())             # 6 0202033e0124
med = encode_domain("Apply a tourniquet.", "medical")
print(len(med), med.hex())               # 7 0305e300609c00
print(decode_any(med))                   # {'v': 2, 'text': {'text': 'Apply a tourniquet.', 'vocab': 5}}
print(render(decode_any(med), "en"))     # Message: Apply a tourniquet.
```

TypeScript (save as `.mjs`):

```js
import { encodeCompact, encodeDomain, decodeAny } from "@rescuecodec/core";

console.log(encodeCompact("hello world").length);     // 6
const med = encodeDomain("Apply a tourniquet.", "medical");
console.log(Buffer.from(med).toString("hex"));         // 0305e300609c00
console.log(decodeAny(med));                           // { v: 2, text: { text: 'Apply a tourniquet.', vocab: 5 } }
```

Kotlin:

```kotlin
val lean = RescueCodec.encodeCompact("hello world")                    // 6 bytes
val med = RescueCodec.encodeDomain("Apply a tourniquet.", "medical")   // 7 bytes: 0305e300609c00
println(RescueCodec.decodeAny(med))                                    // {v=2, text={text=Apply a tourniquet., vocab=5}}
```

The vocabulary is a short id: `emergency`, `sar`, `fire`, `hiking` or `medical` (or its number, 1 to 5). A full name like `"Search & Rescue"` throws `invalid_value` (field `text.vocab`). The full names are labels for people, in the app and in `GET /v2/health`.

**Pick the smallest form, like the app.** Python and Kotlin have the sender rule:

```python
from rescuecodec.engine.textmode import choose_form

f = choose_form({"id": 3, "text": {"text": "Apply a tourniquet."}}, True, compact=True, vocab="auto")
print(f.form, f.vocab, f.packed, len(f.payload))    # domain medical True 7
f = choose_form({"id": 3, "text": {"text": "Ok"}}, True, compact=True)
print(f.form, f.packed, len(f.payload))             # plain False 2
```

- The second argument says whether the chat packs. `wants_packed(...)` in the same module gives it, with the reason.
- `f.packed` True: send `f.payload` on port 256. False: send it as a normal Meshtastic text on port 1.
- `vocab` is `auto`, `general` or one short id, like `--vocabulary`.
- A message with `pos` or `time` never gets a frame (a frame has no id and no position). It goes in the Envelope (`words` or `deflate`), or as a normal text when that is smaller.

Kotlin: `TextMode.chooseForm(message, wantPacked = true, compact = true, vocab = "auto")` in `:engine` gives the same `form`, `vocab` and `payload`. Both pass the same `choice` cases in `spec/vectors/domain.json`. The TypeScript library has no sender rule: it encodes and decodes every form, and you pick the form yourself.

## Android Intent

Use this when you have an Android app (a control app, a drone app, a note-taking app) and you want it to send a RescueCodec report over the mesh radio **without adding any library**. You send an Intent to the RescueCodec app. It queues the report, sends it over the connected radio, and returns a result.

What you need: the RescueCodec app installed on the same phone, and a radio connected to it.

What it does **not** do: it shows no screen and asks nobody for confirmation. A report goes out to the whole rescue team. Your app must let a person check the content first.

### The call

| | |
|---|---|
| Action | `org.rescuecodec.action.SEND_REPORT` |
| Component | `org.rescuecodec.app/.intent.SendReportActivity` (exported) |
| Start with | `startActivityForResult`, or the Activity Result API |
| Android 11 and newer | Add a `<queries>` entry to your manifest (see below), or the system hides our app from you. |

```xml
<!-- Your AndroidManifest.xml -->
<queries>
    <intent>
        <action android:name="org.rescuecodec.action.SEND_REPORT" />
    </intent>
</queries>
```

### Input extras

Send **one** of `JSON` or `TEXT`. All names start with `org.rescuecodec.extra.`

| Extra | Type | Meaning |
|---|---|---|
| `JSON` | String | A message in the canonical JSON shape of `spec/SPEC.md` section 4. You need no `id`: the app adds one for found, request, hazard and mayday. |
| `TEXT` | String | Free text in German or English, for example "Kind gefunden, ansprechbar, unterkühlt, braucht Trage". The rule-based extractor turns it into a message. If it cannot code the sentence safely, the report goes out as a plain text message (same as in the app). |
| `LANG` | String | `de` or `en`. Only a hint for `TEXT`. Optional. |
| `LAT`, `LON` | double | Position in degrees. Added as `pos` if the message has none. Optional. |
| `PRIORITY` | String | `critical`, `urgent`, `normal` or `routine`. Overrides the default of the message type. Optional. |
| `WAIT_SECONDS` | int | 0 to 30. If above 0, the call returns only when the report was handed to the radio, or when the time is up. Default 0 (return as soon as it is queued). |

### Result

`resultCode` is `RESULT_OK` if the report is accepted, `RESULT_CANCELED` if it is refused. The data Intent has these extras:

| Extra | Type | Meaning |
|---|---|---|
| `RESULT_JSON` | String | Everything below as one JSON object. |
| `RESULT_STATUS` | String | `queued`, `sent`, `delivered`, `not_confirmed`, `failed`, `expired`, `cancelled` or `error`. |
| `RESULT_ID` | int | The RescueCodec id of the report (0 if the type has none). Use it to follow the task. |
| `RESULT_ERROR` | String | Only on error. A stable code, see below. |

Example `RESULT_JSON` for an accepted report (the id is different every time):

```json
{"ok": true, "status": "queued", "type": "found", "id": 7, "size": 21,
 "hex": "08071a0908b8e6ea04108287583206084110011801",
 "readable": "Person found: child, conscious, hypothermia, needs stretcher. Position 50.69212, 7.21345.",
 "radio": "connected"}
```

`radio` is the link state at the time of the call: `connected`, `connecting`, `disconnected` or `error`.

`status` meanings:

- `queued`: in the send queue. It goes out when the radio is connected, has a private channel (see "Good to know") and the airtime budget allows. If the radio is off, it waits. Nothing is lost when the app restarts.
- `sent`: handed to the radio.
- `delivered`: another radio repeated it, so the mesh took it. This is **not** the same as "a person accepted it". The Accept arrives later as an Ack.
- `not_confirmed`: the radio sent it, but nobody repeated it. It may or may not have arrived.
- `failed`: the radio could not send it.
- `expired` or `cancelled`: it was dropped before it left the phone (too old, replaced by a newer message, or cancelled by the person).
- With `WAIT_SECONDS`, `RESULT_JSON` also has `key` (for example `a1b2c3d4-7`) and `detail` (the reason when the radio refused because of the airtime limit).

Errors (`RESULT_ERROR` and `error` in `RESULT_JSON`, with a `message` in plain English):

| Code | Meaning |
|---|---|
| `no_input` | Neither `JSON` nor `TEXT` was given. |
| `invalid_json` | `JSON` is not a JSON object. |
| `invalid_value`, `unknown_key`, `missing_field`, `no_body`, `multiple_bodies`, `too_large`, `malformed`, `empty`, `unsupported_version` | The message breaks a rule of the spec (section 5). `field` names the field, for example `found.age`. |
| `disabled` | The person turned off "Let other apps send reports" in the RescueCodec app (Settings, Other apps). |

### Kotlin example

```kotlin
val send = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
    val json = r.data?.getStringExtra("org.rescuecodec.extra.RESULT_JSON")
    if (r.resultCode == Activity.RESULT_OK) showToast("Queued: $json")
    else showToast("Refused: $json")
}

fun sendFound() {
    val i = Intent("org.rescuecodec.action.SEND_REPORT").apply {
        setPackage("org.rescuecodec.app")
        putExtra("org.rescuecodec.extra.JSON", """
            {"found": {"age": "child", "consciousness": "conscious",
                       "injuries": ["hypothermia"], "needs": ["stretcher"]},
             "pos": {"lat": 50.69212, "lon": 7.21345}}
        """.trimIndent())
    }
    send.launch(i)
}
```

Free text instead of JSON:

```kotlin
val i = Intent("org.rescuecodec.action.SEND_REPORT")
    .setPackage("org.rescuecodec.app")
    .putExtra("org.rescuecodec.extra.TEXT", "Kind gefunden, ansprechbar, braucht Trage")
    .putExtra("org.rescuecodec.extra.LAT", 50.69212)
    .putExtra("org.rescuecodec.extra.LON", 7.21345)
    .putExtra("org.rescuecodec.extra.WAIT_SECONDS", 10)
```

### Try it from a computer

With a phone or emulator connected over `adb`:

```bash
adb shell am start -W -a org.rescuecodec.action.SEND_REPORT \
  -n org.rescuecodec.app/.intent.SendReportActivity \
  --es org.rescuecodec.extra.TEXT "Kind gefunden, ansprechbar, braucht Trage"
adb logcat -d | grep RescueCodec     # "sent found id=... 21 B" once the radio took it
```

`am start` cannot show the result extras. Use the Kotlin example for that.

### Good to know

- The first call starts the radio service if a radio is remembered. The RescueCodec app must have been opened and connected once, so Bluetooth permissions and pairing are done.
- Ids count up from a random start and wrap at 16383. Receivers ignore the same (sender, id) for 24 hours.
- The sender of the report on the mesh is the phone's radio. Your app's name is only kept in the local log.
- **The radio needs a private channel.** Reports go out only on a channel that has its own key. The app uses the channel named `RescueCodec`. If there is none, it uses the only channel with its own key. If there are several, or none, nothing is sent: the message stays `queued` and `detail` says "No private RescueCodec channel on this radio" (or similar). The app never falls back to channel 0 or to the public default key (LongFast). The person can pick a channel on the Radio tab, under "Reports go on".
- The Intent API is on by default and any installed app can call it. The person can switch it off in Settings, Other apps, "Let other apps send reports". The calling package is kept in the local message log.
- Everything stays on the phone and on the radio channel. The app never uses the internet.
