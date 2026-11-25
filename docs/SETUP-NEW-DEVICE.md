# Set up RescueCodec on a new laptop, from zero

Use this when you want RescueCodec on a laptop other than Denis's MacBook. Denis's Mac is already set up: for that one, use [SETUP.md](SETUP.md).

At the end this laptop can run the gateway and the dashboard, install the app on phones, and (optional) run the simulator and build the app.

Works on: macOS (tested) and Linux (Ubuntu or Debian, should work). Windows is not tested: the scripts are bash, and the gateway does not find COM ports by itself (`--serial COM3` may work). If you are on Windows, use the Mac.

You need internet once, for the downloads. After that everything runs offline.

| Part | Needed for | Download | Time |
|---|---|---|---|
| A. Basic tools | everything | about 200 MB | 10 min |
| B. Code and gateway | everything | about 100 MB | 5 min |
| C. Offline map | the map behind the pins | about 40 MB | 5 min |
| D. The app file | putting the app on phones | 51 MB (copy) | 2 min |
| E. Voice models | speaking into the app | about 85 MB | 3 min |
| F. Simulator | testing without radios | about 600 MB | 5 min |
| G. Build the app yourself | only if you change the app | about 5 GB | 30 min |

Do A to D. E, F and G are optional.

---

## A. Basic tools

**Mac.** Install Homebrew from https://brew.sh if you do not have it. Then:

```bash
brew install python@3.12 git node pmtiles
brew install --cask android-platform-tools
```

**Linux (Ubuntu or Debian):**

```bash
sudo apt update
sudo apt install -y python3 python3-venv python3-pip git curl unzip adb nodejs npm
sudo usermod -aG dialout $USER        # lets you use the USB radio. Log out and in once after this.
```

`pmtiles` is not in apt. Download the Linux file from https://github.com/protomaps/go-pmtiles/releases, unpack it and put `pmtiles` into `/usr/local/bin/`.

**Check (both):**

```bash
python3 --version     # 3.10 or newer
node --version        # 20 or newer. If older, install Node from https://nodejs.org
adb version
pmtiles version
```

---

## B. Code and gateway

```bash
git clone https://github.com/denishotii/RescueCodec.git
cd RescueCodec
python3 -m venv .venv
.venv/bin/pip install -e '.[gateway,test]'
```

Check it:

```bash
.venv/bin/python -m pytest -q                  # all tests should pass
.venv/bin/rescuecodec-gateway --emulator --emulator-demo --db :memory:
```

Open http://127.0.0.1:8000/. A pretend team sends reports every few seconds. You will not see a map yet (part C). Stop with `Ctrl+C`.

From now on, run every command from inside the `RescueCodec` folder.

---

## C. Offline map

Pick one way:

- **Copy** the folder `assets-offline/` from Denis's Mac into your `RescueCodec/` folder (USB stick or AirDrop). Fastest, and it also brings the voice models (part E).
- **Download** it:

  ```bash
  scripts/fetch-offline-assets.sh
  ```

  This also downloads the voice models. The map covers Köln, Bonn and the Siebengebirge. For another area, change `BBOX=` in that script before you run it.

Then build the dashboard:

```bash
cd dashboard && npm install && npm run build && cd ..
```

You should see `copy-map: ... MB of tiles` at the end. Start the gateway from part B again: now the map shows.

---

## D. The app file

The app is `dist/RescueCodec-debug.apk`. It is not in git.

Copy it from Denis's Mac into a `dist/` folder in your `RescueCodec/` folder:

```bash
mkdir -p dist      # then put RescueCodec-debug.apk in it
```

Use this same file for every team phone. An app built on another laptop has a different signature, and Android then refuses to update it (see part G).

---

## E. Voice models (optional)

If you did part C, the models are already in `assets-offline/vosk/`. With a phone plugged in and the app installed:

```bash
scripts/install-vosk-models.sh de      # German. No argument: German and English
```

---

## F. Simulator (optional)

Three real Meshtastic nodes in Docker, for testing without radios.

1. Install Docker: on a Mac, Docker Desktop from https://www.docker.com/products/docker-desktop/. On Linux, Docker Engine from https://docs.docker.com/engine/install/.
2. Start Docker, then:

   ```bash
   sim/up.sh                            # first run downloads the image (about 600 MB)
   .venv/bin/python sim/smoke.py        # should end with SMOKE PASS
   sim/down.sh
   ```

---

## G. Build the app yourself (optional)

Only if you change the app code. Big downloads.

1. **Java 17.** Mac: `brew install --cask temurin@17`. Linux: `sudo apt install -y openjdk-17-jdk`.
2. **Android SDK command-line tools.** Download "Command line tools only" for your system from https://developer.android.com/studio#command-tools. Unpack it so that this path exists:
   - Mac: `~/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager`
   - Linux: `~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager`
3. **Set the paths.** Mac: `. scripts/env.sh` (it expects the paths above). Linux:

   ```bash
   export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
   export ANDROID_HOME=$HOME/Android/Sdk
   export PATH=$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH
   ```

4. **SDK parts:**

   ```bash
   sdkmanager --licenses                 # answer y to each
   sdkmanager "platform-tools" "platforms;android-37.1" "build-tools;36.0.0"
   ```

5. **Build** (the first build downloads Gradle and libraries, 10 to 20 minutes):

   ```bash
   cd android && ./gradlew :app:assembleDebug && cd ..
   mkdir -p dist && cp android/app/build/outputs/apk/debug/app-debug.apk dist/RescueCodec-debug.apk
   ```

Signature warning: your build has your laptop's own debug key. A phone that has Denis's build will refuse yours with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Then `adb uninstall org.rescuecodec.app` first (this deletes the app's data on that phone). Best: one laptop builds, every phone gets that file.

App unit tests need Java 21 or newer (Mac: `brew install openjdk@25`; elsewhere set `RESCUECODEC_TEST_JAVA_HOME`). Building the app does not.

**Emulator (optional, about 3 GB more):**

```bash
sdkmanager "emulator" "system-images;android-35;google_apis;arm64-v8a"     # Intel or AMD: x86_64 instead of arm64-v8a
avdmanager create avd -n rescue -k "system-images;android-35;google_apis;arm64-v8a" -d pixel_7
emulator -avd rescue &
```

---

## You are done when

- `.venv/bin/python -m pytest -q` passes.
- The dashboard at http://127.0.0.1:8000/ shows the map (part C).
- `dist/RescueCodec-debug.apk` exists (part D).
- Mac: `hil/preflight.sh` shows PASS on every line except phone and radio before you plug them in. On Linux it always warns about the USB radio, because it only knows Mac port names. Ignore that one.

## Next: radios, phones and the test

Now follow [SETUP.md](SETUP.md) from step 2. It is written for Denis's Mac, so change two things:

- Instead of `cd ~/Denis/Hackathons/PMRExpo-2026/RescueCodec`, go to your own `RescueCodec` folder.
- `. scripts/env.sh` is only for a Mac with the Android SDK from part G. Without part G, skip it: `adb` from part A is already on your path.

On Linux the Heltec shows as `/dev/ttyUSB0` or `/dev/ttyACM0`. `--serial auto` finds it. If you get "permission denied", you have not logged out and in after the `usermod` line in part A.
