# Architecture

This page explains how the parts fit together and why they are built this way. The rules of the message format are in `spec/SPEC.md`. How to use each part is in `README.md` and `docs/INTEGRATE.md`.

## The idea in one picture

```
 report (speech or text)
        |
        v
  extractor (rules, offline) ----> JSON message (spec section 4)
        |                                   |
        |  the person checks the fields     v
        |                          encode: protobuf, 8 to 64 bytes
        v                                   |
  send queue + airtime budget <-------------+
        |
        v   Meshtastic packet, port 256 (PRIVATE_APP), broadcast, private channel
   radio ~~~~~~~~ LoRa ~~~~~~~~ radio
                                  |
                                  v   decode: same rules, any library
                         JSON message + readable line (en, de)
                                  |
                                  v
                       task board: open -> accepted -> done
                                  |
                                  v   Ack, 11 to 22 bytes, broadcast
                             back to every team
```

Four decisions shape everything:

1. **One wire format, many libraries.** A protobuf file (`spec/rescuecodec/v2/rescuecodec.proto`) defines the bytes. Python, Kotlin and TypeScript each have a codec. All three must pass the same test vectors (`spec/vectors/codec.json`), so they give identical bytes. Nobody has to port a second spec.
2. **Meaning, not words.** The message holds codes (age group, injuries, needs), not sentences. The words come from `spec/vocab` and `spec/i18n` at the receiving end. That is why 105 bytes of German text become 23 bytes, and why the same report reads in German on one screen and English on another.
3. **The radio is a transport, nothing more.** We use stock Meshtastic firmware 2.7 or 2.8 and its encryption, routing and repeating. We add nothing to the firmware. Reports ride on the private-app port 256, broadcast on a private channel with its own key.
4. **Honest about delivery.** A broadcast has no end-to-end proof. "Sent", "relayed by a neighbour" and "accepted by a person" are three different facts, and every screen says which one it knows.

## Parts

| Part | Where | Job |
|---|---|---|
| Spec | `spec/` | Protobuf, canonical JSON, vocabulary, translations, vectors, OpenAPI. The single source of truth. |
| Python package | `rescuecodec/v2`, `rescuecodec/engine` | Reference codec, rule-based extractor, send queue, task board. |
| Gateway | `rescuecodec/gateway` | A FastAPI service next to one radio. Receives, stores, serves. |
| Dashboard | `dashboard/` | Command-post web page. Uses only the public gateway API. |
| TypeScript library | `ts/` | Codec and readable text for browsers and Node. |
| Kotlin libraries | `android/codec`, `android/engine` | Same as Python, for the JVM and Android. |
| Android app | `android/app` | Field device: speech, report, send queue, radio link, tasks. |
| Simulator | `sim/` | Three real firmware nodes in Docker. |
| Hardware check | `hil/`, `HARDWARE_CHECK.md` | Read-only checks and a checklist for real radios. |

### Spec (`spec/`)

The spec is a folder you can copy. `SPEC.md` is the text. `rescuecodec/v2/rescuecodec.proto` is the one wire definition. `vocab/sar.yaml` holds the words the extractor knows (German and English). `i18n/*.json` holds the words for readable text. `vectors/` holds test cases: valid and invalid messages with exact bytes, readable text, and 73 extraction sentences. `api/openapi.yaml` describes the gateway. A new library is finished when it passes the vectors (SPEC section 12).

### Engine

Three pieces, written first in Python and ported line by line to Kotlin:

- **Extractor.** Spoken or typed German or English becomes one message. It uses plain rules: token matching, a filler word list, negation ("nicht ansprechbar"), number words, typos of one edit, and spoken coordinates. If it cannot code a sentence safely, it says so and offers the plain text message instead. It never guesses a medical fact. A person always checks the chips before sending. An optional local model (Ollama) can help in Python, but the rules are the default and the only path on the phone.
- **Send queue and airtime budget.** EU 868 MHz allows 10 percent airtime per hour (360 s). The queue computes LoRa airtime per packet, sends Mayday first, then Found, Request and Ack, then Hazard, Sector, Track and Text. Each type has its own ceiling (a Mayday and an Ack are never held, tracks stop at 70 percent). A held message stays queued and goes out when the window frees up.
- **Task tracker.** Every Found, Request, Hazard and Mayday is a task with the key `<sender node>-<id>`. Its state comes from the newest Ack of each team: done from anyone is final, else accepted while any team has it accepted, else declined if a team declined, else open. Duplicates are dropped by (sender, id) for 24 hours.

The Python engine, the Kotlin engine and the vectors agree: 73 of 73 extraction cases in both, and the same rules for the queue and tracker (SPEC section 15.1).

### Gateway

```
 radio adapter  ---->  service  ---->  event hub  ---->  connectors (own thread)
 (TCP, USB, memory)    SQLite            |                MQTT, TAK CoT, readable text
                       task board        +-------------->  WebSocket clients
                       send queue
                           ^
                  REST API (FastAPI)  <---- dashboard, scripts, other software
```

- **One service object.** It owns the SQLite file, the task board, the send queue and the delivery states. REST and WebSocket are thin layers on top.
- **Three radio adapters behind one interface.** Meshtastic over TCP (the simulator or a Wi-Fi radio), Meshtastic over USB serial (`--serial auto` for a Heltec V3), and an in-memory mesh (`--emulator`) for tests and demos. The Meshtastic connection is made in a supervisor thread. The gateway starts with no radio, shows `degraded`, and reconnects by itself after a USB or Wi-Fi drop.
- **Connectors never block the radio.** MQTT, TAK and readable text listen on an event bus from a worker thread. A slow broker cannot hold up a packet.
- **Private channel only.** The gateway sends and listens only on the channel named `RescueCodec` (or the only channel with its own key). Port 256 packets from other channels are dropped and counted. With no safe channel it refuses to send. The public LongFast key is never used.
- **Safe by default.** It listens on localhost. Another address needs `--token`. WebSockets check the browser `Origin`. The API may queue at most 30 messages a minute.
- **Status words.** `queued`, `sent`, `delivered`, `failed`. On a broadcast, `delivered` means a neighbour repeated it. With no neighbour in range a send ends as `failed` with `max_retransmit`, although the other side may have it. The dashboard words that "No relay confirmed". The Accept is the proof.

### Dashboard

A Vite and React page, built into `rescuecodec/gateway/static/` and served by the gateway at `/`. It uses only `/v2/*` and the WebSocket, so it is also a worked example for anyone writing their own client. Map: MapLibre with local PMTiles (vector base map and terrain for Koeln to the Siebengebirge), local glyphs, local fonts. No CDN, so it works with the network cut. Every number is tagged measured or estimated, as the API says.

### Android app

```
 UI (Compose)  <-->  ViewModels  <-->  Messenger (core)  <-->  engine (Extractor, SendQueue, RequestTracker)
                                          |   |                         |
                                  Room (messages, tasks, stats)    codec (bytes, readable text)
                                          |
                                  ConnectionManager (retry, backoff)  <-->  RadioService (foreground)
                                          |
                                  Meshtastic SDK: BLE or TCP
```

- **Foreground service.** The radio link lives in `RadioService`, so it survives a closed screen. It reconnects with backoff (1 s doubling to 60 s) and resumes when Bluetooth comes back.
- **Room database.** Every sent and received packet, every task and the stats are stored. After a restart the dedupe, task states and airtime used are rebuilt from the last 24 hours.
- **Offline speech.** Vosk models (German, English) run on the phone. They are not inside the APK. See `docs/VOICE.md`.
- **Intent API.** Other apps on the phone can send a report with an Intent. See `docs/INTEGRATE.md`.
- **Join by QR.** The app reads a Meshtastic channel QR code (bundled ZXing and CameraX, no network). It refuses links with a public or default key.
- **Licence.** The app is GPL-3.0 because the Meshtastic Kotlin SDK is. The libraries under it are Apache-2.0.

### Simulator

`sim/` runs three `meshtasticd` containers (2.7.26) with a simulated radio module. They send each packet as a UDP multicast on a Docker network, so every node hears every other node. This is the firmware's own mesh-over-LAN feature, so the real routing, channel and encryption code runs. Each node offers the normal Meshtastic TCP API on ports 4403 to 4405. The gateway and the Android emulator use it exactly as they would use a real radio. What it cannot test: radio range, airtime, RSSI and SNR, Bluetooth, USB. That is what `HARDWARE_CHECK.md` covers.

## Data flow: one report, end to end

1. The person says the pitch sentence into the app. Vosk turns speech into text. The extractor makes chips. The person checks them.
2. The app adds an `id` and the phone position, encodes 23 bytes, and puts them in the send queue (Mayday ahead of everything).
3. When the airtime budget allows, the app hands the bytes to the radio over Bluetooth with `want_ack` and a Meshtastic priority taken from the message priority.
4. The radio sends LoRa. Other radios in range repeat it. The Heltec on the Mac gets it.
5. The gateway reads the packet from USB, checks the channel, decodes it, stores it, creates the task, and pushes events to WebSocket, MQTT and TAK.
6. The dashboard shows the card with the readable text in German and English, and a pin on the map.
7. The operator clicks Accept. The gateway queues a 22-byte Ack, which goes back over the same path.
8. The app matches the Ack to the task by `(ref, node)` and shows "accepted". Done works the same way.

## Message types

| Type | Meaning | Typical size |
|---|---|---|
| found | A person found: age, state, injuries, needs, helpers needed | 11 to 34 bytes |
| track | 1 to 16 GPS points from one searcher, delta coded | 13 to 64 bytes |
| sector | A search sector is cleared, partial or blocked | 8 bytes |
| request | A resource is needed: stretcher, medic, helicopter | 23 bytes |
| hazard | A danger with a radius: rockfall, high water | 22 bytes |
| mayday | A rescuer needs help. An empty one is the SOS button | 15 to 22 bytes |
| ack | Accepted, declined or done, with ETA and team label | 11 to 22 bytes |
| text | Anything the codes cannot say, up to 180 bytes | 37 bytes and up |

## Security and privacy in short

Encryption is Meshtastic's (AES-256 on a private channel). RescueCodec adds no crypto of its own. The channel key is the trust boundary: anyone who has it can send as a team member, like on voice radio. There are no personal names in any field. Team labels look like "Trupp 3". Positions are sensitive: the gateway is local by default and needs a token to be reachable. Everything is in SPEC section 14.

## What is not done

- No end-to-end test on real radios yet (Bluetooth, USB, RF).
- No USB link in the Android app (the Mac gateway uses USB, the phone uses Bluetooth or TCP).
- No registered Meshtastic port. Port 256 is the private range (SPEC section 13).
- The Ukrainian, Turkish and Arabic words are machine drafts.
- The TypeScript library has the codec and readable text, but no extractor and no send queue.
