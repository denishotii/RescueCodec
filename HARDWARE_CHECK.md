# Hardware check: Wednesday morning, about 10 minutes

Goal: prove the real path works with real radios. Everything so far ran on the simulator and the Android emulator. Nothing here is tested on real Bluetooth or USB yet.

You need: Heltec V3 with a USB cable, a Mac, an Android phone (any Android 8 to 15), a SenseCAP T1000-E and/or a LilyGO T-Watch S3 (all on stock Meshtastic firmware 2.7 or 2.8), the debug APK, and the official Meshtastic app on the phone for the one-time channel setup.

Open two terminals before you start:

```bash
# terminal A: phone log. Leave it running the whole time.
source scripts/env.sh && adb logcat -s RescueCodec
# terminal B: gateway log (step 2)
```

Every step has a pass criterion. If a step fails, stop there and read the "If it fails" list at the bottom.

## 1. Make the private channel (once, 3 minutes)

1. In the official Meshtastic app, connect to the Heltec. Set region EU_868 and modem preset LONG_FAST.
2. Add a channel named **RescueCodec** with a new random key (256-bit). Make sure it is not the default key.
3. Show its QR code. On the phone and on every other radio, scan it and choose **Replace** (not Add). This removes public LongFast, so nothing can leak onto it. With Add, LongFast stays at index 0 and the app and gateway must find RescueCodec at index 1. That works too (they look it up by name), but Replace is cleaner.
4. In the official app, tap **Disconnect**, then force stop it:
   `adb shell am force-stop com.geeksville.mesh`
   Only one app can talk to a radio at a time. If the official app stays connected in the background, our app gets broken handshakes and loses incoming Accepts.

Pass: each radio shows only the channel RescueCodec, with its own key.

## 2. Heltec on USB, gateway on the Mac (1 minute)

```bash
.venv/bin/rescuecodec-gateway --serial auto
python3 hil/check_gateway.py            # in another terminal, transmits nothing
```

Pass: every line says PASS. These matter most:
- `radio connected (kind serial ...)` and a firmware that starts with 2.7 or 2.8.
- `private channel found: index N name 'RescueCodec'`. If it says PROBLEM, the radio has no private channel or only the public key. Do step 1 again.
- Open http://127.0.0.1:8000/ : the dashboard shows the radio chip as connected, the map shows Koeln.

If `--serial auto` says several devices: pass the path, for example `--serial /dev/cu.usbserial-0001`. The official app or the Meshtastic CLI must not hold the port.

## 3. Install the app and grant permissions (2 minutes)

```bash
source scripts/env.sh
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n org.rescuecodec.app/.MainActivity --es screen RADIO
```

1. Grant **Nearby devices** (Android 12 and newer) and **Notifications** (Android 13 and newer). On Android 11 and older, grant Location instead and switch **Location** on in the system settings. The app shows a hint with a button when it is off.
2. Open the Radio screen. It says: close the Meshtastic app first. You did that in step 1.

Pass: no red banner on the Radio screen.

## 4. Pair the radio over Bluetooth (2 minutes)

1. Radio screen, tap **Search**. Hold the phone next to the T1000-E (or the T-Watch).
2. Tap the radio in the list. Android asks to pair.
   - T1000-E: PIN **123456**.
   - T-Watch S3: the PIN is shown on the watch screen.
3. Wait for "Connected". Under it the app shows `Channel N "RescueCodec"`.

Pass: connected within about 30 seconds, and the channel line shows the private channel. In the log: `link: connected to ...`.

If the list stays empty: the radio is on and close? Android 11 and older: Location switch on? Android 12 and newer: if radios still do not show up on this phone, remove `android:usesPermissionFlags="neverForLocation"` from `BLUETOOTH_SCAN` in `AndroidManifest.xml`, rebuild, reinstall.

## 5. First message: Found, bytes on the dashboard (1 minute)

1. In the app, Report screen: type `Kind gefunden, bei Bewusstsein, unterkühlt, braucht Trage`, tap Send.
2. Dashboard.

Pass: within about 10 seconds a card appears on the dashboard in English and German, a pin appears if the phone had a GPS fix, and the message record shows the coded bytes (about 20 to 25 bytes, not 60). `python3 hil/check_gateway.py` still passes.

The app row says "Sent". "Relayed by a neighbour" appears only if another radio repeated the packet. With only two radios of yours close together this may stay missing. That is fine. The Accept in step 6 is the real proof.

## 6. Accept and Done round trip (1 minute)

1. Dashboard: click **Accept** on the card (team and ETA optional).
2. Phone: the task turns **accepted** within about 10 seconds.
3. Dashboard: click **Done**. The phone shows **done**.
4. Other direction: run `python3 hil/check_gateway.py --wait-in 90`, send another Found from the phone, and watch the script accept and finish it.

Pass: all four moves show on the other side. Note the times.

## 7. Auto-reconnect (1 minute)

1. Phone: switch Bluetooth **off**. The Radio screen says Bluetooth is off. No retry countdown.
2. Switch it **on**. Within about 15 seconds the app is connected again, without a tap.
3. Walk out of range or switch the radio off for 30 seconds, then on. The app reconnects. Messages typed meanwhile wait and then go out (Mayday first).
4. Swipe the app away from recents. The notification stays. Open the app: it is connected.
5. Kill the process: `adb shell am kill org.rescuecodec.app`. Wait 10 seconds. The radio link comes back, or the next time you open the app it does.

Pass: every case ends in "Connected" without pairing again.

## 8. Battery saver (1 minute)

1. Settings, Battery, App battery usage for RescueCodec: set **Unrestricted** (name differs by phone).
2. Lock the screen for 2 minutes. From the dashboard send an Accept for an old task or send a text. The phone must still receive.

Pass: the message shows up on the phone with the screen off, and the notification is still there.

## What to write down

- Time from tap Send on the phone to the card on the dashboard. Same for Accept back to the phone.
- RSSI and SNR from the dashboard record for the Bluetooth radio and for the Heltec.
- Anything in `adb logcat -s RescueCodec` that looks like an error.

## If it fails

| Sign | Likely cause | Do this |
|---|---|---|
| Gateway: `no private channel` | Radio has only LongFast or the default key | Step 1, Replace |
| Gateway: nothing arrives, `other_channel` count goes up in `/v2/health` | The phone sends on a different channel than the gateway listens on | Same channel QR on all radios |
| App: "Pairing failed. Check the PIN" | Wrong PIN, or an old pairing | Forget the radio in Android Bluetooth settings, search again |
| App: "The phone and radio no longer trust each other" | Stale bond after a reflash | Forget the radio in Android Bluetooth settings, pair again |
| App: "Radio not found" (GATT 133) | Out of range, or the official app still holds the link | Close the Meshtastic app (step 1.4), move closer, switch the radio off and on |
| App: handshake never finishes | Radio busy, or still connected to another phone | Switch the radio off and on, one phone only |
| App: "Bluetooth permission is missing" | Nearby devices not granted | Allow it in the app permissions |
| Same report sent twice does not appear again | Same id as an earlier test (app data was cleared) | Do not clear app data between tests, or start the gateway with a new `--db` |
| Dashboard: Mayday or Accept refused with 429 `rate_limited` | More than 30 API sends in a minute | Wait a minute, or `--tx-per-minute 0` |

Never put a real transmitter into a loop. Airtime is limited by law to 10 percent per hour on 869.4 to 869.65 MHz, and the app and gateway keep to it.
