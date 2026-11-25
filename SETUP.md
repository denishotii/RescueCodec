# Setup: from zero to a working system

This guide takes you from a fresh laptop to a phone message showing up on the dashboard over real radios. Do the steps in order. Each step says what you should see.

```
Phone + app --Bluetooth--> field radio (T1000-E / T-Watch) ~~LoRa~~ Heltec V3 --USB--> laptop: gateway + dashboard
```

Time: about 30 minutes the first time, 2 minutes after that (see "Every day" at the end).

Tested on a Mac. Linux should work the same way. Windows is not tested (the scripts are bash, and `--serial auto` does not find COM ports: use `--serial COM3`).

---

## 1. What you need

**On the laptop**
- Git and Python 3.10 or newer (`python3 --version`).
- `adb` to install the app over USB. On a Mac: `brew install android-platform-tools`.
- Only if you want to rebuild the dashboard: Node.js 20 or newer.
- Only for the simulator (plan B): Docker Desktop.

**Hardware**
- Heltec V3 radio and a USB **data** cable (charge-only cables do not work). This is the command-post radio.
- A field radio: SenseCAP T1000-E or LilyGO T-Watch S3. All radios on stock Meshtastic firmware 2.7 or 2.8.
- An Android phone, Android 8 or newer.
- The official Meshtastic app on that phone (from the Play Store). You need it once, to set up the channel.

---

## 2. Get the code and install the gateway (5 minutes)

```bash
git clone https://github.com/denishotii/RescueCodec.git
cd RescueCodec
python3 -m venv .venv
.venv/bin/pip install -e '.[gateway,test]'
```

Check it:

```bash
.venv/bin/rescuecodec-gateway --help
```

You should see the list of options. If you get "command not found", the install failed: run the `pip install` line again and read the error.

---

## 3. First run with no radio (2 minutes)

This shows the whole dashboard with a pretend team. No hardware needed.

```bash
.venv/bin/rescuecodec-gateway --emulator --emulator-demo --db :memory:
```

Open http://127.0.0.1:8000/ in a browser.

You should see: reports coming in every few seconds (a found person, a hazard, a GPS track), each with its size in bytes (around 20 to 30). Click **Accept** on a card, then **Done**.

The API is at http://127.0.0.1:8000/docs.

Stop it with `Ctrl+C`.

---

## 4. Offline map (once, 10 minutes)

The map files are big (about 40 MB) and not in git. Without them the dashboard still works, but with no map behind the pins.

Pick one way:

- **Copy from Denis's Mac** (fastest): copy the folder `assets-offline/` into your `RescueCodec/` folder (USB stick or AirDrop).
- **Download** (needs internet): `brew install pmtiles`, then `scripts/fetch-offline-assets.sh`.

Then build the dashboard once (needs Node.js):

```bash
cd dashboard && npm install && npm run build && cd ..
```

You should see `copy-map: ... MB of tiles` at the end. Run step 3 again: the map now shows Köln, Bonn and the Siebengebirge.

---

## 5. Set up the radios (once, 5 minutes)

All radios must share one private channel. You set this up with the official Meshtastic app.

1. In the official Meshtastic app, connect to each radio once and set **Region: EU_868**. A new radio (for example a fresh T1000-E) has no region and sends nothing until you set it.
2. Connect to the **Heltec** and set **Modem preset: LONG_FAST**. Keep LONG_FAST: the app's airtime numbers assume it.
3. Add a channel named exactly **RescueCodec**. Let the app make a new random key. Do not keep the default key.
4. Open the channel's **QR code**.
5. Connect the official app to each other radio (T1000-E, T-Watch), scan that QR code and choose **Replace**. Do this for every radio the team uses.
6. In the official app tap **Disconnect**. Then close it fully (Android Settings, Apps, Meshtastic, Force stop).

Why step 6: a radio talks to one app at a time. If the official app stays connected in the background, our app cannot use the radio.

You should see: every radio shows one channel, named RescueCodec, region EU_868, preset LONG_FAST.

Keep the QR code private. Anyone with it can read and send on the team channel.

---

## 6. Start the gateway with the Heltec (2 minutes)

1. Plug the Heltec into the laptop with the data cable.
2. Check the laptop (reads only, sends nothing):

   ```bash
   hil/preflight.sh
   ```

   Every line should say PASS. A WARN for the phone is fine at this point.
3. Start the gateway:

   ```bash
   .venv/bin/rescuecodec-gateway --serial auto
   ```

   If it says it found several devices, give the port by hand, for example `--serial /dev/cu.usbserial-0001`. `ls /dev/cu.*` lists them.
4. In a second terminal, check it (sends nothing):

   ```bash
   .venv/bin/python hil/check_gateway.py --kind serial
   ```

You should see: every line PASS, including `radio connected` and `private channel found: ... 'RescueCodec'`. The dashboard at http://127.0.0.1:8000/ shows the radio as connected.

Leave the gateway running.

---

## 7. Install the app on the phone (5 minutes)

The app file is `dist/RescueCodec-debug.apk` (about 51 MB). It is not in git. Get it from Denis, or build it yourself (see "Build the app yourself" at the end).

**Way A: with a USB cable (recommended)**

1. On the phone: Settings, About phone, tap **Build number** 7 times (Samsung: About phone, Software information). Then Settings, Developer options, switch on **USB debugging**.
2. Plug the phone into the laptop. On the phone tap **Allow** for USB debugging.
3. On the laptop:

   ```bash
   adb devices                              # the phone must show as "device"
   adb install -r dist/RescueCodec-debug.apk
   ```

   If it fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the phone has a build from another laptop. Remove it first with `adb uninstall org.rescuecodec.app` (this deletes the app's data), then install again. Use the same APK on all team phones.

**Way B: no computer**

Send the APK file to the phone (WhatsApp to yourself, Google Drive, USB stick). Tap it in the Files app. Android asks to allow installs from that app: allow it. If Play Protect warns, tap **Install anyway**.

**First start**

Open **RescueCodec**. Allow what it asks for:

| Permission | Why |
|---|---|
| Nearby devices (Android 12+) | Find and connect the radio over Bluetooth |
| Location | Put your GPS position on reports. On Android 11 and older also needed for Bluetooth, and Location must be switched on |
| Notifications (Android 13+) | Keeps the radio link alive with the screen off |
| Microphone | Voice input (optional) |
| Camera | Scan the channel QR code (optional) |

Then set the battery to unrestricted: Android Settings, Apps, RescueCodec, Battery, **Unrestricted** (the name differs a bit by phone). Otherwise Android may cut the link when the screen is off.

**Voice (optional)**

The app has no speech models inside, so the mic button is grey at first. Typing always works. To add voice: plug the phone in and run `scripts/install-vosk-models.sh de` (German) or with no argument for German and English. Or copy the model zip to the phone and use Settings, Speech models, Import. Details: `docs/VOICE.md`.

---

## 8. Connect the app to the field radio (2 minutes)

1. Make sure the official Meshtastic app is closed (step 5.6).
2. In RescueCodec open the **Radio** tab and tap **Scan for radios**. Hold the phone next to the radio.
3. Tap your radio in the list. Android asks to pair:
   - T1000-E: PIN **123456**.
   - T-Watch S3: the PIN is shown on the watch.
4. Wait for **Connected**.

You should see: "Connected" within about 30 seconds, and under it `Channel N "RescueCodec"`.

If the radio has no RescueCodec channel yet, tap **Scan channel QR code** on the same screen and scan the team QR from step 5. Like Replace in step 5, this overwrites the radio's main channel and its region and preset.

Next time the app reconnects by itself, also after the screen was off. Do not tap **Disconnect** in the app's notification: that switches auto-reconnect off until you connect again on the Radio screen.

A Heltec or T-Watch on Wi-Fi also works: section "Simulator or Wi-Fi radio", tap **Wi-Fi radio**, type the radio's IP address, port 4403, **Connect**. The T1000-E has no Wi-Fi.

---

## 9. Test it end to end (3 minutes)

1. **Send a report.** Open the **Report** tab and type:

   > Kind gefunden, ansprechbar, stark unterkühlt, Knöchel verletzt. Wir brauchen eine Trage und zwei Leute.

   Check the chips (child, conscious, hypothermia, fracture, stretcher, 2 helpers). The app shows about 100 bytes as text and about 23 bytes coded. Tap **Send**.

   You should see: a card on the dashboard within about 10 seconds, in English and German, with a pin if the phone had GPS.

2. **Accept and Done.** On the dashboard click **Accept** on that card, then **Done**.

   You should see: the task on the phone (Actions tab) turns **accepted**, then **done**.

3. **SOS.** On the phone hold the red **SOS** strip for 2 seconds. Tell people nearby it is a test.

   You should see: a red Mayday banner on the dashboard.

If all three work, the system works. The full checklist with more cases (reconnect, battery saver, what to write down) is in `HARDWARE_CHECK.md`.

---

## Every day (after the first setup)

```bash
cd RescueCodec
.venv/bin/rescuecodec-gateway --serial auto     # Heltec plugged in
```

Open http://127.0.0.1:8000/. On the phone open RescueCodec: it reconnects to the radio by itself.

The gateway keeps everything in `rescuecodec-gateway.db`. For a clean demo start with a new file: `--db demo1.db`.

---

## Show the dashboard on another screen

On the gateway laptop:

```bash
.venv/bin/rescuecodec-gateway --serial auto --host 0.0.0.0 --token pick-a-secret
```

On the other device (same Wi-Fi): open `http://<gateway-laptop-ip>:8000/?token=pick-a-secret`. Find the IP with `ipconfig getifaddr en0` on a Mac.

For a projector, just mirror or extend the gateway laptop over HDMI. No token needed.

---

## Plan B: no radios (simulator)

Three real Meshtastic nodes run in Docker. Needs Docker Desktop.

```bash
sim/up.sh                                         # start and set up the nodes
.venv/bin/python sim/smoke.py                     # should end with SMOKE PASS
.venv/bin/rescuecodec-gateway --tcp 127.0.0.1:4404
```

For the phone side, use the Android emulator on Denis's Mac (`scripts/emulator-up.sh`). In the app: Radio tab, section "Simulator or Wi-Fi radio", tap **Simulator**, then **Connect**. Stop everything with `scripts/emulator-down.sh` and `sim/down.sh`.

A real phone cannot reach the simulator (it only listens on the laptop itself).

---

## If something goes wrong

| What you see | Do this |
|---|---|
| `preflight.sh`: no USB radio | Try another cable (it must carry data). Unplug and plug in again |
| Gateway: port busy | Close the official app, the Meshtastic CLI or any serial monitor. Unplug and plug in the Heltec |
| Gateway: no private channel | Step 5 again, with Replace |
| App: radio list stays empty | Radio on and close? Official Meshtastic app force-stopped (on every phone)? Switch the radio off and on. Android 11 or older: Location switched on? |
| App: stuck on "Connecting" or the handshake hangs | The official app or another phone still holds the radio. Force-stop it, switch the radio off and on, try again |
| App: link stopped after a permission pop-up | Grant Nearby devices, then tap **Reconnect to ...** on the Radio screen |
| App: "Pairing failed" or "no longer trust each other" | Android Bluetooth settings, forget the radio, then **Scan for radios** again |
| App: message stays "queued" | That radio has no RescueCodec channel. Read the line under the message, then step 5 |
| App shows "No radio" after start and does not reconnect | First save `adb logcat -s RescueCodec` (the `link:` lines). Then Radio tab, tap **Reconnect to ...**. Still stuck: `adb shell am force-stop org.rescuecodec.app` and open the app again |
| Dashboard: card does not show up | Phone radio and Heltec need the same channel QR, region and preset. `http://127.0.0.1:8000/v2/health` shows `other_channel` going up if the channel differs |
| Gateway keeps printing "radio not available, retrying" | Charge-only cable, port busy, or two boards plugged in (then pass `--serial /dev/cu.usbserial-XXXX`) |
| App: Send button stays blocked on a long sentence | Known bug: a long free-text sentence plus GPS is just over the size limit. Shorten the sentence |
| Dashboard: Accept refused (429) | More than 30 sends in a minute. Wait one minute |

More causes and fixes: `HARDWARE_CHECK.md`, section "If it fails".

---

## Build the app yourself (optional)

Only needed if you change the app. Needs JDK 17 and the Android SDK (about 3 GB, see `OFFLINE.md`).

```bash
. scripts/env.sh
cd android && ./gradlew :app:assembleDebug && cd ..
mkdir -p dist && cp android/app/build/outputs/apk/debug/app-debug.apk dist/RescueCodec-debug.apk
```

`scripts/env.sh` is set up for a Mac. On Linux, set `JAVA_HOME` and `ANDROID_HOME` yourself.

## More

- `README.md`: what is in the repo.
- `HARDWARE_CHECK.md`: the full real-radio checklist.
- `docs/INTEGRATE.md`: use RescueCodec from your own app (REST, MQTT, TAK, Python, Kotlin, TypeScript, Android Intent).
- `docs/VOICE.md`: offline speech.
- `OFFLINE.md`: every download and its licence.
