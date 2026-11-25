# RescueCodec morning brief (Wed 25 Nov 2026)

## 1. What works (proof)

Everything below ran on the simulator and the Android emulator. Nothing ran on real Bluetooth, USB or a real phone yet.

- Tests all green (FINAL_REVIEW.md): Python 249 passed, Android unit tests 100 passed, TypeScript 223 passed, dashboard 26 passed. Debug APK builds.
- Full simulator scenario passed 3 of 3 runs (E2E_REPORT.md). It covers: pitch sentence in the app, card on the dashboard in English and German, Accept and Done both ways, SOS Mayday with queued tracks after a radio drop, plain text fallback, and a gateway restart with no data loss.
- Message size: the pitch sentence goes from about 105 bytes of text to about 24 bytes on air.
- Gateway rejects unsafe sends (no private channel) and refuses to listen on public LongFast.

## 2. What does not work or is risky

- Real Bluetooth pairing, USB serial to the Heltec, permissions and battery saver are untested. This is the main risk.
- A cold start once showed "No radio" and did not reconnect. Not reproduced. Watch `adb logcat -s RescueCodec`.
- Voice input: no speech model in the default APK. Live microphone not tested.
- Same text sent twice is now allowed, but the text fallback is larger than the sentence. Looks bad on stage.
- Simulator has no radio range. Delivery shows "No relay confirmed" on the simulator. The Accept is the real proof.
- Offline map covers Koeln, but dashboard was never tested on a projector.
- Android 8 to 11 phones may need Location on for Bluetooth scan.

## 3. First 5 things this morning, in order

1. Hardware check (about 10 min). Open RescueCodec/HARDWARE_CHECK.md and run `hil/preflight.sh` first. Fix every FAIL. Then follow steps 1 to 6. Stop at the first failure and read "If it fails".
2. Team registration email by 10:00. Send team name and full names of all members to the organiser (address in Slack #agenda). Without it, no pitch. If the hardware check is still running at 09:45, stop it and send the email first.
3. Run the simulator scenario once on this Mac: `scripts/e2e-full.sh --runs 1 --fresh`. It takes about 2.5 minutes. Confirm it is still green.
4. Open the gallery and pick the screenshots for the pitch and the backup video.
5. Agree the 4-minute pitch flow: pitch sentence, Accept, Done, SOS, plain text. Record a backup video of the simulator run in case the hardware fails on stage.

## 4. Where things are

- APK: `RescueCodec/dist/RescueCodec-debug.apk` (51 MB). Install: `. scripts/env.sh && adb install -r dist/RescueCodec-debug.apk`.
- Emulator: `scripts/emulator-up.sh`.
- Simulator (needs Docker): `sim/up.sh` starts three nodes on ports 4403 (SIM-FIELD), 4404 (SIM-BASE), 4405 (SIM-RELAY). Stop with `sim/down.sh`. Check with `.venv/bin/python sim/smoke.py`.
- Gateway (Heltec on USB): `.venv/bin/rescuecodec-gateway --serial auto`. For the simulator: `.venv/bin/rescuecodec-gateway --tcp 127.0.0.1:4404`.
- Dashboard: served by the gateway at http://127.0.0.1:8000/. Dev mode: `cd dashboard && RC_API=http://127.0.0.1:8000 npm run dev`.
- Screenshot gallery: `RescueCodec/.overnight/gallery/index.html`.

## 5. Top 10 decisions made overnight

1. Wire format is protobuf v2, decided by Denis. Old v1 packets still decode.
2. Private channel only: named RescueCodec, never public LongFast. Reports are refused on public channels.
3. Simulator uses real Meshtastic firmware 2.7.26 in Docker, with no extra bridge.
4. Delivery means a neighbour repeated the packet. Only a person's Accept counts as accepted.
5. Airtime budget per message type. Mayday and Accept are never held.
6. Every message gets an id, so repeated text is not dropped.
7. Gateway needs a token before it listens beyond localhost. API sends are limited to 30 per minute.
8. Bluetooth uses the SDK's own transport. Reconnect loop with backoff.
9. Licence: Apache-2.0 for the libraries and spec. GPL-3.0 for the Android app (the Meshtastic SDK is GPL).
10. English UI by default with a German toggle in Settings.

## 6. Open questions

- Full names of all team members for the registration email. Team members and skills are still TBD in the team notes.
- Who sends the email, and from which address? Organiser address is in Slack #agenda.
- Is the pitch in English only, or German for the audience?
- Which radio do we demo on stage: Heltec over USB, or T1000-E over Bluetooth?
- Do we run the backup video on stage or only keep it as a fallback?
