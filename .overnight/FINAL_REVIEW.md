# Final review: findings and what was done

Wed 25 Nov 2026, job `final-fixes`. Sources: `codex` (code review) and `real-devices` (review of the path to real radios). Simulator, emulator and unit tests only. No real device was touched.

Status words: **fixed**, **fixed (docs)** when the answer is a written decision, **not fixed** with the reason.

## Blockers and majors

| # | Issue | Source | Result | What was done |
|---|---|---|---|---|
| 1 | Bluetooth transport skips the SDK's Android setup (23-byte MTU, slow handshake) | real-devices, blocker | **fixed** | `RadioTransports.kt` uses the SDK factory `BleTransport(address)` and closes it with `disconnect()` and `shutdown()`. `RadioClient.skipNodeDb(true)` added for Bluetooth radios only (it hides the radio's own name, which the TCP e2e shows). Cannot be proven without a real radio: step 4 of `HARDWARE_CHECK.md`. |
| 2 | WebSocket accepts any Origin, no auth, POST endpoints unprotected, no rate limit | codex, major | **fixed** | Origin check before accept. Optional bearer token for REST, WebSocket (`?token=`) and dashboard. The gateway refuses a non-loopback `--host` without `--token`. 30 sends per minute (`--tx-per-minute`). Tests: `tests/test_gateway_security.py`. |
| 3 | Gateway radio thread can die for good (unguarded callbacks, `_on_receive`, `packet_from_dict`) | codex, major | **fixed** | Connect and run are split. Every listener call is guarded and logged. `packet_from_dict` converts channel, rssi, snr and id defensively. Tests: failing listeners and a bad packet dict. |
| 4 | `startForeground` can throw (Android 12+ sticky restart, Android 14 type rules), `graph.start()` ran first | codex major, real-devices minor | **fixed** | `RadioService` calls `startForeground` first with a minimal notification and catches failure. On refusal it keeps the link inside the process and stops the service. `RadioService.start()` and `stop()` never throw. A BLE target without Nearby devices skips the service and shows "permission missing" at once. Not run on a device: `adb shell am kill` check is step 7 of `HARDWARE_CHECK.md`. |
| 5 | Missing Bluetooth permission and Bluetooth off retried with backoff forever | codex, major | **fixed** | `SecurityException` (also wrapped) and `RadioUnavailableException` end the loop with `retryAtMs = null`. Bluetooth off waits and resumes without a retry counter. The notification opens the Radio screen when the person must act. Tests added. |
| 6 | Gateway sends on channel 0, accepts port 256 on every channel (also the app) | real-devices, major (and codex minor) | **fixed** | Gateway: channel resolved by name, else the only channel with its own key, never the default key, `--channel N` forces. `/v2/health` shows `channel_index`, `channel_error`, `other_channel`. Sending is refused (503) without a safe channel. Packets from other channels are dropped. App: drops port 256 packets not on the private channel (`rx_other_channel`). Tests on both sides. |
| 7 | Reconnect not robust (4 holes, "No radio" on cold start) | real-devices, major | **fixed (cause not proven)** | Generation counter, immediate `Disconnected`, scan ignored while a loop runs, SDK cancellation retried, `Throwable` caught, `wantConnected` saved, activity restart on every create (no `savedInstanceState` gate), service watchdog (idle more than 5 s then `connectLast()`), every state change logged under tag `RescueCodec`. Unit tests for scan, late disconnect and cancellation. The original cold-start failure was never caught on a log, so there is no proof it is the same bug. |
| 8 | Nothing tells the volunteer that only one app may talk to the radio. Raw exception texts | real-devices, major | **fixed** | Line on the Radio screen (EN and DE) and in the checklist. `friendlyError()` maps permission, Bluetooth off, GATT 133, pairing/PIN and stale bond to plain text. Unit test. |
| 9 | Morning hardware checklist and script missing | real-devices, major | **fixed** | `HARDWARE_CHECK.md` (8 numbered steps with pass criteria and a "if it fails" table) and `hil/check_gateway.py` (checks a running gateway, transmits only with `--send` or `--wait-in`; tried against the emulator gateway only). |
| 10 | Offline map did not cover Koeln | real-devices, major | **fixed** | `basemap.pmtiles` (30 MB) and `terrain.pmtiles` (8 MB) re-extracted for 6.80,50.60,7.35,51.00. Fetch script, manifest and `OFFLINE.md` updated. Dashboard rebuilt, map copied to `static/map`. The z14 terrain detail stays on the Siebengebirge. |
| 11 | Repeated identical text, track or sector inside 10 minutes dropped | real-devices, major | **fixed** | Gateway and app give every message except an Ack an id (`Envelope.id`, no wire change). SPEC 3.3 and 14 say senders SHOULD. Tests: same text twice is two messages (gateway API and app). |

## Minors

| # | Issue | Source | Result | What was done |
|---|---|---|---|---|
| 12 | `disconnect()` and `connect()` race on the state and `loopJob` | codex | **fixed** | Same change as 7: generation counter and `@Synchronized` connect, disconnect, scan. |
| 13 | Wake lock without timeout, notification rebuilt every second | codex | **fixed** | Wake lock 10 minutes, renewed every 5 minutes while connected. Notification updates only when the text changes, countdown in 5 s steps. |
| 14 | Ack flood grows memory, pre-sent `done`, any sender's `done` closes the task | codex | **fixed (partly)** | Python `TaskBoard` and Kotlin `RequestTracker`: purge on every mutation, 500 placeholders, 30 minute TTL. Tests in both. **Not changed:** an early `done` still closes the task, because a real `done` can overtake a lost report. SPEC 14 says the channel key is the only trust boundary. |
| 15 | Same id with different content dropped; id counter after clearing app data | codex, real-devices | **fixed (docs)** | Decision in SPEC 3.3: same id is a retransmit, never an update. Counter keeps its random start. Hardware checklist tells the testers not to clear app data (or to start the gateway with a new `--db`). No `UPDATED` outcome. |
| 16 | `packet_from_dict`: rssi 0 dropped, hops without hopLimit, "delivered" wording | codex | **fixed (rssi, wording), not fixed (hops)** | rssi uses `is not None`. Dashboard, app, gateway README, OpenAPI and SPEC say "relayed by a neighbour". Hops: **not changed**. Protobuf JSON omits a zero `hopLimit`, so a missing `hopLimit` next to a `hopStart` really means 0 and `hops = hopStart` is right (existing test). |
| 17 | Send channel defaults to 0, no receive filter | codex | **fixed** | See 6. |
| 18 | Location services needed for BLE scan on Android 8 to 11, `neverForLocation` | codex, real-devices | **fixed (hint), open (device test)** | Radio screen shows "Turn on Location..." with a settings button on API 30 and lower. `neverForLocation` stays. The checklist says to remove it if radios do not appear on the test phone. Needs a real phone. |
| 19 | Cross-language edge cases: TS `detectVersion(empty)`, Kotlin `hasUnknownTopLevelField` bounds, no hostile vectors | codex | **fixed** | TS already returned 0 for empty input. Kotlin scan has a bounds check and catches runtime errors. 9 new vectors in `spec/tools/gen_vectors.py` and `spec/vectors/codec.json` (bit_31, a flag value wider than 32 bits, overlong UTF-8, varint cut inside a body, node `!ffffffff` valid, `!00000000` invalid). Existing bytes unchanged. Python, TypeScript and Kotlin pass all of them. One more case (an 11-byte varint) found a difference: Kotlin rejects it as `invalid_value`, Python and TypeScript as `malformed`. Every library rejects it, only the code differs, so the vector was not kept. Listed as open. |
| 20 | No unit test for the one-time resend; refusals and link resends share `attempts` | real-devices | **fixed** | Link resends and airtime refusals live in the same column in two places (hundreds and the low two digits), so a refusal no longer uses up the resend. Tests: one resend on a new link with the same bytes, second drop gives not confirmed with no third send, a delivered message is never resent, a refusal does not use the resend. |
| 21 | Reinstall restarts id counter, gateway dedupe rejects reused id | real-devices | **fixed (docs)** | See 15. |

## Not done

- Nothing was run against real Bluetooth or USB hardware. Items 1, 4, 7 and 18 are proven only by unit tests and the simulator. `HARDWARE_CHECK.md` lists the exact checks.
- The cold-start "No radio" report from the integration run was not reproduced, so item 7 closes four code holes, not a proven root cause. The log line `link: ...` under `adb logcat -s RescueCodec` will show it tomorrow.

## Test results


| Suite | Result |
|---|---|
| Python `pytest` (gateway, engine, codec, spec) | 249 passed (was 218 before this job), 898 subtests |
| TypeScript `vitest` in `ts/` | 223 passed (includes the 8 new codec vectors) |
| Dashboard `vitest` and `tsc --noEmit` | 26 passed, types ok, `npm run build` ok |
| Android `./gradlew test` (codec, engine, app) | 100 passed, 0 failed (was 62 before) |
| Android `:app:assembleDebug` | ok |
| `scripts/e2e-full.sh --runs 3 --fresh` (emulator app, 3 simulated radios, gateway, dashboard, MQTT, CoT) | **3 of 3 runs passed**, 144 to 151 s each |
| `scripts/android-e2e.sh` (instrumented tests on the emulator, Python peer) | passed: 22 identical bytes, Accept and Done both ways |
| `hil/check_gateway.py --send` against the in-memory emulator gateway | passed (never run against hardware) |

## Things found while re-running the end-to-end test

- **Duplicate connect restarted a good link.** At app start the activity, the service and the debug extra all asked for the same radio, and each call restarted the loop and dropped the link (seen in logcat: connected, then connecting again 7 s later). `connect()` now does nothing when the same radio is already connected or connecting. Test added.
- **`scripts/e2e_full.py` was out of date.** The design-polish job moved the radio text to a header chip ("Radio connected, SIM-FIELD"). The script looked for "Connected to SIM-FIELD" and timed out. It accepts both now. Use `--fresh` so the emulator starts in English.
- **A failed run left a gateway process behind** that held SIM-BASE and made `android-e2e.sh` fail with "peer died". It was killed by hand. Everything started here (emulator, containers, MQTT, gateway) is stopped now.
- **`playwright-cli` was not installed.** Installed with a scratch prefix (`npm i -g --prefix <scratch> @playwright/cli`), nothing else changed on the machine.
- The text fallback is bigger than the sentence (43 bytes of text, 61 bytes sent with GPS and the new id). The id adds 2 bytes. Still an open item from the integration report.

## Open

- Nothing ran on real Bluetooth, USB or a real phone. See `HARDWARE_CHECK.md`.
- Kotlin and Python/TypeScript answer an 11-byte varint with different error codes (both reject it).
- The cold-start "No radio" report is not reproduced, only hardened against.
