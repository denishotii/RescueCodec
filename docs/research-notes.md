# RescueCodec v2: plan to Thursday

Written Tue 24 Nov, ~23:45. Users: outdoor rescue teams and volunteer search parties (Bergwacht, DLRG, dog teams, THW/DRK, spontaneous helpers). Built around the team's 7 goals (G1 to G7).

## 1. Goal
A volunteer in a dead zone speaks one sentence. About 20 bytes cross the mesh. The command post reads it in German and presses Accept, and the volunteer sees the Accept. All of it live on stage.

## 2. The demo (about 3 of the 4 minutes)
Story: a missing 8-year-old in the Siebengebirge forest at dusk. 120 volunteers, no phone signal in the valley.
1. **0:00-0:30 Map.** The projector shows the real Siebengebirge terrain. Press T and it tilts to 3D. Two shaded coverage areas: relay in the valley (small) and relay on the hilltop (big). "Hills block radio. Height wins."
2. **0:30-1:10 Speak.** The presenter taps the mic: "Kind gefunden, ansprechbar, stark unterkühlt, Knöchel verletzt. Wir brauchen eine Trage und zwei Leute." The phone shows the fields: found, conscious, hypothermia, leg injury, stretcher, 2 helpers, GPS. The presenter checks them and taps Send. Counter: "105 bytes spoken, 21 bytes sent" (live value).
3. **1:10-1:40 Receive.** A card appears on the dashboard in German, with a pin on the map. The Mac reads it aloud. Stats line: "0.56 s on air instead of 1.34 s (estimated)", "RSSI -45 dBm (measured)".
4. **1:40-2:10 Accept.** The commander clicks Accept, "Trupp 3, 15 min". 8 bytes go back. The phone vibrates, turns green and says "Angenommen". The Actions tab moves the task from open to accepted.
5. **2:10-2:40 Mayday.** A teammate holds the SOS button on the phone for 2 seconds. A red banner jumps above everything on the dashboard. "Mayday goes first in the queue."
6. **2:40-3:30 Proof.** A 20-second clip from Wednesday's field test: distance, packets delivered, signal. Then one number: "On the long-range setting, one radio may send 77 text reports per hour, or 202 RescueCodec reports." Close: "Transmit meaning, not words."

## 3. Setup on stage
```
 VOLUNTEER (field)                                COMMAND POST (Mac on stage)

 [Android phone] --Bluetooth-- [T1000-E] ~~LoRa 868~~ [Heltec V3] --USB-- [Mac] --HDMI--> projector
  RescueCodec APK               radio     report ~20 B ->  radio           FastAPI + dashboard
  speak > check > Send                    <- Accept 8 B                    map, Accept button
        |                                                                    ^
        +-------- Wi-Fi from a travel router ("5G at base camp", optional) --+
                  used first when it works; mesh when it does not
```

## 4. Tech choices
| Part | Choice | Why | Plan B if it fails |
|---|---|---|---|
| Phone app | Native Kotlin APK | Team goal. Offline voice and background Bluetooth need native code. | Phone opens the Mac's web page; the Mac sends via its USB radio (Python, works today). |
| Phone to radio | Official **meshtastic-sdk 0.2.0** (Kotlin, Bluetooth). `send { data(PRIVATE_APP, bytes) }` | Binding to the Meshtastic app ("AIDL") was **removed in app 2.8.0** (June 2026). The SDK comes from the Meshtastic team and has a fake radio for tests. | USB-OTG cable (SDK serial module, snapshot build), then plan B above. |
| Codec | Python (exists) + pure Kotlin module | Same bytes. No Android code inside, so Dräger can reuse it. | Python only, via the Mac. |
| Port | PRIVATE_APP (256) | Works with every library. Stock nodes relay it. | Later: a registered port (64 to 127), as GroupAlarm got 112. |
| Speech in | **Vosk** German 45 MB, English 40 MB, inside the APK | Fully offline. Android's own recognizer often fails offline; Chrome on Android has no offline speech. | Typing and preset phrase buttons. |
| Extraction | Kotlin rules on the phone | Instant, offline, predictable. | None; this is the floor. |
| AI booster | Mac: whisper.cpp + **qwen3:8b** via Wi-Fi. Phone: Gemma 3 1B (LiteRT-LM) only if fast. | qwen3 covers Ukrainian, Turkish, Arabic; llama3 is English-focused. | Rules result after a 4 s timeout. |
| Speech out | Android TextToSpeech (German), Mac `say -v Anna` | German built from fields by template, not translated. | Text only. |
| Dashboard | Extend the FastAPI page, poll every 1 s | No new build tool. Today's page does not refresh by itself. | Plain card list. |
| Map / 3D | **MapLibre 6**, local terrain tiles (Siebengebirge, ~9 MB) | Rendered offline tonight with all outside hosts blocked. | Flat map, then no map. |
| Radio | Private channel, LONG_FAST on stage. Searchers CLIENT_MUTE (never relay), hilltop ROUTER. | Fast on stage. Fewer relays leave more room on the channel. | Re-share the QR, reset our own nodes. |

**Numbers we can say (honest).** Bytes are measured. Airtime is calculated with Meshtastic's real overhead (16-byte header + 7-byte wrapper + 16-symbol preamble), not measured.
- The message is 7.7x smaller (92 to 12 bytes), but airtime is only **2.1x to 2.5x** shorter. Example on LONG_FAST: 0.52 s instead of 1.09 s.
- Below ~20 bytes, more squeezing barely helps; the radio's own header dominates. The big win is **batching**: 10 GPS track points take 5.2 s as 10 packets, or 0.7 s as one packet.
- Per radio, at the legal 10% duty cycle on LONG_MODERATE: 77 text reports with GPS per hour, or 202 RescueCodec reports.
- Whole mesh, 200 volunteers on LONG_FAST: roughly one report each every 21 minutes, against 44 minutes with text (rough estimate, many assumptions).
- Range: the record is 254 km on LONG_FAST, mountain to mountain. Forest reports say 2 to 5 km. Height beats slower settings: the slowest preset buys ~1.5x range for 5x airtime.
- Volunteer kit: SenseCAP T1000-E is about 47 EUR, ready to use with GPS. A Heltec V3 board is about 20 USD (no case or battery). The band is licence-free (869.4 to 869.65 MHz, 10% duty cycle).

## 5. Decisions for the team
1. **APK or web app?** APK. The Meshtastic SDK exists, and offline voice is impossible in Chrome on Android. Cost: an Android toolchain install tonight, plus the risk of Bluetooth pairing on Wednesday morning. Gate at Wed 13:00 (see 8).
2. **How much 3D?** A real terrain map, flat by default, with one 20-second tilt to 3D. Drop the Three.js building, because indoor floors are no longer our story. About 10 h of agent work, mostly overnight.
3. **Where does the AI run?** Speech and rules on the phone, always offline. The Mac LLM handles other languages when Wi-Fi is up. A phone LLM only if it answers in under 4 s on the real phone. A person always confirms before sending.
4. **Who receives on stage?** The Mac dashboard, as the command post. We have one Android phone. If a teammate has an Android phone, phone-to-phone is a bonus.
5. **Broadcast or direct message?** Broadcast on our private channel, so the whole search learns "child found" and who took it. Direct messages may add 12 bytes of encryption, and our Accept is the real confirmation anyway.

## 6. Overnight jobs for Claude agents (no hardware)
Rules: work in a new git copy of `docs/RescueCodec` (leave the original untouched). Never open USB devices or transmit.

| # | Goal | Job | Done when |
|---|---|---|---|
| J0 | all | **Downloads (need internet):** Android command-line tools + adb (this Mac has neither), Gradle deps, Vosk models, whisper small + medium, `qwen3:8b` on the stage Mac, MapLibre, terrain tiles, Gemma 3 1B | An empty app builds. Every file listed in `OFFLINE.md` opens with Wi-Fi off. |
| J1 | G1, G3 | Python message types: found (with condition), track (10 points per packet), sector cleared, request (stretcher, helicopter), hazard, Mayday, confirm. Fully German text. `SPEC.md` with version byte. | Tests pass. `vectors.json` has 40+ cases (fields to hex). Every type except track is 25 bytes or less with GPS. |
| J2 | G3 | Kotlin codec module (pure JVM, Apache-2.0) | All vectors give identical hex both ways. |
| J3 | G6 | App: Report, Actions, Messages, Stats tabs, on the SDK's fake radio | Emulator test goes open, accepted, completed. |
| J4 | G1, G3 | Radio layer with meshtastic-sdk (Bluetooth, USB as option) | Fake-radio test sends and receives a port-256 packet. |
| J5 | G2 | Kotlin rules extractor, de/en, search-and-rescue words | 30+ shared sentences give the same fields as Python. |
| J6 | G7 | Vosk in, TextToSpeech out | A German WAV gives the key words; a report is read aloud. |
| J7 | G5 | Send queue: Mayday > found/request > hazard > sector > track, with Meshtastic priority ALERT/HIGH/BACKGROUND. Hold routine items at the airtime limit. | Test: 5 track points + 1 Mayday, Mayday goes first. |
| J8 | G4 | Dashboard v2: cards, Accept, Mayday banner, map, stats with a "measured"/"estimated" tag on every number. Fix the airtime estimator (uses 8 preamble symbols and 16 bytes overhead; real: 16 and 23). | Renders with outside hosts blocked; numbers match the script. |
| J9 | G2 | Mac AI path: whisper-server (`-l auto`) + qwen3:8b with JSON schema | A German and a Turkish clip give fields in under 5 s. |
| J10 | G2 | Phone LLM tried on the Mac first (LiteRT-LM runs on the JVM too): Gemma 3 1B on the 42 test reports | Accuracy and seconds per report written down. |
| J11 | G4 | Site Planner coverage for 2 relay spots (valley, hilltop). Needs a browser: a human or browser agent tonight. | Two GeoJSON layers show on the offline map. |

## 7. Wednesday morning, humans, in this order
1. **08:30 at the hotel.** Charge everything. Write down each node's firmware version. Do not change Dräger's nodes without asking.
2. **Private channel.** In the official Meshtastic app on the Heltec: region EU_868, LONG_FAST, hop limit 3. Share it by QR to our T1000-E and T-Watch ("Replace" also copies region and preset).
3. **Radio to radio.** Send a text from the phone (official app + T1000-E) to the Heltec on the Mac, and back. Then let Python send one port-256 packet. Note the hex, RSSI and SNR.
4. **Phone to radio with our APK.** Install it with `adb install`. Disconnect the official app from the T1000-E first (expect only one Bluetooth client at a time). Pair in Android settings and check the PIN mode in the node's Bluetooth settings. Send one report; the Mac shows it; Accept comes back. Note bytes and seconds.
5. **Voice in the hall.** Airplane mode with Bluetooth on: speak 5 test sentences. Note the phone model and Android version.
6. **10:00 Team registration email.** No email, no pitch.
7. **11:00 Booths.** Dräger: how many nodes until Thu 12:00, one as a relay, can Mayday show in their app? FBI: drone or 5G case, and may we quote them?

## 8. Timeline to Thu 10:00
| When | What | Checkpoint |
|---|---|---|
| Tue night | J0 to J11 | Morning report: what passed |
| Wed 08:30-10:00 | Section 7, steps 1 to 5 | Radio to radio works |
| **Wed 13:00** | **Gate:** APK round trip works? If not, switch to plan B (phone uses the Mac page) and stop debugging Bluetooth | Decision made |
| Wed 13:30-15:30 | **Field test**, 2 people, before dark (~16:30). Heltec + laptop at a high fixed spot by the Rhine (Rheinpark), phone + T1000-E walks away. Range Test module every 30 s, plus one RescueCodec report every ~300 m. Film the phone screen. Turn Range Test off afterwards. | Table: distance, RSSI, SNR, delivered/sent |
| Wed 13:30-17:00 | The other 2 people: dashboard + voice on the stage setup | |
| **Wed 17:00** | The full demo from section 2, with Wi-Fi off | Works 3 times in a row |
| Wed 19:30 | Feature freeze | |
| Wed evening | Record the backup video, make slides, rehearse 3 times to 3:45 | Video on desktop + USB stick |
| Thu 09:00-10:00 | 2 rehearsals with Wi-Fi off. Few radio sends in this hour (airtime limit). | 10:00 tech check |

**Must work live (the floor):** phone to radio to radio to dashboard, Accept back to the phone, honest byte and airtime numbers, Mayday first, field test numbers, backup video.
**Cut list, drop first to last:** drone relay, phone LLM, live multilingual (keep as a 10 s clip), 5G/Wi-Fi switching, 3D tilt and coverage shading (keep the flat map), track and sector messages, voice output, voice input (keep typing and preset buttons).

## 9. Risks and plan B
| Risk | Early sign | Plan B |
|---|---|---|
| APK cannot talk to the radio | No connection by Wed 13:00 | Phone as a web page to the Mac; the Mac sends via USB (Python path) |
| Radios do not hear each other | Step 3 fails | Same preset and region on all nodes; re-share the QR; factory reset our own nodes only |
| Airtime limit blocks sends on stage | The node answers "send again in N min" | Few sends in the last hour; a spare node already on the channel |
| Voice fails in hall noise | Wrong words in step 5 | Preset phrase buttons; a headset mic |
| Phone uses mobile data instead of Wi-Fi | Mac page does not load | Turn mobile data off |
| Projector or 3D is slow | Stutter in rehearsal | Flat map (key 2) |
| Anything else | | **Backup video** of the full demo, switch within 5 s |

## 10. Open questions for the team
1. Which Mac goes on stage? This one (M1, 16 GB) has no Ollama and no Android SDK.
2. Does a teammate have an Android phone? Which model is our demo phone?
3. Does anyone speak Ukrainian, Turkish or Arabic for one live report?
4. Demo area: Siebengebirge, or a real past search near Köln?
5. Pitch in English or German?
6. Can FBI fly the drone with a node on Wednesday (pilot, permits)? If not, a node on a pole or a roof is the height demo.

## Sources and what is not verified
- Meshtastic: Android PRs #5586 and #6196 (AIDL removed); github.com/meshtastic/meshtastic-sdk; protobufs `portnums.proto` (port rules, GroupAlarm 112, PAGING_APP 38 reserved Sep 2026 for "acknowledged paging", no format yet) and `mesh.proto` (priority); firmware `MeshRadio.h`, `RadioInterface.cpp`, `MeshPacketQueue.cpp`; meshtastic.org docs: radio-settings, lora, device roles, range-tests, range-test module, FAQ (channel QR).
- Speech and AI: alphacephei.com/vosk/models; Whisper paper arxiv.org/abs/2212.04356; developer.android.com SpeechRecognizer; github.com/google-ai-edge/LiteRT-LM; qwenlm.github.io/blog/qwen3; docs.ollama.com structured outputs.
- Map: maplibre.org; mapterhorn.com/data-access; github.com/meshtastic/meshtastic-site-planner. Prices: heltec.org, seeedstudio.com, tinytronics.nl (Nov 2026 snapshots).
- **Not verified:** speed on a mid-range phone, forest range, our nodes' Bluetooth PIN mode, one-client-at-a-time, whether a reboot resets the airtime counter, Site Planner export (code read, not run), all hour estimates.
