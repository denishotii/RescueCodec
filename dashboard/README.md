# RescueCodec dashboard

The command-post view for the RescueCodec gateway. Vite, React, TypeScript, MapLibre GL.
It uses only the public API in `spec/api/openapi.yaml` (REST and the `/v2/stream` WebSocket).
Anything it does, your own software can do too.

It works with the network cut: fonts, map libraries, map tiles and map glyphs are all local files.

## What you see
- **Mayday banner.** Red, full width, above everything. Accept or Done from the banner.
- **Actions.** Open, accepted and done tasks. Accept asks for a team label (12 bytes) and an arrival time. Done is one tap.
- **Live reports.** Every decoded message as a card: type, readable text, time, sender, size, RSSI, SNR, hops. EN/DE toggle in the top bar. Outgoing messages show queued, sent, delivered or failed.
- **Map.** Offline base map and terrain shading. Finds, requests, Mayday, hazards (with radius), sector squares, tracks, radios. 2D by default, "3D terrain" tilts the map. The map edge shows UTM grid ticks, and every position is also given as a UTM grid reference.
- **Traffic.** Bytes on air vs the same text, airtime saved, delivered vs sent, last RSSI and SNR, airtime in the last hour. Each number is tagged **measured** (solid outline) or **estimated** (dashed outline).
- **Radios.** Nodes with battery, signal, hops, last heard.
- **Send.** Under the reports. Pick a channel (the team channel is the default) or one person (a direct message). Type one sentence: while you type, the gateway (`POST /v2/extract`) shows what goes on air, for example "Sends as: Person found · 10 B (as text 65 B)" with the facts as chips, or "Text · 37 B". "Send as plain text" sends the words as they are; direct messages and public channels always do. Quick buttons: Recall all, Status check, Meet at …, + Request, + Hazard. Enter sends, Shift+Enter makes a new line. The message shows up in the feed with its status; a rate limit (429) says how long to wait.
- **Connection.** Gateway link, radio, MQTT. When the gateway is out of reach the page says so, keeps the last data and pauses the action buttons.

Room view (the TV button) makes everything larger for a projector. Light and dark themes follow the system and can be switched.

## Run it
```bash
cd dashboard
npm install
npm run mock          # fake gateway on http://127.0.0.1:8787 (demo data), serves the built dashboard too
npm run dev           # Vite on http://localhost:5173, proxies /v2 to RC_API (default http://127.0.0.1:8000)
RC_API=http://127.0.0.1:8787 npm run dev     # develop against the mock
npm run build         # writes rescuecodec/gateway/static/ and copies the map files into static/map/
npm test              # unit tests and the contract test
```

Mock options: `node mock/server.mjs --port 8787 --mayday --no-live --empty`.
While it runs: `curl -XPOST localhost:8787/mock/mayday` fires a Mayday. `curl -XPOST localhost:8787/mock/control -d '{"radio":false}'` removes the radio. `-d '{"fail":"429"}'` makes every Accept fail with the airtime limit. `-d '{"fail":null}'` resets it.
The mock is demo data only. It is checked against the OpenAPI schemas in `tests/contract.test.ts`.

## What the gateway must serve
- `rescuecodec/gateway/static/` at `/`. Everything is relative, so a sub-path also works.
- The same origin for `/v2/*` and the WebSocket. For another origin, put `{"apiBase": "http://host:8000"}` in `static/config.json`, or open the page with `?api=http://host:8000`.
- `static/map/*.pmtiles` with HTTP Range requests (Starlette's `StaticFiles` does this). Change the folder with `{"mapBase": "/some/path"}` in `config.json`.
- `static/map/glyphs/<font>/<range>.pbf` for map labels (Noto Sans, copied from `assets-offline/map/glyphs`).

`static/map/` is gitignored. `npm run build` copies it from `assets-offline/map` (override with `RC_MAP_DIR`). Without the map files the dashboard still works, with a blank map.

## URL options
`?theme=dark|light`, `?lang=de|en`, `?room=1`, `?view=3d`, `?api=<gateway url>`.

## Screenshots
`scripts/gallery.sh [base-url]` writes 1920x1080 and 1440x900 shots, dark and light, to `docs/screenshots/dashboard/` (needs `playwright-cli`).

## Files
- `src/api/` types written from the OpenAPI file, REST client.
- `src/state/store.ts` loads the lists, then follows the WebSocket (`since_seq` replay on reconnect, backoff, ping).
- `src/map/` map style (offline PMTiles, hillshade, 3D terrain), GeoJSON built from public records, UTM edge ticks.
- `src/lib/compose.ts` the Send panel's rules: targets, preview line, which call a send becomes (unit tested).
- `src/lib/airtime.ts` LoRa LONG_FAST airtime formula (matches the examples in the spec). Used for the estimated airtime saving.
- `mock/server.mjs` the mock gateway.
