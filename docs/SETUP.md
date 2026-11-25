# Run RescueCodec from Denis's MacBook

Everything runs from this one Mac. Nobody else installs anything. (Another laptop: [SETUP-NEW-DEVICE.md](SETUP-NEW-DEVICE.md).) Teammates only need an Android phone (for the app) or a browser (to watch the dashboard).

```
Phone + app --Bluetooth--> field radio (ThinkNode M5, T1000-E, T-Watch) ~~LoRa~~ Heltec V3 --USB--> this Mac: gateway + dashboard
```

Already installed on this Mac: the gateway, the dashboard with the offline map, the app file (`dist/RescueCodec-debug.apk`), adb, the voice models, the Meshtastic simulator (Docker) and the Android emulator.

Time: about 20 minutes the first time, 2 minutes after that (see "Demo day" below).

---

## 1. Open a terminal on this Mac (1 minute)

Every command in this guide runs in this folder, in a terminal where you ran these two lines first:

```bash
cd ~/Denis/Hackathons/PMRExpo-2026/RescueCodec
. scripts/env.sh
```

Check the Mac (reads only, sends nothing):

```bash
hil/preflight.sh
```

You should see PASS on every line. A WARN for "no phone" or "no USB radio" is fine until you plug them in.

---

## 2. Try it with no radio (2 minutes)

```bash
.venv/bin/rescuecodec-gateway --emulator --emulator-demo --db :memory:
```

Open http://127.0.0.1:8000/. A pretend team sends reports every few seconds (a found person, a hazard, a GPS track), each about 20 to 30 bytes. Click **Accept** on a card, then **Done**. Try the **Send** panel on the right: type a sentence and it shows how many bytes it will send.

Stop it with `Ctrl+C`.

---

## 3. Set up the radios (once, 5 minutes)

All radios must share one private channel. Use the official Meshtastic app on one phone for this.

1. In the official Meshtastic app, connect to each radio once and set **Region: EU_868**. A new radio (for example a fresh T1000-E) has no region and sends nothing until you set it.
2. Connect to the **Heltec** and set **Modem preset: LONG_FAST**. Keep LONG_FAST: the app's airtime numbers assume it.
3. Add a channel named exactly **RescueCodec**. Let the app make a new random key. Do not keep the default key.
4. Open the channel's **QR code**.
5. Connect the official app to each other radio (T1000-E, T-Watch), scan that QR code and choose **Replace**.
6. In the official app tap **Disconnect**. Then close it fully: Android Settings, Apps, Meshtastic, **Force stop**. Do this on every phone that has the official app.

Why step 6: a radio talks to one app at a time. If the official app stays connected in the background, our app cannot use the radio.

You should see: every radio shows one channel, named RescueCodec, region EU_868, preset LONG_FAST.

Keep the QR code private. Anyone with it can read and send on the team channel.

---

## 4. Plug in the Heltec and start the gateway (2 minutes)

1. Plug the Heltec into this Mac with a USB **data** cable (a charge-only cable does not work).
2. Start the gateway and leave this terminal open:

   ```bash
   .venv/bin/rescuecodec-gateway --serial auto
   ```

   If it says it found several devices, pick one by hand: `ls /dev/cu.*` lists them, then for example `--serial /dev/cu.usbserial-0001`.
3. In a second terminal (same two lines from step 1 first), check it. This sends nothing:

   ```bash
   .venv/bin/python hil/check_gateway.py --kind serial
   ```

You should see: every line PASS, including `radio connected` and `private channel found: ... 'RescueCodec'`. Open http://127.0.0.1:8000/: the dashboard shows the radio as connected.

---

## 5. Install the app on each phone from this Mac (3 minutes per phone)

Do this for every team phone, one at a time.

1. On the phone: Settings, About phone, tap **Build number** 7 times (Samsung: About phone, Software information). Then Settings, Developer options, switch on **USB debugging**.
2. Plug the phone into this Mac. On the phone tap **Allow** for USB debugging.
3. On the Mac:

   ```bash
   adb devices                              # the phone must show as "device"
   adb install -r dist/RescueCodec-debug.apk
   ```

4. Voice (optional, 1 minute): `scripts/install-vosk-models.sh de` for German, or with no argument for German and English. Without it typing still works, the mic button is just grey.

If `adb install` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the phone has an older build from somewhere else. Run `adb uninstall org.rescuecodec.app` (deletes the app's data), then install again.

Several phones plugged in at once: `adb devices` shows their serial numbers. Put the one you mean in front, for example `ANDROID_SERIAL=R58M123ABC adb install -r dist/RescueCodec-debug.apk`. The same works for the voice script.

**On the phone, first start**

Open **RescueCodec** and allow what it asks for:

| Permission | Why |
|---|---|
| Nearby devices (Android 12+) | Find and connect the radio over Bluetooth |
| Location | Put the GPS position on reports. On Android 11 and older also needed for Bluetooth, and Location must be switched on |
| Notifications (Android 13+) | Keeps the radio link alive with the screen off |
| Microphone | Voice input (optional) |
| Camera | Scan the channel QR code (optional) |

Then set the battery to unrestricted: Android Settings, Apps, RescueCodec, Battery, **Unrestricted** (the name differs a bit by phone). Otherwise Android may cut the link when the screen is off.

---

## 6. Connect the app to a field radio (2 minutes)

1. The official Meshtastic app must be force-stopped (step 3.6).
2. In RescueCodec open the **Radio** tab and tap **Scan for radios**. Hold the phone next to the radio.
3. Tap your radio in the list. Android asks to pair:
   - T1000-E: PIN **123456**.
   - ThinkNode M5, T-Watch S3 and other radios with a screen: the PIN is shown on the screen.
4. Wait for **Connected**.

You should see: "Connected" within about 30 seconds, and under it `Channel N "RescueCodec"`.

If the radio has no RescueCodec channel yet, tap **Join by QR** on the same tab and scan the team QR from step 3. This adds the channel next to the ones the radio already has. It never changes the radio's main channel. It sets region and preset only on a radio that has no region yet.

You can also make a new channel on the phone: Radio tab, **New channel**, type a name (at most 11 bytes). The QR code opens right away, and teammates scan it with **Join by QR**. To show it again, tap the three dots next to the channel and choose **Show QR code**.

From now on the app reconnects by itself, also after the screen was off. Do not tap **Disconnect** in the app's notification: that switches auto-reconnect off until you connect again on the Radio tab.

One phone per radio. Each field radio talks to one phone.

---

## 7. Test it end to end (3 minutes)

1. **Send a report.** On the phone open **Chats**, then the **RescueCodec** chat, and type:

   > Kind gefunden, ansprechbar, stark unterkühlt, Knöchel verletzt. Wir brauchen eine Trage und zwei Leute.

   Check the chips (child, conscious, hypothermia, fracture, stretcher, 2 helpers). The app shows 105 bytes as text and about 23 to 27 bytes sent. Tap **Send**.

   You should see: a card on the dashboard within about 10 seconds, in English and German, with a pin if the phone had GPS.

2. **Accept and Done.** On the dashboard click **Accept** on that card, then **Done**.

   You should see: on the phone (Tasks tab) the task turns **accepted**, then **done**.

3. **SOS.** On the phone hold the red **SOS** strip for 2 seconds. Tell people nearby it is a test.

   You should see: a red Mayday banner on the dashboard.

4. **Send from the command post.** In the dashboard's **Send** panel keep **#RescueCodec**, type `Status check, all teams report`, and press **Send**.

   You should see: the message in the phone's RescueCodec chat within about 10 seconds.

If all four work, the system works. The longer checklist (reconnect, battery saver, what to write down) is `docs/HARDWARE_CHECK.md`.

---

## Demo day (2 minutes)

```bash
cd ~/Denis/Hackathons/PMRExpo-2026/RescueCodec
. scripts/env.sh
hil/preflight.sh                                          # Heltec plugged in
.venv/bin/rescuecodec-gateway --serial auto --db demo.db
```

- Open http://127.0.0.1:8000/ and put the browser in full screen. HDMI to the projector: mirror this Mac's screen.
- Phones: open RescueCodec. They reconnect to their radios by themselves.
- `--db demo.db` keeps the demo data apart from the test data. For a clean start, delete `demo.db` before you start the gateway.

---

## Let teammates watch on their own laptop or phone

No install needed, only a browser. This Mac and the other device must be on the same Wi-Fi. Event and hotel Wi-Fi often block devices from seeing each other. If it does not load, use the iPhone hotspot for both.

Start the gateway like this instead:

```bash
.venv/bin/rescuecodec-gateway --serial auto --host 0.0.0.0 --token pick-a-secret
```

Find this Mac's address with `ipconfig getifaddr en0`. On the other device open `http://<that address>:8000/?token=pick-a-secret`.

---

## Base radio over Bluetooth (frees both USB ports)

Power the Heltec from any USB charger or power bank, then start the gateway with `--ble` instead of `--serial`:

```bash
.venv/bin/rescuecodec-gateway --ble <name> --db demo.db --compact-text on
```

Use the radio's Bluetooth name (the Radio tab of the app shows it when scanning, for example `BASE_65b0`); `--ble auto` takes the first Meshtastic radio it finds, which in a busy hall may be someone else's. The first time, macOS asks whether the app may use Bluetooth (allow it) and the radio may ask for the PIN shown on its screen. Only one device can use a radio over Bluetooth at a time.

## Plan B: no radios (simulator and emulator, all on this Mac)

Use this if the radios fail. Three real Meshtastic nodes run in Docker, and the Android emulator plays the phone. Start Docker Desktop first.

Terminal 1, the simulated radios and the gateway:

```bash
sim/up.sh                                         # start the 3 simulated radios
.venv/bin/python sim/smoke.py                     # should end with SMOKE PASS
.venv/bin/rescuecodec-gateway --tcp 127.0.0.1:4404
```

Terminal 2, the emulator phone (a phone window opens on the Mac):

```bash
emulator -avd rescue -no-snapshot-save &
adb wait-for-device
adb install -r dist/RescueCodec-debug.apk
```

In the emulator open RescueCodec: Radio tab, section "Simulator or Wi-Fi radio", tap **Simulator**, then **Connect**. Then do step 7.

Stop everything afterwards: close the emulator window, then `sim/down.sh`.

A real phone cannot reach the simulator. It only listens on this Mac.

---

## If something goes wrong

| What you see | Do this |
|---|---|
| `preflight.sh`: no USB radio | Try another cable (it must carry data). Unplug and plug in again |
| Gateway: port busy | Close the Meshtastic CLI or any serial monitor. Unplug and plug in the Heltec |
| Gateway keeps printing "radio not available, retrying" | Charge-only cable, port busy, or two boards plugged in (then pass `--serial /dev/cu.usbserial-XXXX`) |
| Gateway: no private channel | Step 3 again, with Replace |
| `adb devices` shows "unauthorized" | Unlock the phone and tap Allow on the USB debugging prompt |
| App: radio list stays empty | Radio on and close? Official Meshtastic app force-stopped (on every phone)? Switch the radio off and on. Android 11 or older: Location switched on? |
| App: stuck on "Connecting" or the handshake hangs | The official app or another phone still holds the radio. Force-stop it, switch the radio off and on, try again |
| App: link stopped after a permission pop-up | Grant Nearby devices, then tap **Reconnect to ...** on the Radio tab |
| App: "Pairing failed" or "no longer trust each other" | Android Bluetooth settings, forget the radio, then **Scan for radios** again |
| App: message stays "queued" | That radio has no RescueCodec channel. Read the line under the message, then step 3 |
| App shows "No radio" after start and does not reconnect | With the phone on USB, save `adb logcat -s RescueCodec` (the `link:` lines). Then Radio tab, tap **Reconnect to ...**. Still stuck: `adb shell am force-stop org.rescuecodec.app` and open the app again |
| App: Send button stays blocked on a long sentence | Known bug: a long free-text sentence plus GPS is just over the size limit. Shorten the sentence |
| Dashboard: card does not show up | Phone radio and Heltec need the same channel QR, region and preset. http://127.0.0.1:8000/v2/health shows `other_channel` going up if the channel differs |
| Dashboard: Accept refused (429) | More than 30 sends in a minute. Wait one minute |

More causes and fixes: `docs/HARDWARE_CHECK.md`, section "If it fails".

---

## After a code change (on this Mac)

App:

```bash
cd android && ./gradlew :app:assembleDebug && cd ..
cp android/app/build/outputs/apk/debug/app-debug.apk dist/RescueCodec-debug.apk
adb install -r dist/RescueCodec-debug.apk
```

Dashboard: `cd dashboard && npm run build && cd ..`, then restart the gateway.

Gateway: just restart it.

Tests: `.venv/bin/python -m pytest -q`

## More

- [HARDWARE_CHECK.md](HARDWARE_CHECK.md): the full real-radio checklist.
- [README.md](../README.md): what is in the repo.
- [INTEGRATE.md](INTEGRATE.md): use RescueCodec from other apps (REST, MQTT, TAK, Python, Kotlin, TypeScript, Android Intent).
- [VOICE.md](VOICE.md): offline speech.
- [SETUP-NEW-DEVICE.md](SETUP-NEW-DEVICE.md): set up another laptop from zero.
- [OFFLINE.md](OFFLINE.md): every download and its licence.
