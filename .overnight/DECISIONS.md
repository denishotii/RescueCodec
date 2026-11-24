# Decisions log (newest last). One line each: time, who, decision, why.

- Wed 01:20, Claude: Wire format v2 is protobuf (proto3), decided by Denis. v1 packets stay decodable (first byte 0x01).
- Wed 01:20, Claude: Monorepo layout: spec/ (proto, vocab, i18n, vectors, api), rescuecodec/ (Python reference, engine, gateway), android/ (Gradle: :codec, :engine pure JVM; :app Android), ts/ (TypeScript lib), dashboard/ (Vite + React + MapLibre), sim/ (meshtasticd Docker), hil/ (real-device checks), docs/.
- Wed 01:20, Claude: Licences: Apache-2.0 for spec and libraries; GPL-3.0 for the Android app because the official Meshtastic Kotlin SDK is GPL-3.0.
- Wed 01:20, Claude: Overnight there is no real hardware. Everything is tested on real Meshtastic firmware in Docker (meshtasticd) plus the Android emulator. Real devices on Wednesday morning (HARDWARE_CHECK.md).
- Wed 01:20, Claude: UI language: English by default with a one-tap German toggle; decoded messages can be shown in German and English.
- Wed 01:20, Claude: Radio defaults: EU_868, LONG_FAST, private channel, port PRIVATE_APP 256, broadcast on the channel.
- Wed 01:20, Claude: Models: Opus for the spec and final real-device review, Sonnet for building, reviewing and fixing, Haiku for commits and the morning brief, Codex MCP for an independent final code review.
- Wed 01:20, Claude: Git: one local commit per feature on branch v2-overnight; never push (Denis pushes).
