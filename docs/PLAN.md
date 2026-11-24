# RescueCodec v2: the plan

Revised Wed 25 Nov, ~00:30. First draft (with the demo story) is in `plan-v2-first-draft.md`.
**What changed:** reuse and interoperability are now the core, not a side note. We build a real product that others can plug into, not a demo. Pitch and story come later (Wednesday evening). First we make it work.

Users: outdoor rescue teams and volunteers (search parties, Bergwacht, DLRG, dog teams, THW/DRK, spontaneous helpers).

## 1. Goal
An open, offline engine that turns a spoken or typed report into a tiny message any mesh radio can carry, and that **any app can read and send**: our Android app, Dräger's app, TAK maps, control-room software.

## 2. How it is built: 4 layers
```
 1. OPEN SPEC        rescuecodec.proto + word lists (SAR, medical...) + languages + test vectors
                          |
 2. LIBRARIES        Kotlin/JVM    Python    TypeScript    (later: C for radio firmware)
                          |
 3. ENGINE           understand -> fields -> encode -> priority queue -> send -> Accept/Done
                          |
 4. ADAPTERS         IN/OUT over radio:  Meshtastic (Bluetooth, USB, Wi-Fi)  | later: MeshCore, LoRaWAN, TETRA SDS, satellite
    CONNECTORS       to other software:  REST + WebSocket API | MQTT (JSON) | TAK map (CoT) | GeoJSON | readable text
                          |
    APPS             Android app (field)   Gateway + dashboard (command post, laptop or Raspberry Pi)
```
Rule: **our own apps use only the public API and libraries.** If our dashboard can do it, anyone can.

## 3. How others can use it
| Who | How | Their effort |
|---|---|---|
| An Android app (e.g. Dräger's) | Add our Kotlin library. `RescueCodec.encode(report)` gives bytes, `decode(bytes)` gives a report | About 10 lines of code |
| Any other Android app | Send an Android "Intent" to our app ("send this report") and get the result back | No library needed |
| Control-room or command software | Local REST + WebSocket API on the gateway, documented (OpenAPI page) | HTTP calls |
| TAK users (ATAK, WinTAK, iTAK, common in search and rescue) | Gateway sends found persons, Mayday and tasks as map markers and alerts (CoT format) | None, it shows on their map |
| Meshtastic users without our app, and Dräger's app (it already shows "[Mesh]" text) | Gateway also posts a short readable line, e.g. "FOUND child, conscious, hypothermia, needs stretcher +2, 50.692,7.213" | None |
| Dashboards, Node-RED, home-made tools | MQTT topics with JSON, same style as Meshtastic's own MQTT | Subscribe to a topic |
| GIS / maps | GeoJSON export of finds, tracks, searched sectors | Open the file |
| Developers in Python or JavaScript | `pip install` / `npm install` | Normal package |

## 4. Recommendation: use protobuf for the message format
Protobuf is the format Meshtastic itself uses. One `.proto` file generates readers and writers for every language.
Checked tonight with the team's own encoder:
- The pitch sentence: **12 bytes custom, 12 bytes protobuf.**
- Full report with GPS, time and counts: 25 bytes custom, 29 bytes protobuf.
- Below about 20 bytes the radio's own header (about 23 bytes) dominates airtime anyway, so 4 extra bytes barely matter.
- Old readers skip fields they don't know, so we can add fields later without breaking anyone.
We keep the team's good ideas: small codes and bit packing inside one number field. Old v1 packets still decode.

## 5. Portable, not just a demo
- **Word lists are data, not code.** Search and rescue first; later medical, fire, THW, water rescue, as new files.
- **Languages are files**: German, English first; Ukrainian, Turkish, Arabic next.
- **Any radio or network:** the engine doesn't care how bytes travel. Meshtastic now; the spec names the next ones.
- **Runs on:** Android phone, Mac/Linux/Windows laptop, Raspberry Pi gateway.
- **Conformance:** a shared file of test messages. Every library must give the same bytes. That is how a third party proves their version works.
- **Versioned spec and API** (v1, v2...), semantic version numbers for libraries.
- **Licence:** spec and libraries Apache-2.0 (companies like Dräger can embed it). The Android app is GPL-3.0, because the official Meshtastic SDK it uses is GPL-3.0.
- **Docs:** SPEC.md, API page, and "Integrate in 10 minutes" with copy-paste examples (Kotlin, Python, JS, REST, MQTT, TAK).

## 6. Tech choices (unchanged from the first draft, all checked)
- **Android app:** native Kotlin APK. Phone talks to the radio with the **official Meshtastic Kotlin SDK** (v0.2.0, Oct 2026; Bluetooth and USB). The old way of plugging into the Meshtastic app was removed in June 2026.
- **Speech offline:** Vosk (German 45 MB, English 40 MB) on the phone. Read-aloud with Android's built-in text-to-speech.
- **Understanding:** simple rules on the phone (always works, instant). Bigger AI on the laptop (whisper + qwen3:8b) for other languages when Wi-Fi is there. A small AI on the phone (Gemma 3 1B) only if it answers in under 4 seconds. **A person always checks the fields before sending.**
- **Map:** MapLibre with offline 3D terrain (tested offline tonight, Siebengebirge tiles about 9 MB).
- **Radio:** private channel, EU_868. Searchers' radios don't relay (fewer collisions); one radio placed high relays.
- **Honest numbers:** bytes are measured. Airtime is calculated with Meshtastic's real overhead: about 2x shorter for a single report, up to 7x for 10 GPS points packed in one message.

## 7. What must work first (by Wed 17:00)
1. Spec + Kotlin and Python libraries give identical bytes for all test messages.
2. Android app: speak or type, check fields, send over the real radio. Actions tab with Accept and Done.
3. Gateway on the laptop: receives over the USB radio, REST + WebSocket API, dashboard built only on that API.
4. Accept goes back to the phone over the radio.
5. Mayday goes first in the queue.
6. Two connectors working live: **readable text** (stock Meshtastic apps can read it) and **TAK markers** or **MQTT**.
7. Outdoor range test with real numbers.
**Next (written in the spec, not built by Thursday):** C library and Meshtastic firmware module (decoded text on the radio's own screen), an official Meshtastic port number (GroupAlarm got 112; port 38 "acknowledged paging" is reserved but has no format yet, and it matches our Accept loop), CAP and EMSI export for authorities, MeshCore/LoRaWAN/TETRA SDS/satellite adapters, iOS.

## 7a. Overnight: build on a realistic simulation, connect real hardware Wednesday morning
Decided Wed ~00:45: no hardware plugged in tonight.
- **Real Meshtastic firmware in Docker** (official `meshtastic/meshtasticd` image, updated 24 Nov, runs on this M1). Two or three simulated nodes. Our app and gateway talk the **exact same protocol** as with a real radio; only the radio waves are simulated.
- **Android emulator on the Mac** runs our app and connects to a simulated node over the network. Agents screenshot **every screen**, so the team gets a screen gallery in the morning and designs can be reviewed without the phone.
- **The Python gateway** connects to another simulated node, the same way it will connect to the Heltec over USB.
- **What simulation can't catch** (the Wednesday morning list): Bluetooth pairing and PIN, Android permission pop-ups on the real phone, battery saver killing the app, real radio timing and lost packets.
- **Morning:** a ready "hardware check" script and checklist (about 10 min): phone + T1000-E + Heltec, the same tests as overnight, now on real hardware. We debug only what differs.

## 7b. Wednesday morning: the same tests on real radios
(The setup below was the original overnight idea; it is now the Wednesday morning check.)
```
 [Android phone] --USB cable (adb)--> [Mac] <--USB-- [Heltec V3]  (gateway radio)
        |                                                ^
        +--Bluetooth--> [T1000-E or T-Watch] ~~LoRa~~~~~~+
```
1. **Tonight, 15 min, a human:** the official Meshtastic app on the phone connects to the T1000-E, and a text reaches the Heltec on the Mac. If the official app works, the official SDK (same protocol, same team) works too.
2. **First agent job, before any screens:** a tiny "hello radio" app with the SDK. Connect over Bluetooth, send one packet, receive one, on the real phone. If this fails, we know tonight, not at Wednesday 13:00.
3. **Only then** the full app is built on that proven radio layer.
4. **Real-hardware test suite**, runs on demand and every 30 min overnight: connect, send, receive, Accept back, radio switched off and on, Bluetooth drop and reconnect, app in background and screen locked, app restart (messages and tasks are saved), duplicate packets, timeout shows "delivery unknown", airtime limit shows "send again in N min".
5. **Soak test:** one real message every few minutes all night (far below the legal 10% airtime). Morning brief shows sent, delivered, lost, round-trip times.
6. **All our node types:** Heltec V3, T1000-E, T-Watch, with their firmware versions written down.
7. **Wednesday:** outdoor range test covers distance and real terrain, which the desk can't.
Honest limit: "works on our phone and our radios, proven by tests", not "every phone on earth".

## 8. Overnight jobs for Claude agents
Work in a Git copy of `docs/RescueCodec`; the original stays untouched. Nothing is pushed anywhere.
| # | Job | Done when |
|---|---|---|
| J0 | Downloads while internet works: Android build tools + Java, Gradle parts, Vosk models, protoc, MapLibre + terrain tiles, Mosquitto (MQTT), optional Ollama + qwen3:8b | An empty Android app builds. Everything opens with Wi-Fi off |
| J1 | Spec: `rescuecodec.proto` with all search-and-rescue messages (found, GPS track batch, sector searched, request stretcher/helicopter, hazard, Mayday, Accept/Done), word lists, SPEC.md, 40+ test messages | Test-message file exists; size table per message type |
| J2 | Python library on the spec (old v1 still decodes) | Old 17 tests + all test messages pass |
| J3 | Kotlin/JVM library, Apache-2.0 | Same bytes as Python for every test message |
| J4 | TypeScript library | Same bytes for every test message |
| J5 | Gateway: REST + WebSocket API with OpenAPI page, Meshtastic adapter (USB/Wi-Fi) + simulator, MQTT JSON, GeoJSON, readable-text line | End-to-end test on the simulator; API page opens offline |
| J6 | TAK connector (CoT markers and alerts) | Unit tests match official CoT examples |
| J7 | Android app: Report, Actions, Messages, Stats tabs; Kotlin library; Meshtastic SDK with its fake radio; Intent API for other apps | APK builds; emulator test goes open, accepted, done |
| J8 | Rules (Kotlin, same test sentences as Python), Vosk speech in, read-aloud out, priority queue (Mayday first, airtime budget) | 30+ sentences give the same fields as Python; Mayday-first test passes |
| J9 | Dashboard v2 built only on the public API: cards, Actions, Mayday banner, offline map, stats marked measured/estimated; fix the airtime estimator | Works with Wi-Fi off; numbers match the calculation |
| J10 | Docs: README, architecture drawing, "Integrate in 10 minutes", LICENSE files | A new developer can send a test message by following it |
Morning brief at 07:30: what works, what doesn't, what to test first.

## 9. Wednesday, humans, in this order
1. 08:30: charge everything, note firmware versions. Don't change Dräger's radios without asking.
2. Private channel on our radios (EU_868, LONG_FAST, hop limit 3), shared by QR.
3. Radio to radio: text from the phone to the Heltec on the laptop and back; then one RescueCodec packet. Note bytes and signal.
4. Our APK on the phone: `adb install`, pair with the radio, send a report, Accept comes back.
5. **10:00 team registration email.**
6. 11:00 booths. **Dräger:** what format does your app read from the mesh? Would you embed a Kotlin library? Can our messages show in "Einsatznotizen"? How many radios until Thu 12:00? **FBI GmbH:** does MissionBuddies (their command app) take REST, MQTT or TAK? Drone or 5G case?
7. **13:00 gate:** if the APK can't talk to the radio, plan B (phone opens the gateway page, laptop sends over USB) and stop debugging Bluetooth.
8. 13:30 to 15:30: outdoor range test (2 people), before dark. 17:00: everything from section 7 works with Wi-Fi off, 3 times in a row.
9. Evening: story, slides, backup video, rehearsal.

## 10. Decisions for the team
1. **Protobuf for the message format?** **Decided: yes** (Denis, Wed 01:00). Code lives in https://github.com/denishotii/RescueCodec; agents commit locally per feature, Denis pushes.
2. **Licence:** Apache-2.0 for spec and libraries? Recommended, so others can embed it.
3. **Which 2 connectors do we show live?** Recommended: readable text (works with stock apps and probably Dräger's) + TAK markers.
4. **APK:** yes, with the 13:00 gate.

## 11. Open questions
1. Which laptop runs the gateway on stage? Which Android phone (model)?
2. Does Dräger's app accept anything other than text from the mesh?
3. Does anyone on the team know TAK or MQTT already?
4. Where should the code live (team GitHub repo, name, public or private for now)?

Sources and the unverified points are listed at the end of `plan-v2-first-draft.md`.
