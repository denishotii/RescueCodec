
## setup (Wed 01:51)
- setup-android: done, review none, open: slow first build (~30 min on ~300 KB/s net, ~1 min after); Meshtastic SDK targets Java 21 but build uses JDK 17; no CI/lint in android/; emulator/package.xml hand-written, do not run sdkmanager --update on emulator package
- setup-sim: done, review none, open: sim has no radio range/airtime (protocol/app tests only, real devices in hil/); pinned 2.7.26 vs devices 2.8.x (multicast 239.0.0.69, not mixed); nodes restart once on first configure, up.sh waits; emulator uses 10.0.2.2; scripts/ and ORCHESTRATION.md/STATE.json from other jobs
- setup-assets: done, review none, open: Protomaps glyphs/sprites not downloaded (use symbol-free layers or add glyph set); dashboard must show attributions (c) OpenStreetMap contributors and (c) Mapterhorn; terrain-siebengebirge-z14 extract 2 MB vs 9 MB scratch, check detail; basemap build 20261122 in manifest.json/OFFLINE.md, re-fetch picks newer

## spec (Wed 02:03)
- spec: done, review pass, commit ba6adf3 already contains all spec paths (no new commit needed). Open: no Python rule-based extractor yet (belongs to build-python / J8); uk/tr/ar i18n are machine drafts, need native review; OpenAPI enums are strict (unknown_<n>/bit_<n> not modelled); generated pb2 needs protobuf>=6.33; port 38 (PAGING_APP) only described in SPEC.md, nothing requested from Meshtastic; a818c2c swept in early spec drafts (history not rewritten).
- Relayed question on Android toolchain download/duration: not answered by the spec step; orchestrator should answer from setup state.

## build-ts (Wed 02:12)
- ts-lib: done (already committed as 7de428e), review: pass, open issues: no LICENSE file yet (add Apache-2.0 before npm publish); strict UTF-8 decode uses raw-byte scan because protobuf-es replaces bad bytes; Level 3 extraction not implemented in TS; gen.sh needs protoc (brew), src/gen and i18n-data.ts committed; not published to npm; build uses lib DOM only for TextEncoder/TextDecoder

## build-dashboard (Wed 02:31)
- dashboard: done, review: pass, commit 5f1ca03 (contains all dashboard paths; no new commit needed). Open: not tested against the real gateway (rescuecodec/gateway has no cli.py/app yet; re-run with RC_API=http://127.0.0.1:8000 npm run dev once it exists; needs /v2 + WebSocket same origin or apiBase in static/config.json, and Range requests for static/map/*.pmtiles); static/map is gitignored, run build or scripts/fetch-offline-assets.sh on demo laptop; sector marker is a 250 m square (no sector polygon in API), 3D uses z12 terrain only; PRODUCT.md inferred from brief, DESIGN.md hand-written, finish-reviewer not run; not tested on projector from 10 m, room view raises font ~30%, Accepted/Done lanes need scroll at 900 px height; stale team label in localStorage (rescuecodec.dashboard.v2) can survive, default 'Leitstelle'.

## build-python (Wed 02:48)
- python-engine: partial; review: fixed 1 serious issue, fixer partial; open: shared sar.yaml urgency phrases added; Kotlin Extractor needs DECISIONS rules, person_nouns found-cue, hazard other from avalanche words; Kotlin tests not run; Ollama tested with fake model only (check qwen3:8b); gateway still must call rescuecodec.engine; proto HAZARD_ICE comment stale
- gateway: done; review: pass; open: USB/Heltec V3 path untested (mocks only); want_ack broadcast ends failed/max_retransmit, word as no relay confirmed; RSSI/SNR null on sim; CoT multicast iface check on LAN; no password on API (127.0.0.1 default); run pip install -e . once for console script

## build-android (Wed 05:06)
- kotlin-core: done; review pass; open: 54/54 is dev-set only, held-out unmeasured (weak: coords need 3+ decimals, 'Kollege bewusstlos' no reason, 'ca. 8 Jahre' ignored); EXTRAS not in sar.yaml; tracker refines spec s15 (newest answer, DONE final); SendQueue/tracker timeouts are defaults; protobuf+snakeyaml need internet or warm cache; app Engine.describe() unverified, :app not compiled
- android-radio: done; review fixed 1 serious, fixer done; open: BLE untested on hardware; USB transport not implemented; no GPS position; no Mayday/Found notification; airtime estimate ignores up-to-3 repeats; task keys use old node if radio switched; unit tests need JDK 21+; INTEGRATE.md has only Intent section; EngineTest.kt deletion predates job; FGS restart/battery untested; Intent API on by default; unused import in SimulatorE2ETest
- android-ui: done; review pass; open: live hold-to-talk unverified (no mic on emulator); no speech models in default APK; QR scanner and channel apply untested on hardware; BT dialogs untested; channel texts English; SOS uses last known position only; only 2 quick-phrase sets checked; TalkBack/gesture check open

## integrate (Wed 06:32)
- integrate: done (already committed as 018a6c4, no new commit); review: none; open: cold-start 'No radio' not reproduced; text dedupe drops identical resend within 10 min (spec 14); text fallback 51 bytes vs 36 shown; resend-after-silent-drop has no unit test (emulator only); real-device BT/PIN/permissions/battery/radio/USB Heltec untested until Wed; e2e-full.sh needs playwright-cli (PLAYWRIGHT_CLI or npm i -g @playwright/cli).
- note: .overnight/ORCHESTRATION.md and STATE.json modified by other jobs, not committed here.

## design (Wed 06:57)
- design-polish | done | critique: 12 issues | open: (1) logo mark not added to app status chip; (2) German feed filter chips still wrap to two rows at 1440 px; (3) app grid ref uses system monospace, JetBrains Mono not bundled (Android needs ttf, only woff/woff2 available); (4) mobile Mayday layout and sticky Accept bar not screenshotted with active Mayday; (5) dashboard/scripts/gallery.sh needs playwright-cli (not installed), screenshots taken with playwright-core script in scratchpad, not in repo
