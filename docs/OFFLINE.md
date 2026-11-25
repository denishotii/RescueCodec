# Offline assets and downloads

Things to download once while online. After that the product runs with no internet. Big files live in gitignored folders (`assets-offline/`, `models/`, `dist/`).

Quick check that this Mac is ready: `hil/preflight.sh`.

## Docker images
- `meshtastic/meshtasticd:2.7.26.54e0d8d` (simulator, `sim/`). Pull with `docker pull`. About 100 MB.

## How to re-fetch
Run `scripts/fetch-offline-assets.sh` once with internet. It skips files that exist. Needs `curl`, `unzip`, `npm` and `pmtiles` (`brew install pmtiles`). Everything lands in `assets-offline/` (gitignored).

## Speech models (assets-offline/vosk/)
| Asset | Size | Source | Licence |
|---|---|---|---|
| vosk-model-small-de-0.15 (zip + unpacked) | 45 MB zip, about 50 MB unpacked | https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip | Apache 2.0 |
| vosk-model-small-en-us-0.15 (zip + unpacked) | 40 MB zip, about 40 MB unpacked | https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip | Apache 2.0 |

Speech models are **not** inside the default APK. See `docs/VOICE.md` for the three ways onto the phone: `scripts/install-vosk-models.sh`, Settings > Import model (zip), or `./gradlew :app:assembleDebug -PbundleVosk=true` (full APK, about +85 MB).

## Android app resources (committed in the repo, no download needed at runtime)
| Asset | Size | Source | Licence |
|---|---|---|---|
| Barlow and Barlow Condensed (6 TTF files in `android/app/src/main/res/font/`) | 0.6 MB | https://github.com/google/fonts/tree/main/ofl/barlow | SIL OFL 1.1 (`android/app/licenses/Barlow-OFL.txt`) |
| Material icons (35 vector drawables in `res/drawable/ic_*.xml`) | 30 KB | https://github.com/google/material-design-icons | Apache-2.0 |
| Vosk Android library 0.3.75 (Maven, linked into the APK) | 13 MB | https://repo1.maven.org/maven2/com/alphacephei/vosk-android/ | Apache-2.0 |
| CameraX 1.7.0 and ZXing core 3.5.4 (Maven, QR scanner) | 1.5 MB | Google Maven, Maven Central | Apache-2.0 |

## Map data (assets-offline/map/, bbox 6.80,50.60,7.35,51.00: Koeln, Bonn and the Siebengebirge)
| Asset | Size | Source | Licence and attribution |
|---|---|---|---|
| basemap.pmtiles (vector, Protomaps schema, z0-14, build 20261122) | 30 MB | https://build.protomaps.com/20261122.pmtiles (via `pmtiles extract`) | ODbL. Show "© OpenStreetMap contributors" on the map. |
| terrain.pmtiles (terrarium webp 512 px, z0-12) | 8 MB | https://download.mapterhorn.com/planet.pmtiles (via `pmtiles extract`) | Mapterhorn, mixed open sources. Show "© Mapterhorn" and link https://mapterhorn.com/attribution |
| terrain-siebengebirge-z14.pmtiles (detail, bbox 7.15,50.635,7.292,50.725, z0-14) | 2 MB | same as above | same as above |
| glyphs/ (Noto Sans Regular, Medium, Italic, ranges 0-255 and 256-511) | 0.7 MB | https://protomaps.github.io/basemaps-assets/fonts/ | SIL OFL 1.1 (Noto Sans) |

`manifest.json` lists these files. Both extracts need the protocol `pmtiles://` and the pmtiles JS library. Terrain encoding is `terrarium`, tileSize 512.
Labels use the local glyphs above (Latin only). No sprite sheet: the dashboard drops POI, road shield and one-way layers.

## Web libraries (assets-offline/web/)
| Asset | Size | Source | Licence |
|---|---|---|---|
| maplibre-gl 6.19.0 (ESM: maplibre-gl.mjs, -shared.mjs, -worker.mjs, css) | 1.5 MB | npm `maplibre-gl@6.19.0` | BSD-3-Clause |
| pmtiles 4.5.0 (pmtiles.js, IIFE global `pmtiles`) | 0.1 MB | npm `pmtiles@4.5.0` | BSD-3-Clause |

## Local services
- Mosquitto (Homebrew, 2.1.4): `scripts/mqtt-up.sh` and `scripts/mqtt-down.sh`. Listens on 127.0.0.1:1883 only. Not a background service.
- pmtiles CLI (Homebrew, 1.31.2).
# Build toolchain downloads

Only needed to build the Android app and the libraries again. Not needed to run the gateway, the dashboard or an installed APK.

## Android toolchain (outside the repo, in ~/Library/Android/sdk)

- Android command-line tools 13114758 (dl.google.com, about 143 MB)
- platform-tools 37.0.3, build-tools 35.0.0, platforms android-35 and android-37.1
- Emulator 37.3.10 (about 420 MB)
- System image google_apis arm64-v8a, API 35 r9 (about 1.8 GB)
- AVD "rescue" (pixel_7), created in ~/.android/avd
- Source it with: `. scripts/env.sh`
- Emulator: `scripts/emulator-up.sh` and `scripts/emulator-down.sh`

Note: on a slow network sdkmanager can stall. The emulator and system image were
installed by hand (download the zip, check its SHA-1, unzip into the SDK folder,
add a package.xml for the emulator).

## Gradle and Maven dependencies (in ~/.gradle)

- Gradle 9.8.0 (wrapper in android/), about 130 MB
- AGP 9.4.1, Kotlin 2.4.10, Compose BOM 2026.11.01
- Meshtastic Kotlin SDK 0.2.0 (org.meshtastic:sdk-core, sdk-transport-ble, sdk-transport-tcp)
- To build offline later: `cd android && ./gradlew --offline :app:assembleDebug`

## Gateway API docs (committed in rescuecodec/gateway/docs/)
- Swagger UI `swagger-ui-dist@5.32.1` (swagger-ui-bundle.js, swagger-ui.css, favicon, LICENSE, NOTICE), about 1.7 MB, Apache-2.0. Source: npm `swagger-ui-dist@5.32.1`. Served by the gateway at `/docs`, no CDN.

## Build outputs (gitignored)

| File | Size | How it is made |
|---|---|---|
| `dist/RescueCodec-debug.apk` | 51 MB | `. scripts/env.sh && cd android && ./gradlew :app:assembleDebug`, then copy `android/app/build/outputs/apk/debug/app-debug.apk` to `dist/RescueCodec-debug.apk`. Install: `adb install -r dist/RescueCodec-debug.apk`. No speech models inside (see above). |
| `rescuecodec/gateway/static/` | 2.5 MB without map, 42 MB with | `cd dashboard && npm install && npm run build`. The copied map files in `static/map/` are gitignored. |
| `ts/dist/` | small | `cd ts && npm install && npm run build` |

## Python and Node packages

- Python: `pip install -e '.[gateway,test]'` (fastapi, uvicorn, pydantic, websockets, pyyaml, protobuf, meshtastic, paho-mqtt, pytest). Everything is cached in `.venv/` after the first install.
- Node: `npm install` in `dashboard/` and `ts/` (cached in `node_modules/`, gitignored).
- `protoc` (Homebrew) is only needed to regenerate protobuf code (`scripts/gen-proto.sh`, `ts/scripts/gen.sh`). The generated files are committed.

## Licence texts

`LICENSE` (Apache-2.0, repo root and in `spec/`, `ts/`, `android/codec/`, `android/engine/`) and `android/app/LICENSE` (GPL-3.0) are the official texts (Apache text from the Swagger UI copy in `rescuecodec/gateway/docs/`, GPL text from gnu.org). The Meshtastic Kotlin SDK is GPL-3.0, which is why the app is GPL-3.0 (see `README.md`).
