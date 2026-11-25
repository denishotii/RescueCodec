# End-to-end report (simulator and Android emulator)

Wed 25 Nov 2026, job `integrate`. Simulator and emulator only. No USB device, no Bluetooth radio, no real radio was touched.

## What ran

```
Android emulator (app) --TCP 10.0.2.2:4403--> SIM-FIELD ~~ UDP mesh ~~ SIM-BASE <--TCP 4404-- gateway --> dashboard
                                                                                         \--> MQTT (Mosquitto), TAK CoT (UDP)
```

- 3 real Meshtastic firmware nodes (`meshtasticd 2.7.26`) in Docker, EU_868, LONG_FAST, private channel, PRIVATE_APP port 256.
- Gateway on SIM-BASE, serving the built dashboard (map tiles from `static/map/`, no internet).
- One command runs the whole scenario and stops everything at the end: `scripts/e2e-full.sh --runs 3`.
- Screenshots: `.overnight/gallery/e2e/` (app by `adb exec-out screencap`, dashboard by `playwright-cli`). Open `.overnight/gallery/index.html`.
- Raw numbers: `.overnight/gallery/e2e/results.json`.

## Result: 3 runs of the full scenario, all passed

| Step | What was checked | Run 1 | Run 2 | Run 3 |
|---|---|---|---|---|
| 1 | Pitch sentence with real umlauts typed into the app, fields checked (child, conscious, hypothermia, fracture, stretcher, 2 more helpers), Send | pass | pass | pass |
| 2 | Card on the dashboard in English and German, map pin, stats, MQTT topics, TAK marker, readable line | pass | pass | pass |
| 3 | Accept on the dashboard, app task turns accepted. Done, app task turns done. MQTT and CoT follow | pass | pass | pass |
| 4 | Radio away, 5 track batches and then SOS queued in the app. After reconnect the Mayday reaches the gateway first, then all 5 tracks | pass | pass | pass |
| 5 | A sentence the rules cannot code goes out as plain text and shows as readable text on the dashboard | pass | pass | pass |
| 6 | Gateway restarted: tasks, messages and stats are still there, dashboard reconnects. App in the background for 8 s and back: task list kept, link up. A new report afterwards still arrives | pass | pass | pass |

The `android-e2e.sh` instrumented test (app, SIM-FIELD, Python peer on SIM-BASE) also passes after the changes (22 identical bytes, Accept and Done both ways).

## Round-trip times (simulator, so no LoRa airtime)

| Measure | Run 1 | Run 2 | Run 3 | Average |
|---|---|---|---|---|
| Tap Send in the app to the task on the gateway | 2.31 s | 2.65 s | 2.48 s | 2.5 s |
| Gateway has it to the card on the dashboard | 1.28 s | 0.74 s | 0.74 s | 0.9 s |
| Click Confirm on Accept to the app task shows accepted | 3.51 s | 3.46 s | 3.55 s | 3.5 s |
| Click Done to the app task shows done | 4.77 s | 4.82 s | 4.84 s | 4.8 s |
| Radio comes back to Mayday and 5 tracks at the gateway | 6.6 s | 4.6 s | 5.6 s | 5.6 s |
| Gateway restart to radio connected | 1.1 s | 1.1 s | 1.1 s | 1.1 s |
| Whole scenario | 146 s | 144 s | 145 s | 145 s |

How to read these: the app side is read with `uiautomator` about every 0.7 s, and each dashboard click is a `playwright-cli` call (about 1 s), so each number is good to about plus or minus 1.5 s. They are not radio times. On real LONG_FAST radios add about 0.6 s of airtime per packet and any hop.

## Bytes per message (measured on the wire)

| Message | Plain text | Coded | Note |
|---|---|---|---|
| Pitch sentence, found with GPS | 105 B | 24 B (23 B when the id is below 128) | 4.4 times smaller. The dashboard shows 5.1 times against the readable English line |
| Mayday from SOS with GPS | | 16 B | |
| Track batch of 4 GPS points | | 19 B | |
| Accept with team and ETA | | 26 B | |
| Done | | 24 B | |
| Unsupported sentence as text, with GPS | 27 to 43 B | 42 to 58 B | Larger than the sentence: 15 B are the position and the message wrapper. See open issues |

## Handover items done

- **Kotlin extractor parity.** `Extractor.kt` is now a port of the Python extractor, not a second design. Both read `spec/vocab/sar.yaml`. Score on `spec/vectors/extraction.json`: **Python 70/70, Kotlin 70/70** (the file grew from 54 to 70 cases: person-noun found cue, breathing none is critical, confused beats conscious, one filler word inside a phrase, number plus noun as count or responders, avalanche and landslide words, two persons in two states). The two engines also gave identical results on 900 random sentences made from fragments, when the port was finished. All five rules from the handover are covered by vectors.
- **Kotlin airtime limits.** Per type, like Python: Mayday and Ack never held, Found, Request, Hazard 100 percent, Sector 90 percent, Track and Text 70 percent. Order is type rank, then priority, then first in first out. A held message no longer blocks one that still fits.
- **Dashboard on the real gateway.** Tested against the real gateway (not the mock) with `npm run build` and the map tiles copied to `rescuecodec/gateway/static/map/`. Range requests for the tiles work. A 404 for `config.json` in the console is gone.
- **"No relay confirmed".** A broadcast that ends `failed` with `max_retransmit` shows as "No relay confirmed" ("Kein Relais bestätigt") on a neutral chip. The stats count it separately. The API keeps its status. See `run3-18b-dashboard-no-relay-confirmed.png`.

## Bugs found and fixed during integration

1. **Packets lost when the link dies silently.** When SIM-FIELD went away, the app still accepted packets for 3 to 6 seconds and then marked them "not confirmed". 2 of 5 track batches were lost this way. The app now sends such a message once more after the reconnect. If the first copy had gone out, the gateway drops the copy (same sender and id, or same bytes). After this, 5 of 5 tracks arrived in all 3 runs.
2. **Gateway stats restarted from zero after a restart.** "Bytes on air" showed 0 B while "Airtime saved" showed 9.4 s. Counters and the airtime budget are now rebuilt from the stored messages. Test added.
3. **Dashboard asked for `config.json` and got a 404.** Added an empty `config.json`.
4. **The pitch could not be typed with umlauts through adb.** Added a debug-only intent extra `report_text_b64` (debug builds only).
5. **Harness lessons that look like bugs but are not:** `sim/up.sh` configures the nodes over their TCP API, a node serves one API client, so running it kicks the app and the gateway off the radio. `adb emu geo fix` does nothing on the headless emulator, so GPS comes from a test location provider. The gateway drops a text or track with the same sender and bytes seen in the last 10 minutes (spec section 14), so the scenario uses different sentences and points per run.

## Flaky, not solved

- **One cold start failed.** Once, on a cold start of the whole stack (emulator and simulator started together), the app said "No radio" a minute after it had connected, did not reconnect by itself, and the first report never left. The same stack started again, and a later run (after all code changes, 1 run) was clean. I could not catch the log. Suspects: the firmware still starting up when the app connected, or the app's reconnect loop ending in an idle state. Worth a look with `adb logcat -s RescueCodec` if it shows up on a real radio.
- Timings above are good to about 1.5 s (see above).

## Open issues

- **Same text twice is dropped.** A message without an id (text, track, sector) with the same bytes from the same sender within 10 minutes is a duplicate. A person who sends "Hilfe" twice loses the second one. Spec section 14 says so. Consider an id or a counter for text.
- **Text fallback is bigger than the sentence** (position and wrapper). The app shows "36 bytes as text, 51 bytes sent". True, but it looks bad on stage. Consider leaving the position out of the byte counter or sending text without GPS.
- **No unit test for the one-time resend** (bug 1). The SDK's fake radio has no easy way to drop a link after "sent". It is covered by the emulator run only.
- **Dashboard "Delivered 0 of N"** counts only delivered, so on the simulator it stays at 0. The new wording tells the viewer why.
- Not tested here, and cannot be on the simulator: Bluetooth pairing, permission pop-ups on a real phone, battery saver, real timing and loss, USB serial on the Heltec. These are the Wednesday morning checks.
- Instrumented tests wipe the app data, so run `scripts/e2e-full.sh` with `--fresh` afterwards.

## Test suites (all green after the changes)

| Suite | Result |
|---|---|
| Python `pytest` | 218 passed, 870 subtests |
| Gradle `test` (codec, engine, app unit tests, debug and release) | 170 tests, 0 failures |
| Gradle `connectedDebugAndroidTest` plus Python peer (`scripts/android-e2e.sh`) | pass |
| TypeScript library `npm test` | 215 passed |
| Dashboard `tsc --noEmit` and `vitest` | 26 passed |
| Full scenario `scripts/e2e-full.sh --runs 3` | 3 of 3 passed |

## Re-run it

```bash
cd android && ./gradlew :app:assembleDebug     # debug APK
cd ../dashboard && npm run build               # dashboard and map tiles into the gateway
npm i -g @playwright/cli                       # or set PLAYWRIGHT_CLI=/path/to/playwright-cli
cd .. && scripts/e2e-full.sh --runs 3 --fresh  # starts and stops simulator, MQTT, emulator and gateway
python3 scripts/build_gallery.py               # rebuilds .overnight/gallery/index.html
```
