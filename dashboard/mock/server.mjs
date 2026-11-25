#!/usr/bin/env node
// Mock RescueCodec gateway, written from spec/api/openapi.yaml. DEMO DATA ONLY.
// Usage: node mock/server.mjs [--port 8787] [--mayday] [--no-live] [--empty] [--static <dir>] [--map <dir>]
// Control while running: POST /mock/mayday, POST /mock/control {"radio":false,"fail":"503"|"429"|null}
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { WebSocketServer } from "ws";

const here = path.dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const flag = (n) => args.includes(`--${n}`);
const opt = (n, d) => { const i = args.indexOf(`--${n}`); return i >= 0 && args[i + 1] ? args[i + 1] : d; };
const PORT = Number(opt("port", process.env.MOCK_PORT ?? 8787));
const STATIC = path.resolve(opt("static", path.join(here, "../../rescuecodec/gateway/static")));
const MAPDIR = path.resolve(opt("map", path.join(here, "../../assets-offline/map")));
const GATEWAY = "!0badc0de";

// ---------------------------------------------------------------- airtime (same formula as the dashboard)
function airtime(payload) {
  const sf = 11, bw = 250000, tSym = 2 ** sf / bw;
  const pl = payload + 23;
  const n = 8 + Math.max(Math.ceil((8 * pl - 4 * sf + 28 + 16) / (4 * sf)) * 5, 0);
  return (16 + 4.25 + n) * tSym;
}

// ---------------------------------------------------------------- readable text (demo wording, not the spec renderer)
const EN = { child: "child", teen: "teenager", adult: "adult", senior: "senior", conscious: "conscious", confused: "confused", unconscious: "unconscious",
  hypothermia: "hypothermia", fracture: "fracture", exhaustion: "exhaustion", bleeding: "bleeding", head: "head injury",
  stretcher: "stretcher", medic: "medic", helicopter: "helicopter", warmth: "warmth", water: "water", rope: "rope", walks: "can walk", cannot_walk: "cannot walk" };
const DE = { child: "Kind", teen: "Jugendlicher", adult: "Erwachsener", senior: "Senior", conscious: "ansprechbar", confused: "verwirrt", unconscious: "bewusstlos",
  hypothermia: "Unterkühlung", fracture: "Knochenbruch", exhaustion: "Erschöpfung", bleeding: "Blutung", head: "Kopfverletzung",
  stretcher: "Trage", medic: "Sanitäter", helicopter: "Hubschrauber", warmth: "Wärme", water: "Wasser", rope: "Seil", walks: "gehfähig", cannot_walk: "nicht gehfähig" };
const RES_EN = { stretcher: "stretcher", helicopter: "helicopter", searchers: "searchers", dog_team: "dog team", medic: "medic", light: "lights" };
const RES_DE = { stretcher: "Trage", helicopter: "Hubschrauber", searchers: "Suchkräfte", dog_team: "Hundestaffel", medic: "Sanitäter", light: "Beleuchtung" };
const HAZ_EN = { rockfall: "rockfall", blocked_path: "blocked path", water: "high water", ice: "ice", hunting: "hunting in progress" };
const HAZ_DE = { rockfall: "Steinschlag", blocked_path: "Weg versperrt", water: "Hochwasser", ice: "Eis", hunting: "Jagd im Gange" };
const posTxt = (p) => (p ? ` Position ${p.lat.toFixed(5)}, ${p.lon.toFixed(5)}.` : "");
const posTxtDe = posTxt;

function render(m, names) {
  const p = m.pos;
  if (m.found) {
    const f = m.found;
    const en = [EN[f.age], EN[f.consciousness], ...(f.injuries ?? []).map((x) => EN[x]), ...(f.mobility ? [EN[f.mobility]] : []), ...(f.needs ?? []).map((x) => "needs " + EN[x])].filter(Boolean);
    const de = [DE[f.age], DE[f.consciousness], ...(f.injuries ?? []).map((x) => DE[x]), ...(f.mobility ? [DE[f.mobility]] : []), ...(f.needs ?? []).map((x) => "braucht " + DE[x])].filter(Boolean);
    const cnt = f.count && f.count > 1 ? `${f.count} persons found: ` : "Person found: ";
    const cntDe = f.count && f.count > 1 ? `${f.count} Personen gefunden: ` : "Person gefunden: ";
    const more = f.responders ? `, ${f.responders} more helpers needed` : "";
    const moreDe = f.responders ? `, ${f.responders} weitere Helfer benötigt` : "";
    return { en: cnt + en.join(", ") + more + "." + posTxt(p), de: cntDe + de.join(", ") + moreDe + "." + posTxtDe(p) };
  }
  if (m.mayday) {
    const r = m.mayday;
    const reason = { injured: ["injured", "verletzt"], lost: ["lost", "verirrt"], trapped: ["trapped", "eingeschlossen"], medical: ["medical emergency", "medizinischer Notfall"] }[r.reason] ?? ["emergency", "Notfall"];
    return { en: `MAYDAY: ${reason[0]}, ${(r.persons ?? 1) === 1 ? "1 team member" : (r.persons ?? 1) + " team members"}.` + posTxt(p), de: `MAYDAY: ${reason[1]}, ${(r.persons ?? 1) === 1 ? "1 Einsatzkraft" : (r.persons ?? 1) + " Einsatzkräfte"}.` + posTxtDe(p) };
  }
  if (m.request) {
    const r = m.request;
    return { en: `Request: ${r.count ?? 1} ${RES_EN[r.resource] ?? r.resource}, ${r.urgency ?? "soon"}.` + posTxt(p), de: `Anforderung: ${r.count ?? 1} ${RES_DE[r.resource] ?? r.resource}, ${{ now: "sofort", soon: "bald", later: "später" }[r.urgency ?? "soon"]}.` + posTxtDe(p) };
  }
  if (m.hazard) {
    const h = m.hazard;
    return { en: `Hazard: ${HAZ_EN[h.kind] ?? h.kind}, ${h.severity ?? "medium"} severity${h.radius ? `, radius ${h.radius} m` : ""}.` + posTxt(p),
      de: `Gefahr: ${HAZ_DE[h.kind] ?? h.kind}, Stufe ${{ low: "niedrig", medium: "mittel", high: "hoch" }[h.severity ?? "medium"]}${h.radius ? `, Radius ${h.radius} m` : ""}.` + posTxtDe(p) };
  }
  if (m.track) {
    const q = m.track.points.at(-1);
    return { en: `Track: ${m.track.points.length} points, last at ${q.lat.toFixed(5)}, ${q.lon.toFixed(5)}.`, de: `Spur: ${m.track.points.length} Punkte, zuletzt bei ${q.lat.toFixed(5)}, ${q.lon.toFixed(5)}.` };
  }
  if (m.sector) {
    const s = m.sector;
    const stEn = { cleared: "cleared", partial: `partly searched${s.coverage ? ` (${s.coverage}%)` : ""}`, blocked: "blocked" }[s.status];
    const stDe = { cleared: "abgesucht", partial: `teilweise abgesucht${s.coverage ? ` (${s.coverage}%)` : ""}`, blocked: "gesperrt" }[s.status];
    return { en: `Sector ${s.sector} ${stEn}.` + posTxt(p), de: `Sektor ${s.sector} ${stDe}.` + posTxtDe(p) };
  }
  if (m.ack) {
    const a = m.ack;
    const who = a.team ?? "a team";
    const st = { accepted: ["accepted", "angenommen"], declined: ["declined", "abgelehnt"], done: ["finished", "erledigt"] }[a.status];
    const eta = a.eta ? ` ETA ${a.eta} min.` : "";
    const etaDe = a.eta ? ` Ankunft in ${a.eta} Min.` : "";
    return { en: `${who} ${st[0]} task ${a.ref} from ${names(a.node)}.` + eta, de: `${who} hat Aufgabe ${a.ref} von ${names(a.node)} ${st[1]}.` + etaDe };
  }
  if (m.text) return { en: m.text.text, de: m.text.text };
  return { en: "Unknown message.", de: "Unbekannte Nachricht." };
}

// ---------------------------------------------------------------- state
const NODES = [
  { node: GATEWAY, num: 0x0badc0de, short_name: "ELZ", long_name: "Einsatzleitung Heltec V3", hw_model: "HELTEC_V3", role: "CLIENT", rssi: null, snr: null, hops_away: 0, battery_pct: 100, pos: { lat: 50.6862, lon: 7.2031 }, is_gateway: true },
  { node: "!a1b2c3d4", num: 0xa1b2c3d4, short_name: "T1K1", long_name: "Trupp 1 T1000-E", hw_model: "TRACKER_T1000_E", role: "CLIENT_MUTE", rssi: -97, snr: 6.25, hops_away: 0, battery_pct: 81, pos: { lat: 50.6704, lon: 7.2158 }, is_gateway: false },
  { node: "!c0ffee02", num: 0xc0ffee02, short_name: "T2W", long_name: "Trupp 2 T-Watch S3", hw_model: "LILYGO_TWATCH_S3", role: "CLIENT_MUTE", rssi: -108, snr: 2.5, hops_away: 1, battery_pct: 54, pos: { lat: 50.6761, lon: 7.2279 }, is_gateway: false },
  { node: "!5eed0003", num: 0x5eed0003, short_name: "T3K", long_name: "Trupp 3 T1000-E", hw_model: "TRACKER_T1000_E", role: "CLIENT_MUTE", rssi: -112, snr: -1.0, hops_away: 1, battery_pct: 17, pos: { lat: 50.6641, lon: 7.2087 }, is_gateway: false },
  { node: "!d09f0004", num: 0xd09f0004, short_name: "HUND", long_name: "Hundestaffel T1000-E", hw_model: "TRACKER_T1000_E", role: "CLIENT_MUTE", rssi: -101, snr: 4.0, hops_away: 0, battery_pct: 68, pos: { lat: 50.6823, lon: 7.2411 }, is_gateway: false },
];
const nodeName = (id) => NODES.find((n) => n.node === id)?.long_name ?? id;

const state = {
  seq: 0, messages: [], tasks: new Map(), counters: new Map(), started: Date.now(),
  control: { radio: true, fail: null }, gwId: 40,
};
const clients = new Set();
const bcast = (event, data) => { const s = JSON.stringify({ event, data }); for (const ws of clients) if (ws.readyState === 1) ws.send(s); };
const iso = (ms) => new Date(ms).toISOString().replace(/\.\d{3}Z$/, "Z");

function typeOf(m) { return Object.keys(m).find((k) => ["found", "track", "sector", "request", "hazard", "mayday", "ack", "text"].includes(k)) ?? "unknown"; }
const DEFAULT_PRIO = { mayday: "critical", found: "urgent", hazard: "urgent", ack: "urgent", request: "normal", text: "normal", sector: "routine", track: "routine" };
const SIZES = { found: 23, mayday: 15, request: 17, hazard: 19, sector: 11, ack: 12, track: 38 };

function addRecord({ from, message, direction = "in", at = Date.now(), rssi = null, snr = null, hops = 0, status, size }) {
  const type = typeOf(message);
  const hex = from.slice(1);
  const needsId = ["found", "request", "hazard", "mayday"].includes(type);
  if (needsId && message.id == null) { const n = (state.counters.get(from) ?? 0) + 1; state.counters.set(from, n); message = { id: n, ...message }; }
  const key = message.id != null && needsId ? `${hex}-${message.id}` : null;
  const taskKey = type === "ack" ? `${message.ack.node.slice(1)}-${message.ack.ref}` : key;
  const sz = size ?? (type === "track" ? 6 + 5 * message.track.points.length : type === "text" ? 3 + new TextEncoder().encode(message.text.text).length : SIZES[type] ?? 20);
  const rec = {
    seq: ++state.seq, key, direction, from, type, priority: message.priority ?? DEFAULT_PRIO[type] ?? "normal",
    status: status ?? (direction === "in" ? "received" : "queued"), error: null, time: iso(at), event_time: null,
    codec: { version: 2, size: sz, hex: "08" + (message.id ?? 0).toString(16).padStart(2, "0") + "1a".repeat(Math.max(0, sz - 2)).slice(0, (sz - 2) * 2) },
    radio: direction === "in" ? { packet_id: 3141592000 + state.seq, rssi, snr, hops, channel: 1, via: "sim" } : null,
    message, text: render(message, nodeName), task_key: taskKey,
  };
  state.messages.push(rec);
  if (needsId) {
    const task = {
      key, from, id: message.id, type, priority: rec.priority, state: "open", created: rec.time, updated: rec.time,
      pos: message.pos ?? null, text: rec.text, message_seq: rec.seq, acks: [],
    };
    state.tasks.set(key, task);
  } else if (type === "ack") {
    const t = state.tasks.get(taskKey);
    if (t) {
      t.acks.push({ by: from, status: message.ack.status, eta: message.ack.eta ?? null, team: message.ack.team ?? null, time: rec.time, seq: rec.seq });
      t.state = message.ack.status; t.updated = rec.time;
    }
  }
  return rec;
}
const emitRecord = (rec) => { bcast("message", rec); const t = rec.task_key && state.tasks.get(rec.task_key); if (t) bcast("task", t); };

// ---------------------------------------------------------------- seed
const MIN = 60_000;
function seed() {
  const now = Date.now();
  const T1 = "!a1b2c3d4", T2 = "!c0ffee02", T3 = "!5eed0003", DOG = "!d09f0004";
  const ago = (m) => now - m * MIN;
  // tracks (positions near Drachenfels and Oelberg, Siebengebirge)
  const walk = (from, lat, lon, steps, dLat, dLon, minAgo) => {
    for (let i = 0; i < steps; i++) {
      const pts = [0, 1, 2].map((j) => ({ lat: +(lat + (i * 3 + j) * dLat).toFixed(5), lon: +(lon + (i * 3 + j) * dLon).toFixed(5), s: j * 30 }));
      addRecord({ from, message: { time: "10:00", track: { points: pts } }, at: ago(minAgo - i * 2), rssi: -97 - i, snr: 5 - i * 0.4, hops: from === T2 ? 1 : 0 });
    }
  };
  walk(T1, 50.6669, 7.2092, 5, 0.00042, 0.00038, 54);
  walk(T2, 50.6718, 7.2236, 4, 0.00036, 0.00056, 50);
  walk(DOG, 50.6785, 7.2352, 4, 0.00034, 0.00052, 44);
  addRecord({ from: T2, message: { pos: { lat: 50.6735, lon: 7.2205 }, sector: { sector: 3, status: "cleared", coverage: 100 } }, at: ago(47), rssi: -108, snr: 2.5, hops: 1 });
  addRecord({ from: T1, message: { pos: { lat: 50.6679, lon: 7.2142 }, sector: { sector: 4, status: "partial", coverage: 60 } }, at: ago(45), rssi: -97, snr: 6.0 });
  addRecord({ from: DOG, message: { pos: { lat: 50.6812, lon: 7.2395 }, sector: { sector: 7, status: "blocked" } }, at: ago(43), rssi: -101, snr: 4.0 });
  addRecord({ from: T1, message: { pos: { lat: 50.6689, lon: 7.2121 }, hazard: { kind: "rockfall", severity: "high", radius: 90 } }, at: ago(40), rssi: -96, snr: 6.5 });
  const f1 = addRecord({ from: T3, message: { pos: { lat: 50.6652, lon: 7.2074 }, found: { count: 2, consciousness: "conscious", mobility: "walks", injuries: ["exhaustion"] } }, at: ago(62), rssi: -112, snr: -1, hops: 1 });
  const f2 = addRecord({ from: T1, message: { pos: { lat: 50.6702, lon: 7.2155 }, found: { age: "child", consciousness: "conscious", injuries: ["hypothermia", "fracture"], needs: ["stretcher"], responders: 2 } }, at: ago(33), rssi: -97, snr: 6.25 });
  addRecord({ from: T3, message: { ack: { ref: f1.message.id, node: T3, status: "accepted", eta: 5, team: "Trupp 3" } }, at: ago(60), rssi: -112, snr: -1, hops: 1 });
  addRecord({ from: T3, message: { ack: { ref: f1.message.id, node: T3, status: "done", team: "Trupp 3" } }, at: ago(48), rssi: -112, snr: -1, hops: 1 });
  addRecord({ from: T3, message: { ack: { ref: f2.message.id, node: T1, status: "accepted", eta: 15, team: "Trupp 3" } }, at: ago(31), rssi: -111, snr: -0.5, hops: 1 });
  addRecord({ from: T1, message: { pos: { lat: 50.6708, lon: 7.2168 }, request: { resource: "stretcher", count: 2, urgency: "now" } }, at: ago(25), rssi: -97, snr: 6.0 });
  addRecord({ from: DOG, message: { pos: { lat: 50.6821, lon: 7.2402 }, found: { age: "adult", consciousness: "confused", injuries: ["hypothermia", "exhaustion"], needs: ["warmth"] } }, at: ago(18), rssi: -101, snr: 4.5 });
  addRecord({ from: T2, message: { text: { text: "Treffpunkt jetzt am Gasthaus, nicht am Parkplatz" } }, at: ago(12), rssi: -108, snr: 3, hops: 1 });
  addRecord({ from: T2, message: { pos: { lat: 50.6779, lon: 7.2318 }, request: { resource: "searchers", count: 10, urgency: "soon" } }, at: ago(4), rssi: -107, snr: 2.75, hops: 1 });
  // our own outgoing: ELZ sent a text and accepted nothing yet
  const o = addRecord({ from: GATEWAY, direction: "out", message: { text: { text: "ELZ: alle Trupps Rückmeldung in 10 Minuten" } }, at: ago(9), status: "delivered" });
  void o;
  // a task Trupp 1 has declined
  const f3 = addRecord({ from: T2, message: { pos: { lat: 50.6741, lon: 7.2263 }, found: { age: "senior", consciousness: "conscious", mobility: "cannot_walk", needs: ["medic"] } }, at: ago(15), rssi: -108, snr: 2.5, hops: 1 });
  addRecord({ from: T1, message: { ack: { ref: f3.message.id, node: T2, status: "declined", team: "Trupp 1" } }, at: ago(14), rssi: -97, snr: 6.0 });
  // nodes: heard times
  NODES.forEach((n, i) => { n.last_heard = iso(ago(i === 0 ? 0 : [2, 4, 9, 1][i - 1] ?? 3)); });
  if (flag("mayday")) triggerMayday(false);
}
function triggerMayday(live = true) {
  const rec = addRecord({ from: "!a1b2c3d4", message: { pos: { lat: 50.6667, lon: 7.2086 }, mayday: { reason: "injured", persons: 1 } }, rssi: -95, snr: 7.0 });
  if (live) emitRecord(rec);
  return rec;
}
if (!flag("empty")) seed();

// ---------------------------------------------------------------- derived
function health() {
  return { status: state.control.radio ? "ok" : "degraded", version: "2.0.0-mock", spec_version: "2.0.0", uptime_s: Math.round((Date.now() - state.started) / 1000) + 3605,
    radio: state.control.radio ? { connected: true, kind: "sim", node: GATEWAY, port: 256, channel: "RescueCodec", region: "EU_868", preset: "LONG_FAST", firmware: "2.7.26" }
      : { connected: false, kind: "none", node: null, port: 256, channel: null, region: null, preset: null, firmware: null },
    mqtt: { connected: false } };
}
function stats() {
  let inN = 0, outN = 0, inB = 0, outB = 0, txt = 0, air = 0, hour = 0; const by = {};
  let last = null;
  for (const m of state.messages) {
    const b = m.codec.size;
    if (m.direction === "in") { inN++; inB += b; last = m.radio ?? last; } else { outN++; outB += b; air += airtime(b); if (Date.parse(m.time) > Date.now() - 3600_000) hour += airtime(b); }
    txt += new TextEncoder().encode(m.text.en).length;
    by[m.type] = { count: (by[m.type]?.count ?? 0) + 1, bytes: (by[m.type]?.bytes ?? 0) + b };
  }
  const tot = inB + outB;
  const M = (value, unit, source, note) => ({ value, unit, source, ...(note ? { note } : {}) });
  return {
    since: iso(state.started - 3600_000), messages_in: M(inN, "count", "measured"), messages_out: M(outN, "count", "measured"),
    bytes_in: M(inB, "B", "measured"), bytes_out: M(outB, "B", "measured"), text_equivalent_bytes: M(txt, "B", "measured", "UTF-8 length of text.en for the same messages"),
    size_ratio: M(tot ? +(txt / tot).toFixed(2) : null, "x", "measured"), airtime_out_s: M(+air.toFixed(2), "s", "estimated", "LONG_FAST, +23 B Meshtastic overhead per packet"),
    airtime_last_hour_pct: M(+((hour / 3600) * 100).toFixed(3), "%", "estimated"), airtime_limit_pct: M(10, "%", "measured", "EU 869.4-869.65 MHz rule"),
    last_rssi: M(last?.rssi ?? null, "dBm", "measured"), last_snr: M(last?.snr ?? null, "dB", "measured"), by_type: by,
  };
}
function geojson() {
  const features = [];
  for (const m of state.messages) {
    const p = m.message.pos;
    const props = (extra) => ({ seq: m.seq, key: m.key, type: m.type, from: m.from, priority: m.priority, state: (m.task_key && state.tasks.get(m.task_key)?.state) || null, acc: p?.acc ?? "gps", time: m.event_time ?? m.time, text_en: m.text.en, text_de: m.text.de, ...extra });
    if (m.type === "track") {
      const c = m.message.track.points.map((q) => [q.lon, q.lat]);
      features.push({ type: "Feature", geometry: c.length > 1 ? { type: "LineString", coordinates: c } : { type: "Point", coordinates: c[0] }, properties: props({}) });
    } else if (p && m.type !== "ack") features.push({ type: "Feature", geometry: { type: "Point", coordinates: [p.lon, p.lat] }, properties: props({}) });
  }
  return { type: "FeatureCollection", features };
}

// ---------------------------------------------------------------- ack
const MOVES = { open: ["accepted", "declined"], accepted: ["done", "declined"], declined: ["accepted"], done: [] };
function doAck(key, body) {
  const t = state.tasks.get(key);
  if (!t) return [404, { error: { code: "not_found", message: `no task ${key}`, field: null } }];
  if (!["accepted", "declined", "done"].includes(body?.status)) return [400, { error: { code: "invalid_value", message: "status must be accepted, declined or done", field: "status" } }];
  if (state.control.fail === "503" || !state.control.radio) return [503, { error: { code: "radio_unavailable", message: "no Meshtastic radio connected", field: null } }];
  if (state.control.fail === "429") return [429, { error: { code: "airtime_limit", message: "airtime budget used, send again in 4 min", field: null, retry_after_s: 240 } }];
  if (t.state !== body.status && !MOVES[t.state].includes(body.status)) return [409, { error: { code: "invalid_transition", message: `task is ${t.state}; cannot change to ${body.status}`, field: "status" } }];
  const msg = { ack: { ref: t.id, node: t.from, status: body.status, ...(body.eta ? { eta: body.eta } : {}), team: (body.team ?? "ELZ").slice(0, 12) } };
  const rec = addRecord({ from: GATEWAY, direction: "out", message: msg });
  bcast("message", rec); bcast("task", state.tasks.get(key));
  const step = (status, ms) => setTimeout(() => { rec.status = status; bcast("message_status", { seq: rec.seq, status, error: null, updated: iso(Date.now()) }); }, ms);
  step("sent", 700); step("delivered", 1900);
  return [202, { task: state.tasks.get(key), record: rec }];
}

// ---------------------------------------------------------------- http
const MIME = { ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".mjs": "text/javascript", ".css": "text/css", ".json": "application/json", ".svg": "image/svg+xml",
  ".woff2": "font/woff2", ".woff": "font/woff", ".pbf": "application/x-protobuf", ".pmtiles": "application/octet-stream", ".png": "image/png", ".webp": "image/webp" };
const json = (res, code, body, extra = {}) => { res.writeHead(code, { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*", ...extra }); res.end(JSON.stringify(body)); };

function serveFile(req, res, file) {
  let st; try { st = fs.statSync(file); } catch { return false; }
  if (!st.isFile()) return false;
  const type = MIME[path.extname(file)] ?? "application/octet-stream";
  const range = /bytes=(\d*)-(\d*)/.exec(req.headers.range ?? "");
  if (range) {
    const start = range[1] ? Number(range[1]) : 0; const end = range[2] ? Math.min(Number(range[2]), st.size - 1) : st.size - 1;
    res.writeHead(206, { "Content-Type": type, "Content-Range": `bytes ${start}-${end}/${st.size}`, "Accept-Ranges": "bytes", "Content-Length": end - start + 1 });
    fs.createReadStream(file, { start, end }).pipe(res);
  } else {
    res.writeHead(200, { "Content-Type": type, "Content-Length": st.size, "Accept-Ranges": "bytes", "Cache-Control": "no-cache" });
    fs.createReadStream(file).pipe(res);
  }
  return true;
}

function body(req) { return new Promise((resolve) => { let b = ""; req.on("data", (c) => (b += c)); req.on("end", () => { try { resolve(b ? JSON.parse(b) : {}); } catch { resolve(null); } }); }); }

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://x");
  const p = decodeURIComponent(url.pathname);
  if (req.method === "OPTIONS") { res.writeHead(204, { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "content-type", "Access-Control-Allow-Methods": "GET,POST,OPTIONS" }); return res.end(); }

  if (p === "/v2/health") return json(res, 200, health());
  if (p === "/v2/stats") return json(res, 200, stats());
  if (p === "/v2/nodes") return json(res, 200, { items: [...NODES].sort((a, b) => (b.last_heard ?? "").localeCompare(a.last_heard ?? "")) });
  if (p === "/v2/export.geojson") return json(res, 200, geojson(), { "Content-Type": "application/geo+json", "Content-Disposition": "attachment; filename=rescuecodec.geojson" });
  if (p === "/v2/tasks" && req.method === "GET") {
    const states = url.searchParams.get("state")?.split(",");
    const items = [...state.tasks.values()].filter((t) => !states || states.includes(t.state));
    const rank = { critical: 3, urgent: 2, normal: 1, routine: 0 };
    items.sort((a, b) => rank[b.priority] - rank[a.priority] || a.created.localeCompare(b.created));
    return json(res, 200, { items });
  }
  let m;
  if ((m = /^\/v2\/tasks\/([0-9a-f]{8}-\d+)$/.exec(p))) { const t = state.tasks.get(m[1]); return t ? json(res, 200, t) : json(res, 404, { error: { code: "not_found", message: `no task ${m[1]}`, field: null } }); }
  if ((m = /^\/v2\/tasks\/([0-9a-f]{8}-\d+)\/ack$/.exec(p)) && req.method === "POST") {
    const b = await body(req); if (!b) return json(res, 400, { error: { code: "bad_request", message: "not JSON", field: null } });
    const [code, out] = doAck(m[1], b); return json(res, code, out);
  }
  if (p === "/v2/messages" && req.method === "GET") {
    const since = Number(url.searchParams.get("since_seq") ?? 0); const limit = Math.min(1000, Number(url.searchParams.get("limit") ?? 100));
    const types = url.searchParams.get("type")?.split(","); const dir = url.searchParams.get("direction");
    const items = state.messages.filter((r) => r.seq > since && (!types || types.includes(r.type)) && (!dir || r.direction === dir)).slice(0, limit);
    return json(res, 200, { items, next_since_seq: items.length ? items.at(-1).seq : since });
  }
  if (p === "/v2/messages" && req.method === "POST") {
    const b = await body(req); if (!b?.message) return json(res, 400, { error: { code: "bad_request", message: "message missing", field: "message" } });
    if (!state.control.radio) return json(res, 503, { error: { code: "radio_unavailable", message: "no Meshtastic radio connected", field: null } });
    const rec = addRecord({ from: GATEWAY, direction: "out", message: b.message }); emitRecord(rec);
    setTimeout(() => { rec.status = "sent"; bcast("message_status", { seq: rec.seq, status: "sent", error: null, updated: iso(Date.now()) }); }, 600);
    return json(res, 202, rec);
  }
  if ((m = /^\/v2\/messages\/(\d+)$/.exec(p))) { const r = state.messages.find((x) => x.seq === Number(m[1])); return r ? json(res, 200, r) : json(res, 404, { error: { code: "not_found", message: "no such record", field: null } }); }

  // mock controls
  if (p === "/mock/mayday" && req.method === "POST") return json(res, 200, triggerMayday());
  if (p === "/mock/control" && req.method === "POST") {
    const b = (await body(req)) ?? {};
    if ("radio" in b) { state.control.radio = !!b.radio; bcast("health", health()); }
    if ("fail" in b) state.control.fail = b.fail;
    return json(res, 200, state.control);
  }

  // static files: the dashboard build, then the map folder
  let rel = p === "/" ? "/index.html" : p;
  if (rel.startsWith("/map/")) { if (serveFile(req, res, path.join(MAPDIR, rel.slice(5)))) return; }
  if (rel === "/config.json") return json(res, 200, { apiBase: "", mapBase: "map" });
  const file = path.join(STATIC, rel);
  if (file.startsWith(STATIC) && serveFile(req, res, file)) return;
  res.writeHead(404, { "Content-Type": "text/plain" }); res.end("not found");
});

const wss = new WebSocketServer({ noServer: true });
server.on("upgrade", (req, socket, head) => {
  const url = new URL(req.url, "http://x");
  if (url.pathname !== "/v2/stream") return socket.destroy();
  wss.handleUpgrade(req, socket, head, (ws) => {
    clients.add(ws);
    ws.on("close", () => clients.delete(ws));
    ws.on("message", (d) => { try { if (JSON.parse(String(d)).op === "ping") ws.send(JSON.stringify({ event: "pong", data: {} })); } catch { /* ignore */ } });
    ws.send(JSON.stringify({ event: "hello", data: { last_seq: state.seq, health: health() } }));
    const since = url.searchParams.get("since_seq");
    if (since != null) for (const r of state.messages) if (r.seq > Number(since)) ws.send(JSON.stringify({ event: "message", data: r }));
  });
});

// ---------------------------------------------------------------- live
setInterval(() => bcast("stats", stats()), 10_000);
if (!flag("no-live")) {
  let n = 0;
  const trk = { "!a1b2c3d4": [50.6669 + 5 * 3 * 0.00042, 7.2092 + 5 * 3 * 0.00038, 0.00042, 0.00038], "!c0ffee02": [50.6718 + 12 * 0.00036, 7.2236 + 12 * 0.00056, 0.00036, 0.00056] };
  setInterval(() => {
    n++;
    if (n % 4 === 0) {
      const mk = [
        { pos: { lat: 50.6676 + Math.random() * 0.01, lon: 7.2 + Math.random() * 0.03 }, found: { age: "adult", consciousness: "conscious", injuries: ["exhaustion"], needs: ["water"] } },
        { pos: { lat: 50.67 + Math.random() * 0.01, lon: 7.21 + Math.random() * 0.03 }, request: { resource: "dog_team", count: 1, urgency: "soon" } },
        { pos: { lat: 50.672 + Math.random() * 0.01, lon: 7.215 + Math.random() * 0.03 }, hazard: { kind: "blocked_path", severity: "medium", radius: 40 } },
      ][n % 3];
      const from = ["!a1b2c3d4", "!c0ffee02", "!d09f0004"][n % 3];
      emitRecord(addRecord({ from, message: mk, rssi: -95 - (n % 15), snr: 6 - (n % 5), hops: from === "!c0ffee02" ? 1 : 0 }));
    } else {
      const from = n % 2 ? "!a1b2c3d4" : "!c0ffee02";
      const t = trk[from];
      const pts = [0, 1, 2].map((j) => ({ lat: +(t[0] + j * t[2]).toFixed(5), lon: +(t[1] + j * t[3]).toFixed(5), s: j * 30 }));
      t[0] += 3 * t[2]; t[1] += 3 * t[3];
      emitRecord(addRecord({ from, message: { time: "10:00", track: { points: pts } }, rssi: -97, snr: 5, hops: from === "!c0ffee02" ? 1 : 0 }));
    }
    const nd = NODES[1 + (n % 4)]; nd.last_heard = iso(Date.now()); bcast("node", nd);
  }, 15_000);
}

server.listen(PORT, "127.0.0.1", () => console.log(`mock gateway on http://127.0.0.1:${PORT}  (static: ${STATIC})`));
