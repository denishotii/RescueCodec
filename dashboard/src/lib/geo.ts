// WGS84 to UTM, used for the grid references German rescue teams read on paper maps.
export interface Utm { zone: number; band: string; easting: number; northing: number }

const A = 6378137.0;
const F = 1 / 298.257223563;
const K0 = 0.9996;
const E2 = F * (2 - F);
const EP2 = E2 / (1 - E2);
const BANDS = "CDEFGHJKLMNPQRSTUVWX";

export function toUtm(lat: number, lon: number, forceZone?: number): Utm {
  const zone = forceZone ?? Math.floor((lon + 180) / 6) + 1;
  const lon0 = ((zone - 1) * 6 - 180 + 3) * (Math.PI / 180);
  const phi = (lat * Math.PI) / 180;
  const lam = (lon * Math.PI) / 180;
  const N = A / Math.sqrt(1 - E2 * Math.sin(phi) ** 2);
  const T = Math.tan(phi) ** 2;
  const C = EP2 * Math.cos(phi) ** 2;
  const Aa = Math.cos(phi) * (lam - lon0);
  const e4 = E2 * E2, e6 = e4 * E2;
  const M = A * ((1 - E2 / 4 - (3 * e4) / 64 - (5 * e6) / 256) * phi
    - ((3 * E2) / 8 + (3 * e4) / 32 + (45 * e6) / 1024) * Math.sin(2 * phi)
    + ((15 * e4) / 256 + (45 * e6) / 1024) * Math.sin(4 * phi)
    - ((35 * e6) / 3072) * Math.sin(6 * phi));
  const easting = K0 * N * (Aa + ((1 - T + C) * Aa ** 3) / 6 + ((5 - 18 * T + T * T + 72 * C - 58 * EP2) * Aa ** 5) / 120) + 500000;
  let northing = K0 * (M + N * Math.tan(phi) * (Aa ** 2 / 2 + ((5 - T + 9 * C + 4 * C * C) * Aa ** 4) / 24
    + ((61 - 58 * T + T * T + 600 * C - 330 * EP2) * Aa ** 6) / 720));
  if (lat < 0) northing += 10000000;
  const band = BANDS[Math.max(0, Math.min(19, Math.floor((lat + 80) / 8)))];
  return { zone, band, easting, northing };
}

/** "32U 385640 5613420": zone, band, then easting and northing in whole metres. */
export function utmLabel(lat: number, lon: number): string {
  const u = toUtm(lat, lon);
  return `${u.zone}${u.band} ${Math.round(u.easting)} ${Math.round(u.northing)}`;
}

export function degLabel(lat: number, lon: number): string {
  return `${lat.toFixed(5)}, ${lon.toFixed(5)}`;
}

/** Approximate inverse: metres east/north of a point to lon/lat (fine for sizes under a few km). */
export function offsetMeters(lat: number, lon: number, dxEast: number, dyNorth: number): [number, number] {
  const dLat = dyNorth / 111320;
  const dLon = dxEast / (111320 * Math.cos((lat * Math.PI) / 180));
  return [lon + dLon, lat + dLat];
}

export function circlePolygon(lat: number, lon: number, radiusM: number, steps = 48): [number, number][] {
  const ring: [number, number][] = [];
  for (let i = 0; i <= steps; i++) {
    const a = (i / steps) * Math.PI * 2;
    ring.push(offsetMeters(lat, lon, Math.cos(a) * radiusM, Math.sin(a) * radiusM));
  }
  return ring;
}

export function squarePolygon(lat: number, lon: number, sideM: number): [number, number][] {
  const h = sideM / 2;
  return [
    offsetMeters(lat, lon, -h, -h), offsetMeters(lat, lon, h, -h),
    offsetMeters(lat, lon, h, h), offsetMeters(lat, lon, -h, h), offsetMeters(lat, lon, -h, -h),
  ];
}
