# RescueCodec Android app (`:app`)

Licence: GPL-3.0-or-later (it links the official Meshtastic Kotlin SDK, which is GPL-3.0). `:codec` and `:engine` stay Apache-2.0.

This module holds the radio and data layer, and the screens and voice on top of it. Design notes: `DESIGN.md` in this folder. Voice: `docs/VOICE.md`.

## Layers

| Package | What it does |
|---|---|
| `radio/` | `ConnectionManager` (states `Disconnected`, `Scanning`, `Connecting`, `Connected`, `Error`; retry with backoff 1 s to 60 s), `RadioTarget` (TCP or Bluetooth address), `LastRadioStore`, transports and the Bluetooth scanner. |
| `core/` | `Messenger` (send queue from `:engine`, packet handling, tasks, Accept and Done), `AppSettings`, `UiPrefs` (language, day/night), `ReportFields` (chip editing), `Labels` (words from spec/i18n), `LocationHelper`. |
| `data/` | Room database: `messages` (every packet with its state), `tasks`, `stats`. |
| `service/` | `RadioService`: foreground service, type `connectedDevice`, keeps the link alive with the screen off. |
| `intent/` | Public Intent API `org.rescuecodec.action.SEND_REPORT`. See `docs/INTEGRATE.md`, section "Android Intent". |
| `ui/` | `RescueRoot` (theme, language, top bar, SOS strip, navigation), `screens/` (Report, Actions, Feed, Stats, Connect, Settings, QR scanner), `components/` (cards, chips, hold-to-confirm, tags), `theme/`, view models (`StateFlow`), permission plan. |
| `voice/` | `SpeechInput` (Vosk, hold to talk), `VoskModels` and `ModelImporter` (where the models are, import from a zip), `Speaker` (Android TextToSpeech). |

`AppGraph` creates and holds all of it. `RescueApp` owns one.

## How a message travels

1. `Messenger.submit(message)` encodes it with `:codec`, writes a row (`queued`) and puts it into the `SendQueue`.
2. The send loop takes the next message that fits the airtime budget (Mayday first) and sends it as a `PRIVATE_APP` (256) broadcast on the team channel, with `want_ack` and a Meshtastic priority.
3. The radio answers: `sent`, `delivered` (a neighbour repeated it), `not_confirmed`, or `refused_airtime` (the radio's own limit). Refused messages are tried again later.
4. Incoming packets on port 256 are decoded, checked for duplicates and written as `received`. Reports become tasks, Acks change task states.
5. `Messenger.answer(key, "accepted" | "declined" | "done")` sends an Ack and applies it at once.

Everything is in the database, so a restart loses nothing: the queue, the tasks, the duplicate memory and the used airtime are rebuilt.

## Which radio channel

Reports go out on one private channel, never on the public default channel. `core/ChannelChoice.kt` decides:

1. The person picked a channel on the main screen: that one, if it exists on the radio and has its own key.
2. Else the enabled channel named `RescueCodec`, if it has its own key.
3. Else the only enabled channel with its own key.
4. Else nothing is sent. Messages stay `queued` and their `detail` says why, for example "No private RescueCodec channel on this radio. Add a channel named RescueCodec with its own key, or pick a channel in the settings." The main screen shows the same text.

So name the team channel `RescueCodec` on every radio (same name, same key). A channel with no key or the one-byte default key (LongFast) counts as public. `Messenger.channel` is a `StateFlow<ChannelChoice>` and `ConnectionViewModel.pickChannel(index)` sets the choice (-1 = automatic).

## Permissions

| Android | Needed for Bluetooth | Also |
|---|---|---|
| 12 and newer (API 31+) | Nearby devices (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`) | Notifications from 13 (optional) |
| 8 to 11 (API 26 to 30) | Location (to scan) | |

A radio over the network (TCP) needs none of these. `ui/Permissions.kt` has the plan and the plain-English reasons.

## Screens, voice and screenshots

Build and run: `cd android && . ../scripts/env.sh && ./gradlew :app:assembleDebug`, then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

- English is the default. One tap on EN / DE in the top bar switches the app and the decoded text. Strings are in `res/values` and `res/values-de`.
- `adb shell am start -n org.rescuecodec.app/.MainActivity --es screen STATS` opens a screen directly (REPORT, ACTIONS, FEED, STATS, RADIO, SETTINGS). Debug builds also take `--es connect 10.0.2.2:4403` to connect to the simulator.
- Speech models: see `docs/VOICE.md`. Install with `scripts/install-vosk-models.sh`, or import a zip in Settings, or build the full APK with `-PbundleVosk=true`.
- Screenshots of every screen, light and dark, English and German: `scripts/app-screenshots.sh` (starts the simulator and the emulator, writes `.overnight/gallery/app/`, stops them again; `--keep` leaves them running).

## Tests

Unit tests (JVM, SDK fake radio, no emulator):

```bash
cd android && . ../scripts/env.sh && ./gradlew :app:testDebugUnitTest
```

They need a JDK 21 or newer to run (the SDK jars are Java 21). The build looks for Homebrew's `openjdk@25`; set `RESCUECODEC_TEST_JAVA_HOME` otherwise.

End to end on the emulator against the Meshtastic simulator (Docker), plus the Room, Intent API and screen tests:

```bash
scripts/android-e2e.sh          # starts sim and emulator, runs everything, stops them
scripts/android-e2e.sh --keep   # leaves them running
```

`AppUiTest` (Compose UI tests: Report then Send, Accept then Done, SOS needs a hold, the language toggle) runs in the same task. `VoiceModelTest` runs the real Vosk models on recorded speech and is skipped when the models are not installed on the device (run `scripts/install-vosk-models.sh` first and start it with `adb shell am instrument`, because Gradle uninstalls the app and its models after a run).

`SimulatorE2ETest` sends a Found report over TCP (`10.0.2.2:4403`, SIM-FIELD). The Python peer `sim/android_e2e_peer.py` on SIM-BASE must receive identical bytes, answers Accept and Done, sends its own report and must receive the app's Accept.

## Not covered by simulation

Bluetooth pairing and PIN dialog, permission pop-ups on a real phone, battery saver killing the service, real radio timing and lost packets. These are on the hardware check list. The Bluetooth code path is written against the SDK's documented API and compiles, but it has not run against a real radio yet.
