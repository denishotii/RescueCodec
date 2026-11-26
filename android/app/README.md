# RescueCodec Android app (`:app`)

Licence: GPL-3.0-or-later (it links the official Meshtastic Kotlin SDK, which is GPL-3.0). `:codec` and `:engine` stay Apache-2.0.

This module holds the radio and data layer, and the screens and voice on top of it. Design notes: `DESIGN.md` in this folder. Voice: `docs/VOICE.md`.

## Layers

| Package | What it does |
|---|---|
| `radio/` | `ConnectionManager` (states `Disconnected`, `Scanning`, `Connecting`, `Connected`, `Error`; retry with backoff 1 s to 60 s), `RadioTarget` (TCP or Bluetooth address), `LastRadioStore`, transports and the Bluetooth scanner. |
| `core/` | `Messenger` (send queue from `:engine`, packet handling, tasks, Accept and Done), `Channels` (the radio's channels by name, where a packet belongs), `TextRoutes` (the text mode of each chat, and who was heard running RescueCodec), `Chats` (`ChatId`, `ChatList`, `NodeDirectory` for node names, `ChatReads` for unread counts), `AppSettings`, `UiPrefs` (language, day/night, sounds), `ReportFields` (chip editing), `Labels` (words from spec/i18n), `TeamAlerts` (which new message notifies, and how), `LocationHelper`. |
| `data/` | Room database (version 3): `messages` (every packet with its state, channel and DM peer), `tasks` (with the channel of the report), `stats`, `nodes_seen` (which node was heard on which port) and `chat_modes` (the text mode of each chat). `Migrations` holds the SQL of each schema change. |
| `service/` | `RadioService`: foreground service, type `connectedDevice`, keeps the link alive with the screen off. `MaydayNotifier` (loud Mayday channel). `TeamWatcher` and `TeamNotifier`: read new rows from the database and post Messages, Tasks and Answers notifications, or an in-app tone when the app is in front. |
| `intent/` | Public Intent API `org.rescuecodec.action.SEND_REPORT`. The switch is in Settings, Other apps, "Let other apps send reports" (on by default). See `docs/INTEGRATE.md`, section "Android Intent". |
| `ui/` | `RescueRoot` (theme, language, top bar, three tabs, Settings), `chat/` (Team chat: bubbles, report cards, answer lines, composer with preview strip), `screens/` (Tasks, Stats, Connect, Settings, QR scanner, report editor sheets), `components/` (cards, chips, SOS and mic hold controls, tags), `theme/`, view models (`StateFlow`; `ChatViewModel` takes a `ChatId`, `ChatListViewModel` builds the chat list), permission plan. |
| `voice/` | `SpeechInput` (Vosk, hold to talk), `VoskModels` and `ModelImporter` (where the models are, import from a zip), `Speaker` (Android TextToSpeech). |

`AppGraph` creates and holds all of it. `RescueApp` owns one.

## How a message travels

1. `Messenger.submit(message)` encodes it with `:codec`, writes a row (`queued`) and puts it into the `SendQueue`.
2. The send loop takes the next message that fits the airtime budget (Mayday first, one budget for all channels) and sends it as a `PRIVATE_APP` (256) broadcast on the message's channel, with `want_ack` and a Meshtastic priority. A direct message goes packed on port 256 when the peer runs RescueCodec, else as a port 1 text, with `to` = that node.
3. The radio answers: `sent`, `delivered` (a neighbour repeated it), `not_confirmed`, or `refused_airtime` (the radio's own limit). Refused messages are tried again later.
4. Incoming packets on port 256 are decoded, checked for duplicates and written as `received`, with their channel. Reports become tasks, Acks change task states.
5. `Messenger.answer(key, "accepted" | "declined" | "done")` sends an Ack on the report's channel and applies it at once.
6. Normal Meshtastic texts (port 1, spec section 2.1), for example from the official app, are stored as `text` rows with `source = "meshtastic"` (`MeshText.isPlain(row)`, shown with a small "via Meshtastic"). The same packet twice and readable copies that start with `[RC] ` are dropped as duplicates.
7. A message with only a text goes packed on port 256 or as a normal port 1 text (spec section 2.3). Each chat has a text mode in its ⋮ menu: Auto, Always compressed or Always normal. Auto packs a direct message when the peer runs RescueCodec, and a channel text when RescueCodec nodes and no stock Meshtastic user were heard there in the last 24 h. A public channel always gets normal text. A normal text carries only the words (at most 200 bytes, no position). Both ways use the same queue, priority, airtime budget and `want_ack`. Reports always go on port 256.
8. Settings "Use compact encoding for text messages" (`AppSettings.compactText`, off by default) and "Team vocabulary" (Auto by default, shown when compact is on): in a chat that packs, a text goes in the strictly smallest form, for example a compact frame (`0x02`) or a domain frame (`0x03`). With compact on, free texts go without the automatic GPS fix, so they can use a frame without an Envelope. Reports keep their position.

Everything is in the database, so a restart loses nothing: the queue, the tasks, the duplicate memory and the used airtime are rebuilt.

## Channels and direct messages

The Chats tab is a chat list, like Telegram: the team channel pinned on top, then every other private channel of the radio, then direct messages (one per node, with the name from the radio's node list), then the public channel only when Settings "Show public channel" is on. The app opens straight into the last chat; back goes to the list.

- A channel is identified by its **name** (shared with the gateway). An unnamed primary channel is the modem preset's name, for example `LongFast`. A channel with no key or the one-byte default key is **public**.
- Receive: port 256 and port 1 on every private channel; the public channel only with the setting on (else counted as `rx_other_channel` and dropped); direct messages to our node on any channel, always.
- Send: on the open chat's channel. A message for a channel that is not on the radio waits (its `detail` says why) and never blocks the others. Answers go on the report's channel. SOS always goes on the team channel.
- **Direct messages and the public channel carry text only.** The composer does no report extraction there and adds no position; `Messenger.submit` refuses a report with `peer` set (`direct_text_only`) or on a public channel (`public_channel`). Reports belong on a private channel.
- Joining by QR code or link **adds** the link's private channels into free secondary slots. It never touches the primary, skips public channels, updates the key of a secondary with the same name, and sets region and modem settings only when the radio has no region. The confirm screen lists each step first (`ChannelJoin.plan`).
- Notifications name the channel (or "Direct message") and the sender, and a tap opens that chat. Tasks and Maydays open Tasks.

## Which channel is the team channel

Reports from the SOS button, the Intent API and the team chat go out on one private channel, never on the public default channel. `core/ChannelChoice.kt` decides:

1. The person picked a channel on the Radio tab: that one, if it exists on the radio and has its own key.
2. Else the enabled channel named `RescueCodec`, if it has its own key.
3. Else the only enabled channel with its own key.
4. Else nothing is sent. Messages stay `queued` and their `detail` says why, for example "No private RescueCodec channel on this radio. Add a channel named RescueCodec with its own key, or pick a channel in the settings." The Radio tab shows the same text.

So name the team channel `RescueCodec` on every radio (same name, same key). A channel with no key or the one-byte default key (LongFast) counts as public. `Messenger.channel` is a `StateFlow<ChannelChoice>` and `ConnectionViewModel.pickChannel(index)` sets the choice (-1 = automatic).

## Permissions

| Android | Needed for Bluetooth | Also |
|---|---|---|
| 12 and newer (API 31+) | Nearby devices (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`) | Notifications from 13 (optional) |
| 8 to 11 (API 26 to 30) | Location (to scan) | |

A radio over the network (TCP) needs none of these. `ui/Permissions.kt` has the plan and the plain-English reasons.

## Screens, voice and screenshots

Build and run: `cd android && . ../scripts/env.sh && ./gradlew :app:assembleDebug`, then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

- English is the default. Settings, Language switches the app and the decoded text to German. Strings are in `res/values` and `res/values-de`.
- The app has three tabs: Chats, Tasks and Radio (the link, the channels with New channel and Join by QR, and the airtime numbers). Settings opens from the gear in the top bar. See `DESIGN.md`. Screenshots: `docs/screenshots/app/`.
- `adb shell am start -n org.rescuecodec.app/.MainActivity --es screen TASKS` opens a screen directly (CHATS, TASKS, RADIO, SETTINGS; the old names TEAM, REPORT and FEED open Chats, ACTIONS opens Tasks, STATS opens Radio). Debug builds also take `--es connect 10.0.2.2:4403` to connect to the simulator, and `--es report_text_b64 <base64>` to type into the composer.
- Notifications: "Messages" (texts from the team), "Tasks" (found, request, hazard), "Mayday" (loud), "Answers" (Accept or Done on your own report). Only when the app is not in front; in front a short tone and a haptic tick (Settings, Sounds).
- Speech models: see `docs/VOICE.md`. Install with `scripts/install-vosk-models.sh`, or import a zip in Settings, or build the full APK with `-PbundleVosk=true`.
- Screenshots of every screen, light and dark, English and German: `scripts/app-screenshots.sh` (starts the simulator and the emulator, writes `docs/screenshots/app/`, stops them again; `--keep` leaves them running). It still uses the old screen names of the first app layout (Report, Actions, Feed), so check its output before you commit it.

## Tests

Unit tests (JVM, SDK fake radio, no emulator):

```bash
cd android && . ../scripts/env.sh && ./gradlew :app:testDebugUnitTest
```

`ChannelsTest` covers two private channels landing in two chats, the hidden public channel, direct messages, sending on the open chat's channel and Acks on the report's channel; `ChannelJoinTest` the join plan (adds, never replaces); `MigrationTest` runs the Room migration SQL on a real SQLite (sqlite-jdbc) against the exported schemas in `schemas/`.

They need a JDK 21 or newer to run (the SDK jars are Java 21). The build looks for Homebrew's `openjdk@25`; set `RESCUECODEC_TEST_JAVA_HOME` otherwise.

End to end on the emulator against the Meshtastic simulator (Docker), plus the Room, Intent API and screen tests:

```bash
scripts/android-e2e.sh          # starts sim and emulator, runs everything, stops them
scripts/android-e2e.sh --keep   # leaves them running
```

`AppUiTest` (Compose UI tests: type a report in the composer then Send, Accept then Done on Tasks, SOS needs a hold, the EN/DE toggle) runs in the same task. `VoiceModelTest` runs the real Vosk models on recorded speech and is skipped when the models are not installed on the device (run `scripts/install-vosk-models.sh` first and start it with `adb shell am instrument`, because Gradle uninstalls the app and its models after a run).

`SimulatorE2ETest` sends a Found report over TCP (`10.0.2.2:4403`, SIM-FIELD). The Python peer `sim/android_e2e_peer.py` on SIM-BASE must receive identical bytes, answers Accept and Done, sends its own report and must receive the app's Accept.

## Not covered by simulation

Bluetooth pairing and PIN dialog, permission pop-ups on a real phone, battery saver killing the service, real radio timing and lost packets. These are on the hardware check list. Bluetooth has run with real radios: on 25 Nov 2026 the phones reached their radios over Bluetooth, and the gateway's `--ble` reached the base radio. No automated test covers it.
