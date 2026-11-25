# Voice: speech in, read-aloud out

Both work with no internet.

## Speech to text (Vosk)

The Report screen has a big mic button. Hold it, speak, let go. The words land in the text field, the fields are read from them, and a person checks the chips before pressing Send.

- Engine: Vosk 0.3.75 (`com.alphacephei:vosk-android`, Apache-2.0). It adds about 13 MB to the APK (native code for arm64, armv7 and x86_64).
- Models: `vosk-model-small-de-0.15` (about 45 MB zipped, 50 MB unpacked) and `vosk-model-small-en-us-0.15` (40 MB zipped, 68 MB unpacked). Both Apache-2.0. They are in `assets-offline/vosk/` (see `docs/OFFLINE.md`).
- The app keeps one model in memory (roughly 100 to 300 MB of RAM while loaded). Changing the language in the app loads the other model.
- The app reads the microphone itself (16 kHz mono), so the screen can draw the loudness rings and so letting go of the button ends the phrase at once.

### How the models get onto the phone

The default APK has **no models**. Two small models would add 85 MB to a 50 MB debug APK and make every `adb install` slow, and a field phone only needs the language its team speaks. There are three ways to add them. All work offline.

| Way | When | How |
|---|---|---|
| 1. Script over USB | Your own debug build, a computer at hand | `scripts/install-vosk-models.sh` (both languages) or `scripts/install-vosk-models.sh de`. It pushes the models over adb and copies them into the app's private folder with `run-as`. Needs a debug build. Falls back to `Android/data/org.rescuecodec.app/files/vosk/<lang>` for release builds. |
| 2. Import in the app | Any phone, no computer | Copy `vosk-model-small-de-0.15.zip` to the phone (USB, SD card, cloud, any way). Settings, Speech models, tap "Import German model", pick the zip. The app unpacks it into its private folder. |
| 3. Full APK | You want one file that just works | `cd android && ./gradlew :app:assembleDebug -PbundleVosk=true`. Both models go into the APK assets (about +85 MB). The first use unpacks them into the private folder. |

Check the state in Settings, Speech models: Ready, Inside the app, or Missing. When a model is missing, the mic button is greyed out and says "No voice model". Tapping it explains what to do. Typing always works.

Search order (`voice/VoskModels.kt`): the app's private folder `files/vosk/<lang>`, then `Android/data/org.rescuecodec.app/files/vosk/<lang>`, then the APK assets.

Why not Play Asset Delivery or a download button? The phones are sideloaded and often have no internet. A download would not work when it matters.

### What was tested

- On the Android 15 emulator with both models (`VoiceModelTest`): speech made with the macOS `say` command (16 kHz) is recognised and turned into a Found report:
  - English: "child found conscious hypothermia need stretcher" gives a Found report (child, conscious, hypothermia, stretcher).
  - German: "kind gefunden ansprechbar unterkühlt trage benötigt" gives the same fields.
- The emulator has no microphone, so the live hold-to-talk path (AudioRecord into Vosk) is **not** verified here. It is on the hardware check list: hold the mic on a real phone, say a sentence, check the text.
- Synthetic speech is cleaner than a person in wind and with gloves on. Expect more mistakes outdoors. The chips are there so a person can fix them.

## Read aloud (Android TextToSpeech)

Every card in the Feed has a "Read aloud" button (48 dp tall, with a label, not just an icon). It reads the decoded line in the language of the app.

- `voice/Speaker.kt` uses `android.speech.tts.TextToSpeech`. It prefers an installed voice that does not need the network. If the phone has no voice for the language, the button shows a message ("No offline voice for German on this phone. The text stays on screen.") and the card keeps its large text. Nothing breaks.
- Tested on the emulator: the Google engine speaks English and German with embedded (offline) voices.
- On a real phone check Settings, System, Languages, Text-to-speech output, and install the German and English voice data once while you still have internet.
