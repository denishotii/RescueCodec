import type { Map as MlMap } from "maplibre-gl";
import { toUtm } from "../lib/geo";

export interface Tick { pos: number; label: string; major: boolean }
export interface Ticks { x: Tick[]; y: Tick[]; zone: string }

function stepFor(metersPerPx: number): number {
  const target = metersPerPx * 140; // about one tick per 140 px
  for (const s of [100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000, 100000]) if (s >= target) return s;
  return 100000;
}

const fmt = (v: number, step: number) => {
  const km = v / 1000;
  const two = String(Math.floor(km) % 100).padStart(2, "0");
  return step >= 1000 ? two : `${two}.${String(Math.round((v % 1000) / 100))}`;
};

/** Grid ticks for the sheet edge. Walks the bottom and left edge, reads UTM there, and finds the round numbers. */
export function computeTicks(map: MlMap): Ticks {
  const c = map.getCanvas();
  const w = c.clientWidth, h = c.clientHeight;
  const centre = map.getCenter();
  const zone = Math.floor((centre.lng + 180) / 6) + 1;
  const band = toUtm(centre.lat, centre.lng, zone).band;
  const z = map.getZoom();
  const mpp = (156543.03 * Math.cos((centre.lat * Math.PI) / 180)) / 2 ** z;
  const step = stepFor(mpp);

  const x: Tick[] = [];
  const y: Tick[] = [];
  const sample = (px: number, py: number) => { const ll = map.unproject([px, py]); return toUtm(ll.lat, ll.lng, zone); };

  let prev = sample(0, h - 1).easting;
  for (let px = 8; px <= w; px += 8) {
    const cur = sample(px, h - 1).easting;
    const a = Math.floor(prev / step), b = Math.floor(cur / step);
    if (b > a) {
      const val = b * step;
      const frac = (val - prev) / (cur - prev);
      x.push({ pos: px - 8 + frac * 8, label: fmt(val, step), major: (val / step) % 5 === 0 });
    }
    prev = cur;
  }
  let prevN = sample(0, 0).northing;
  for (let py = 8; py <= h; py += 8) {
    const cur = sample(0, py).northing;
    // northing falls as y grows
    const a = Math.floor(prevN / step), b = Math.floor(cur / step);
    if (b < a) {
      const val = a * step;
      const frac = (prevN - val) / (prevN - cur);
      y.push({ pos: py - 8 + frac * 8, label: fmt(val, step), major: (val / step) % 5 === 0 });
    }
    prevN = cur;
  }
  return { x, y, zone: `UTM ${zone}${band}` };
}
