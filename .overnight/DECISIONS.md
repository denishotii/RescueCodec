# Decisions log (newest last). One line each: time, who, decision, why.

- Wed 01:20, Claude: Wire format v2 is protobuf (proto3), decided by Denis. v1 packets stay decodable (first byte 0x01).
- Wed 01:20, Claude: Monorepo layout: spec/ (proto, vocab, i18n, vectors, api), rescuecodec/ (Python reference, engine, gateway), android/ (Gradle: :codec, :engine pure JVM; :app Android), ts/ (TypeScript lib), dashboard/ (Vite + React + MapLibre), sim/ (meshtasticd Docker), hil/ (real-device checks), docs/.
- Wed 01:20, Claude: Licences: Apache-2.0 for spec and libraries; GPL-3.0 for the Android app because the official Meshtastic Kotlin SDK is GPL-3.0.
- Wed 01:20, Claude: Overnight there is no real hardware. Everything is tested on real Meshtastic firmware in Docker (meshtasticd) plus the Android emulator. Real devices on Wednesday morning (HARDWARE_CHECK.md).
- Wed 01:20, Claude: UI language: English by default with a one-tap German toggle; decoded messages can be shown in German and English.
- Wed 01:20, Claude: Radio defaults: EU_868, LONG_FAST, private channel, port PRIVATE_APP 256, broadcast on the channel.
- Wed 01:20, Claude: Models: Opus for the spec and final real-device review, Sonnet for building, reviewing and fixing, Haiku for commits and the morning brief, Codex MCP for an independent final code review.
- Wed 01:20, Claude: Git: one local commit per feature on branch v2-overnight; never push (Denis pushes).
- Wed 00:55, setup-sim: Simulator = 3x meshtasticd 2.7.26.54e0d8d (pinned) with `Lora: Module: sim` + `EnableUDP` (UDP multicast 224.0.0.69 on one compose bridge); real firmware routing and crypto, no extra bridge process, ~5 MB RAM per node.
- Wed 00:55, setup-sim: Node config (EU_868, LONG_FAST, channel RescueCodec, owner names) is set through the Python TCP API in sim/configure.py, not YAML, because the firmware YAML cannot set channels; fixed MACs keep node IDs stable.
- Wed 00:55, setup-sim: venv pins: meshtastic 2.7.11, protobuf 7.36.2, fastapi 0.165.0 + starlette 1.10.0, pydantic 2.14.4 (old FastAPI/Starlette mismatch gone, 17 tests pass).
- Wed 01:55, setup-android: Gradle 9.8.0 + AGP 9.4.1 + Kotlin 2.4.10 (not Gradle 8), compileSdk 37.1, targetSdk 35, why: Meshtastic SDK 0.2.0 is built with Kotlin 2.4/AGP 9 and its AAR needs compileSdk 37; Compose 1.13 needs 37.1.
- Wed 01:55, setup-android: Meshtastic SDK 0.2.0 is GPL-3.0-or-later, so the app module is GPL when it links it; :codec and :engine stay Apache-2.0 and must never depend on the SDK.
