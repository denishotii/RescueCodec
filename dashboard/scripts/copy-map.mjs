// Copies the offline map (PMTiles + glyphs) next to the built dashboard, so the gateway can serve it from one folder.
// Source: assets-offline/map (override with RC_MAP_DIR). Target: rescuecodec/gateway/static/map (gitignored).
// If you would rather serve the map from another path, set "mapBase" in static/config.json instead.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const src = path.resolve(process.env.RC_MAP_DIR ?? path.join(root, "assets-offline/map"));
const dst = path.join(root, "rescuecodec/gateway/static/map");

if (!fs.existsSync(src)) {
  console.warn(`copy-map: ${src} not found. The dashboard will run without a base map. Run scripts/fetch-offline-assets.sh first.`);
  process.exit(0);
}
fs.rmSync(dst, { recursive: true, force: true });
fs.mkdirSync(dst, { recursive: true });
let bytes = 0;
for (const f of fs.readdirSync(src)) {
  const from = path.join(src, f);
  if (f.endsWith(".build-date") || f === "manifest.json" || f.endsWith(".pmtiles") || f === "glyphs") {
    fs.cpSync(from, path.join(dst, f), { recursive: true });
    bytes += fs.statSync(from).isDirectory() ? 0 : fs.statSync(from).size;
  }
}
console.log(`copy-map: ${src} -> ${dst} (${(bytes / 1e6).toFixed(1)} MB of tiles)`);
