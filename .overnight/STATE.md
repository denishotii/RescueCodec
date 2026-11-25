
## setup (Wed 01:51)
- setup-android: done, review none, open: slow first build (~30 min on ~300 KB/s net, ~1 min after); Meshtastic SDK targets Java 21 but build uses JDK 17; no CI/lint in android/; emulator/package.xml hand-written, do not run sdkmanager --update on emulator package
- setup-sim: done, review none, open: sim has no radio range/airtime (protocol/app tests only, real devices in hil/); pinned 2.7.26 vs devices 2.8.x (multicast 239.0.0.69, not mixed); nodes restart once on first configure, up.sh waits; emulator uses 10.0.2.2; scripts/ and ORCHESTRATION.md/STATE.json from other jobs
- setup-assets: done, review none, open: Protomaps glyphs/sprites not downloaded (use symbol-free layers or add glyph set); dashboard must show attributions (c) OpenStreetMap contributors and (c) Mapterhorn; terrain-siebengebirge-z14 extract 2 MB vs 9 MB scratch, check detail; basemap build 20261122 in manifest.json/OFFLINE.md, re-fetch picks newer
