# RescueCodec

**Transmit meaning, not words.** A rescuer says one sentence in German or English. RescueCodec turns it into about 23 bytes, sends them over a Meshtastic LoRa radio with no internet, and shows the command post a readable line.
The command post taps Accept. 22 bytes go back, so the whole team sees who took the task.
It is an open spec with libraries (Python, Kotlin, TypeScript), a gateway (REST, WebSocket, MQTT, TAK, GeoJSON), a web dashboard and an Android app. Everything runs offline.

Built for the Hackathon at PMRExpo 2026, Koeln. This is a prototype. It is not a certified emergency or medical system. Do not rely on it for a real rescue yet.

Example: `Kind gefunden, ansprechbar, stark unterkühlt, Knöchel verletzt. Wir brauchen eine Trage und zwei Leute.` is 105 bytes as text. As a RescueCodec message it is 23 bytes with a position. The command post reads: *Person found: child, conscious, hypothermia, fracture, needs stretcher, 2 more helpers needed.*

## How it fits together

```
 FIELD                                                    COMMAND POST

 Phone + RescueCodec app                                  Mac or Pi
 (speech or typing -> 23 bytes)                           +------------------------------+
        |  Bluetooth                                      | rescuecodec-gateway          |
        v                                                 |   SQLite, task board,        |
 Meshtastic radio                                         |   airtime budget             |
 (T1000-E, T-Watch S3)                                    |                              |
        |                                                 |  REST + WebSocket :8000 -----+--> web dashboard (offline map)
        |  LoRa 868 MHz, private channel,                 |  MQTT ------------------------> Node-RED, Home Assistant
        |  port 256 (PRIVATE_APP)                         |  TAK CoT (UDP / TCP) ---------> ATAK, WinTAK, iTAK
        v                                                 |  GeoJSON export --------------> QGIS, any GIS
 Meshtastic radio  <--------- USB serial ------------>   |  readable text ---------------> logs, bots, pagers
 (Heltec V3)                                              +------------------------------+
                                                                      ^
 Same bytes, same rules, three libraries:  Python  |  Kotlin/JVM  |  TypeScript   (spec/ and its test vectors)
```

Details: `docs/ARCHITECTURE.md`. The wire format and every rule: `spec/SPEC.md`.

## What is in the repo

| Folder | What | Licence |
|---|---|---|
| `spec/` | The open spec: protobuf file, JSON shape, vocabulary, translations, test vectors, OpenAPI | Apache-2.0 |
| `rescuecodec/` | Python package: codec, rule-based extractor, send queue, gateway | Apache-2.0 |
| `ts/` | TypeScript library `@rescuecodec/core` | Apache-2.0 |
| `android/codec`, `android/engine` | Kotlin/JVM libraries | Apache-2.0 |
| `android/app` | The Android app (Compose, Meshtastic SDK, offline speech) | GPL-3.0 |
| `dashboard/` | Web dashboard (React, MapLibre) | Apache-2.0 |
| `sim/` | Three real Meshtastic nodes in Docker, no radio needed | Apache-2.0 |
| `hil/`, `HARDWARE_CHECK.md` | Checklist and scripts for the real radios | Apache-2.0 |

## Licences

Libraries, spec, gateway and dashboard are **Apache-2.0**, so anyone can use them in closed or open products. See `LICENSE`.

The Android app is **GPL-3.0** (`android/app/LICENSE`) because the Meshtastic Kotlin SDK it links is GPL-3.0. The app uses our Apache-2.0 libraries (`android/codec`, `android/engine`), which is allowed. If you want to build your own closed-source Android app, use those two libraries and talk to a radio yourself, or call our app with an Intent (see `docs/INTEGRATE.md`). If you ship a changed version of the app, you must publish its source under GPL-3.0.

Third-party parts are listed in `OFFLINE.md` with their licences (map data ODbL, Barlow and Noto fonts OFL, Vosk models Apache-2.0, Swagger UI Apache-2.0).

## Quick start

Run everything from Denis's MacBook (gateway, dashboard, app on the phones, radios), step by step: **`SETUP.md`**. The steps below are for setting up another machine.

You need Python 3.10 or newer. For the simulator also Docker. All steps work with no internet once the downloads in `OFFLINE.md` are done.

```bash
python3 -m venv .venv
.venv/bin/pip install -e '.[gateway,test]'
```

### 1. The simulator (no radio needed)

Three real Meshtastic firmware nodes in Docker (SIM-FIELD 4403, SIM-BASE 4404, SIM-RELAY 4405), on a private channel, port 256.

```bash
sim/up.sh                         # start, configure, verify
.venv/bin/python sim/smoke.py     # FIELD -> BASE and RELAY: "SMOKE PASS"
sim/down.sh                       # stop
```

More: `sim/README.md`. The simulator tests protocol and software. It has no radio range and no airtime.

### 2. Gateway and dashboard

```bash
.venv/bin/rescuecodec-gateway --tcp 127.0.0.1:4404          # the simulator
.venv/bin/rescuecodec-gateway --serial auto                 # a Heltec V3 on USB
.venv/bin/rescuecodec-gateway --emulator --emulator-demo    # no radio at all, a pretend team
```

Open http://127.0.0.1:8000/ for the dashboard and http://127.0.0.1:8000/docs for the API. Add `--mqtt 127.0.0.1` for MQTT and `--cot` for TAK. All flags: `rescuecodec-gateway --help`. More: `rescuecodec/gateway/README.md`.

The built dashboard is in `rescuecodec/gateway/static/`. To rebuild it: `cd dashboard && npm install && npm run build`. The offline map is not in git. Fetch it once with internet: `scripts/fetch-offline-assets.sh`.

Check the gateway against a radio, without transmitting: `python3 hil/check_gateway.py`.

### 3. Android app

The debug APK is `dist/RescueCodec-debug.apk` (about 51 MB, not in git). Install it on a phone with USB debugging on:

```bash
. scripts/env.sh
adb install -r dist/RescueCodec-debug.apk
```

Build it yourself (JDK 17, Android SDK, see `OFFLINE.md`):

```bash
. scripts/env.sh
cd android && ./gradlew :app:assembleDebug
mkdir -p ../dist && cp app/build/outputs/apk/debug/app-debug.apk ../dist/RescueCodec-debug.apk
```

In the app: Radio screen, Search, pick your Meshtastic radio (Bluetooth), then Report. Offline speech models are not inside the APK: see `docs/VOICE.md`. App details: `android/app/README.md`.

No phone at hand: `scripts/emulator-up.sh` starts an Android emulator that reaches the simulator at `10.0.2.2`. Stop it with `scripts/emulator-down.sh`.

### 4. Libraries

```python
from rescuecodec.v2 import encode, decode_any, render
data = encode({"id": 7, "pos": {"lat": 50.69212, "lon": 7.21345},
               "found": {"age": "child", "consciousness": "conscious", "needs": ["stretcher"]}})
print(len(data), render(decode_any(data), "de"))
```

Kotlin, TypeScript, REST, MQTT, TAK, GeoJSON and the Android Intent are in `docs/INTEGRATE.md`. Every example there was run.

### 5. Real radios

`HARDWARE_CHECK.md` is the Wednesday-morning checklist (about 10 minutes): private channel, Heltec on USB, app on the phone, Bluetooth pairing, the same scenario as the simulator test, and what to write down. Start with `hil/preflight.sh`.

## Tests

```bash
.venv/bin/python -m pytest -q                     # Python: codec, vectors, engine, gateway
(cd ts && npm install && npm test)                # TypeScript conformance
(cd android && ./gradlew :codec:test :engine:test) # Kotlin conformance
scripts/e2e-gateway.sh                            # two gateways on the simulator, MQTT and TAK observed
scripts/e2e-full.sh --runs 3                      # app on the emulator, simulator, gateway, dashboard
```

All three libraries run the same vectors in `spec/vectors/` and must give the same bytes.

## Docs

| File | What |
|---|---|
| `docs/INTEGRATE.md` | Integrate in 10 minutes: Python, TypeScript, Kotlin, REST, WebSocket, MQTT, TAK, GeoJSON, readable text, Android Intent |
| `docs/ARCHITECTURE.md` | How the parts work together and why |
| `spec/SPEC.md` | The spec: wire format, JSON, rules, test vectors, security |
| `HARDWARE_CHECK.md` | Real-device checklist |
| `OFFLINE.md` | Every download, its size and licence |
| `docs/VOICE.md` | Offline speech models |
| `docs/PLAN.md` | The team plan |

## Known limits

- Not tested on real radios yet. That is what `HARDWARE_CHECK.md` is for.
- A broadcast has no delivery proof. "Relayed by a neighbour" means a radio repeated it. Only a person's Accept proves that someone has it.
- Anyone who holds the channel key can send as a team member. Share the key by QR code only with your team.
- German and English are checked. Ukrainian, Turkish and Arabic texts are machine drafts.
- Old v1 packets from the first prototype can still be read (`decode_any`). Some first-prototype code stays in `rescuecodec/` (`protocol/`, `transport/`, `encoding/`, `api.py`). v2 uses it only to read v1 packets, for the airtime formula and for the in-memory radio. The old v1 web demo (`rescuecodec` command) is not part of the v2 product.
