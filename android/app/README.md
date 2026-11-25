# RescueCodec Android app (`:app`)

Licence: GPL-3.0-or-later (it links the official Meshtastic Kotlin SDK, which is GPL-3.0). `:codec` and `:engine` stay Apache-2.0.

This module holds the radio and data layer. The screens are minimal on purpose. The UI job replaces `ui/MinimalScreen.kt` and keeps the view models.

## Layers

| Package | What it does |
|---|---|
| `radio/` | `ConnectionManager` (states `Disconnected`, `Scanning`, `Connecting`, `Connected`, `Error`; retry with backoff 1 s to 60 s), `RadioTarget` (TCP or Bluetooth address), `LastRadioStore`, transports and the Bluetooth scanner. |
| `core/` | `Messenger` (send queue from `:engine`, packet handling, tasks, Accept and Done), `AppSettings`. |
| `data/` | Room database: `messages` (every packet with its state), `tasks`, `stats`. |
| `service/` | `RadioService`: foreground service, type `connectedDevice`, keeps the link alive with the screen off. |
| `intent/` | Public Intent API `org.rescuecodec.action.SEND_REPORT`. See `docs/INTEGRATE.md`, section "Android Intent". |
| `ui/` | View models (`StateFlow`), permission plan, minimal screen. |

`AppGraph` creates and holds all of it. `RescueApp` owns one.

## How a message travels

1. `Messenger.submit(message)` encodes it with `:codec`, writes a row (`queued`) and puts it into the `SendQueue`.
2. The send loop takes the next message that fits the airtime budget (Mayday first) and sends it as a `PRIVATE_APP` (256) broadcast on the team channel, with `want_ack` and a Meshtastic priority.
3. The radio answers: `sent`, `delivered` (a neighbour repeated it), `not_confirmed`, or `refused_airtime` (the radio's own limit). Refused messages are tried again later.
4. Incoming packets on port 256 are decoded, checked for duplicates and written as `received`. Reports become tasks, Acks change task states.
5. `Messenger.answer(key, "accepted" | "declined" | "done")` sends an Ack and applies it at once.

Everything is in the database, so a restart loses nothing: the queue, the tasks, the duplicate memory and the used airtime are rebuilt.

## Permissions

| Android | Needed for Bluetooth | Also |
|---|---|---|
| 12 and newer (API 31+) | Nearby devices (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`) | Notifications from 13 (optional) |
| 8 to 11 (API 26 to 30) | Location (to scan) | |

A radio over the network (TCP) needs none of these. `ui/Permissions.kt` has the plan and the plain-English reasons.

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

`SimulatorE2ETest` sends a Found report over TCP (`10.0.2.2:4403`, SIM-FIELD). The Python peer `sim/android_e2e_peer.py` on SIM-BASE must receive identical bytes, answers Accept and Done, sends its own report and must receive the app's Accept.

## Not covered by simulation

Bluetooth pairing and PIN dialog, permission pop-ups on a real phone, battery saver killing the service, real radio timing and lost packets. These are on the hardware check list. The Bluetooth code path is written against the SDK's documented API and compiles, but it has not run against a real radio yet.
