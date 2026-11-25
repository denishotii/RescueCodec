# Hardware check: Wednesday morning, about 10 minutes

Goal: prove the real path works with real radios. Everything so far ran on the simulator and the Android emulator. Nothing here is tested on real Bluetooth or USB yet. This is the same scenario as the simulator end-to-end test (`.overnight/E2E_REPORT.md`), done by hand.

```
Phone + RescueCodec app --Bluetooth--> T1000-E (or T-Watch) ~~ LoRa ~~ Heltec V3 --USB--> Mac: gateway + dashboard
```

You need:
- Heltec V3 and a USB data cable (not a charge-only cable), a Mac with this repo.
- An Android phone (Android 8 to 15) with USB debugging on, and a USB cable for it.
- A SenseCAP T1000-E and/or a LilyGO T-Watch S3. All radios on stock Meshtastic firmware 2.7 or 2.8.
- The debug APK: `dist/RescueCodec-debug.apk` (51 MB, gitignored, built from `android/`).
- The official Meshtastic app on the phone, for the one-time channel setup.

## 0. Check the Mac (30 seconds)

```bash
hil/preflight.sh
```

It only reads. It does not open any port and does not transmit. Fix every `FAIL` before you go on. A `WARN` for the phone or the USB radio is normal if you have not plugged them in yet.

Open two terminals and leave them open:

```bash
# terminal A: phone log
. scripts/env.sh && adb logcat -s RescueCodec
# terminal B: the gateway (step 2)
```

Every step has a pass criterion. If a step fails, stop and read "If it fails" at the bottom.

## 1. Make the private channel (once, 3 minutes)

1. In the official Meshtastic app, connect to the Heltec. Set region **EU_868** and modem preset **LONG_FAST**.
2. Add a channel named **RescueCodec** with a new random 256-bit key. It must not be the default key.
3. Show its QR code. On the phone and on every other radio, scan it and choose **Replace** (not Add). This removes public LongFast, so nothing can leak onto it. With Add, LongFast stays at index 0 and RescueCodec is at index 1. That also works, because the app and the gateway find the channel by name, but Replace is cleaner.
4. In the official app tap **Disconnect**, then force stop it:
   `adb shell am force-stop com.geeksville.mesh`
   A radio talks to one app at a time. If the official app stays connected in the background, our app gets broken handshakes and loses incoming Accepts.

Pass: each radio shows only the channel RescueCodec, with its own key.

## 2. Heltec on USB, gateway on the Mac (1 minute)

```bash
.venv/bin/rescuecodec-gateway --serial auto          # terminal B
python3 hil/check_gateway.py --kind serial           # terminal C, transmits nothing
```

Pass: every line says PASS. These matter most:
- `radio connected (kind serial ...)` and a firmware that starts with 2.7 or 2.8.
- `private channel found: index N name 'RescueCodec'`. If it says PROBLEM, the radio has no private channel or only the public key. Do step 1 again.
- Open http://127.0.0.1:8000/. The dashboard shows the radio as connected and the map shows Koeln.

If `--serial auto` says there are several devices, pass the path, for example `--serial /dev/cu.usbserial-0001`. The official app, the Meshtastic CLI and any serial monitor must not hold the port.

## 3. Install the app and grant permissions (2 minutes)

```bash
. scripts/env.sh
adb install -r dist/RescueCodec-debug.apk
adb shell am start -n org.rescuecodec.app/.MainActivity --es screen RADIO
```

1. Grant **Nearby devices** (Android 12 and newer) and **Notifications** (Android 13 and newer). On Android 11 and older, grant Location instead and switch **Location** on in the system settings. The app shows a hint with a button when it is off.
2. The Radio screen says: close the Meshtastic app first. You did that in step 1.

Pass: no red banner on the Radio screen.

## 4. Pair the radio over Bluetooth (2 minutes)

1. Radio screen, tap **Search**. Hold the phone next to the T1000-E (or the T-Watch).
2. Tap the radio in the list. Android asks to pair.
   - T1000-E: PIN **123456**.
   - T-Watch S3: the PIN is shown on the watch screen.
3. Wait for "Connected". Under it the app shows `Channel N "RescueCodec"`.

Pass: connected within about 30 seconds, and the channel line shows the private channel. In terminal A: `link: connected to ...`.

If the list stays empty: is the radio on and close? On Android 11 and older, is the Location switch on? On Android 12 and newer, if radios still do not show up on this phone, remove `android:usesPermissionFlags="neverForLocation"` from `BLUETOOTH_SCAN` in `android/app/src/main/AndroidManifest.xml`, rebuild and reinstall.

## 5. The scenario (3 minutes)

This is the pitch scenario from the simulator test.

**5a. Found.** In the app, Report screen, type or say the pitch sentence:

> Kind gefunden, ansprechbar, stark unterkühlt, Knöchel verletzt. Wir brauchen eine Trage und zwei Leute.

Check the chips (child, conscious, hypothermia, fracture, stretcher, 2 more helpers). The app shows the live count: about 100 bytes as text, about 23 bytes sent. Tap **Send**.

Pass: within about 10 seconds a card appears on the dashboard in English and German. A pin shows if the phone had a GPS fix. The record shows the coded size (20 to 25 bytes, not 100).

The app row says "Sent". "Relayed by a neighbour" shows only if another radio repeated the packet. With two radios of yours side by side this may stay missing. That is fine. The Accept in step 5b is the real proof.

**5b. Accept and Done.**
1. Dashboard: click **Accept** on the card (team and ETA are optional). The phone task turns **accepted** within about 10 seconds.
2. Dashboard: click **Done**. The phone task turns **done**.
3. Other way round: run `python3 hil/check_gateway.py --wait-in 90`, send another Found from the phone, and watch the script accept and finish it. The phone must show both.

Pass: all four moves show on the other side.

**5c. Mayday.** On the phone, hold the red **SOS** strip for 2 seconds. Tell the room it is a test.

Pass: a red Mayday banner on the dashboard within about 10 seconds. Click **Accept** in the banner. The phone shows accepted.

**5d. Plain text.** In the app type a sentence the rules cannot code, for example `Wir treffen uns um acht am Parkplatz`, and send it.

Pass: the dashboard shows it as a readable text message.

## 6. Auto-reconnect (2 minutes)

1. Phone: switch Bluetooth **off**. The Radio screen says Bluetooth is off. No retry countdown.
2. Switch it **on**. Within about 15 seconds the app is connected again, without a tap.
3. Switch the T1000-E off for 30 seconds, then on. The app reconnects. A message typed in between waits, then goes out (a Mayday goes first).
4. Swipe the app away from recents. The notification stays. Open the app: it is connected.
5. Kill the process: `adb shell am kill org.rescuecodec.app`. Wait 10 seconds. The link comes back, or at the latest when you open the app.

Pass: every case ends in "Connected" without pairing again.

## 7. Battery saver (1 minute)

1. Phone Settings, Battery, App battery usage for RescueCodec: set **Unrestricted** (the name differs by phone).
2. Lock the screen for 2 minutes. From the dashboard Accept an old task or send a text. The phone must still receive it.

Pass: the message shows up on the phone with the screen off, and the notification is still there.

## What to write down (1 minute)

Run this in terminal C and paste the output into your notes (add `--md` for a Markdown table):

```bash
python3 hil/report.py --limit 40
```

It lists every message with its coded size, hex bytes, RSSI, SNR, hops and status, and how long each task took until Accept and Done (gateway clock).

Also note, with a stopwatch:

| What | Your number |
|---|---|
| Tap Send on the phone until the card shows on the dashboard | |
| Click Accept on the dashboard until the phone shows accepted | |
| RSSI and SNR of the phone radio at the gateway (from the report) | |
| Bytes on air for the pitch sentence (expect 22 to 25) | |
| Distance and place of the two radios | |
| Firmware versions (gateway health shows the Heltec) | |

The simulator gave 2.5 s up and 3.5 s back, with no LoRa airtime. Real LONG_FAST adds about 0.5 s per packet. Anything under 10 s is good.

Also keep anything in `adb logcat -s RescueCodec` that looks like an error.

## If it fails

| Sign | Likely cause | Do this |
|---|---|---|
| `hil/preflight.sh` says no USB radio | Charge-only cable, or no driver | Another cable. Heltec V3 shows as `/dev/cu.usbserial-*` or `/dev/cu.usbmodem*` |
| Gateway: `no private channel` | Radio has only LongFast or the default key | Step 1, Replace |
| Gateway: serial port busy or permission error | The official app, `meshtastic --info` or a serial monitor holds the port | Close it, unplug and plug in the Heltec |
| Gateway: nothing arrives, `other_channel` count goes up in `/v2/health` | The phone sends on a different channel than the gateway listens on | Same channel QR on all radios |
| App: "Pairing failed. Check the PIN" | Wrong PIN, or an old pairing | Forget the radio in Android Bluetooth settings, search again |
| App: "The phone and radio no longer trust each other" | Stale bond after a reflash | Forget the radio in Android Bluetooth settings, pair again |
| App: "Radio not found" (GATT 133) | Out of range, or the official app still holds the link | Close the Meshtastic app (step 1.4), move closer, switch the radio off and on |
| App: handshake never finishes | Radio busy, or still connected to another phone | Switch the radio off and on, one phone only |
| App: "Bluetooth permission is missing" | Nearby devices not granted | Allow it in the app permissions |
| App connects but the message stays "queued" | No private channel on that radio | The detail line says why. Step 1 |
| Same report sent twice does not appear again | Same id as an earlier test (app data was cleared) | Do not clear app data between tests, or start the gateway with a new `--db` |
| Dashboard: Accept refused with 429 `rate_limited` | More than 30 API sends in a minute | Wait a minute, or `--tx-per-minute 0` |
| Dashboard shows "No relay confirmed" on your own send | Nobody in range repeated it | Normal with one radio. The Accept is the proof |
| Everything fails and time is short | | Fall back to the simulator: `sim/up.sh`, `rescuecodec-gateway --tcp 127.0.0.1:4404`, and the emulator app. See `sim/README.md` |

Never put a real transmitter into a loop. Airtime is limited by law to 10 percent per hour on 869.4 to 869.65 MHz, and the app and the gateway keep to it.
