// RescueCodec v2 codec: canonical JSON message <-> wire bytes.
// Port of the Python reference (rescuecodec/v2/codec.py). Same rules, same error codes.
import { create, fromBinary, toBinary } from "@bufbuild/protobuf";
import { BinaryReader } from "@bufbuild/protobuf/wire";
import * as C from "./codes.js";
import { CodecError, type ErrorCode } from "./errors.js";
import { EnvelopeSchema, type Envelope } from "./gen/rescuecodec/v2/rescuecodec_pb.js";
import { MAX_UNPACKED_BYTES, PackError, knownDict, packText, unpackText } from "./textpack.js";
import {
  CURRENT_WORD_DICT, MAX_WORDS_TEXT_BYTES, WORD_DICTS, WordsError, decodeWords, encodeWords, wordDictAvailable,
} from "./textwords.js";
import type { RescueMessage } from "./types.js";

type Obj = Record<string, unknown>;

const err = (code: ErrorCode, message: string, field: string | null = null) => new CodecError(code, message, field);

const UNKNOWN_RE = /^unknown_(\d{1,5})$/;
const BIT_RE = /^bit_(\d{1,2})$/;
const TIME_RE = /^([01]\d|2[0-3]):([0-5]\d)$/;
const NODE_RE = /^!([0-9a-f]{8})$/;

const utf8 = new TextEncoder();
const byteLength = (s: string) => utf8.encode(s).length;

function isObj(v: unknown): v is Obj {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

function hasLoneSurrogate(s: string): boolean {
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    if (c >= 0xd800 && c <= 0xdbff) {
      const d = s.charCodeAt(i + 1);
      if (d >= 0xdc00 && d <= 0xdfff) i++;
      else return true;
    } else if (c >= 0xdc00 && c <= 0xdfff) return true;
  }
  return false;
}

function int(value: unknown, field: string, lo: number, hi: number): number {
  if (typeof value !== "number" || !Number.isInteger(value)) throw err("invalid_value", "must be an integer", field);
  if (value < lo || value > hi) throw err("invalid_value", `must be ${lo}..${hi}`, field);
  return value;
}

function valuesOf(table: C.Table): Set<number> {
  return new Set(Object.values(table));
}

function enumToWire(value: unknown, table: C.Table, field: string, max = 0x7fffffff): number {
  if (typeof value === "string") {
    if (Object.hasOwn(table, value)) return table[value];
    const m = UNKNOWN_RE.exec(value);
    if (m) {
      const n = Number(m[1]);
      if (n > 0 && n <= max && !valuesOf(table).has(n)) return n;
    }
  }
  throw err("invalid_value", `unknown value ${JSON.stringify(value)}`, field);
}

function enumToJson(n: number, table: C.Table): string {
  for (const [name, v] of Object.entries(table)) if (v === n) return name;
  return `unknown_${n}`;
}

function flagsToWire(values: unknown, table: C.Table, field: string): number {
  if (!Array.isArray(values)) throw err("invalid_value", "must be a list of names", field);
  let mask = 0;
  for (const value of values) {
    if (typeof value === "string" && Object.hasOwn(table, value)) {
      mask |= 1 << table[value];
      continue;
    }
    const m = typeof value === "string" ? BIT_RE.exec(value) : null;
    if (m && Number(m[1]) < 32 && !valuesOf(table).has(Number(m[1]))) {
      mask |= 1 << Number(m[1]);
      continue;
    }
    throw err("invalid_value", `unknown flag ${JSON.stringify(value)}`, field);
  }
  return mask >>> 0;
}

function flagsToJson(mask: number, table: C.Table): string[] {
  const byBit = new Map(Object.entries(table).map(([name, bit]) => [bit, name]));
  const out: string[] = [];
  for (let bit = 0; bit < 32; bit++) if ((mask >>> bit) & 1) out.push(byBit.get(bit) ?? `bit_${bit}`);
  return out;
}

/**
 * Degrees to integer degrees x 1e5, rounding half away from zero on the decimal text
 * (so 50.123455 gives 5012346 and not a float artefact).
 */
export function degToE5(value: unknown, field: string): number {
  if (typeof value !== "number") throw err("invalid_value", "must be a number", field);
  if (!Number.isFinite(value)) throw err("invalid_value", "must be a finite number", field);
  const m = /^(-?)(\d+)(?:\.(\d+))?(?:e([+-]?\d+))?$/i.exec(String(value));
  if (!m) throw err("invalid_value", "must be a number", field);
  const frac = m[3] ?? "";
  const mantissa = BigInt(m[2] + frac);
  const shift = Number(m[4] ?? 0) + 5 - frac.length;
  if (shift > 20) return m[1] ? -1e15 : 1e15; // far out of range, rejected by the caller
  let q: bigint;
  if (shift >= 0) q = mantissa * 10n ** BigInt(shift);
  else {
    const d = 10n ** BigInt(-shift);
    q = (mantissa * 2n + d) / (d * 2n); // half up on the absolute value
  }
  const n = Number(q);
  return m[1] && n !== 0 ? -n : n;
}

export function e5ToDeg(value: number): number {
  return value / 100000;
}

export function nodeToJson(num: number): string {
  return "!" + (num >>> 0).toString(16).padStart(8, "0");
}

export function nodeFromJson(value: unknown, field = "ack.node"): number {
  if (typeof value === "string") {
    const m = NODE_RE.exec(value);
    if (m) return parseInt(m[1], 16);
  }
  throw err("invalid_value", 'must look like "!a1b2c3d4"', field);
}

function checkKeys(obj: unknown, allowed: readonly string[], field: string): Obj {
  if (!isObj(obj)) throw err("invalid_value", "must be an object", field);
  for (const key of Object.keys(obj)) {
    if (!allowed.includes(key)) throw err("unknown_key", `unknown key ${JSON.stringify(key)}`, field ? `${field}.${key}` : key);
  }
  return obj;
}

function checkLatLon(lat: number, lon: number, field: string): void {
  if (lat < -9_000_000 || lat > 9_000_000) throw err("invalid_value", "latitude must be -90..90", `${field}.lat`);
  if (lon < -18_000_000 || lon > 18_000_000) throw err("invalid_value", "longitude must be -180..180", `${field}.lon`);
}

// ------------------------------------------------------------ JSON -> Envelope

type Body = NonNullable<Envelope["body"]>;

function buildFound(b0: unknown): Body {
  const b = checkKeys(b0, ["count", "age", "consciousness", "breathing", "mobility", "injuries", "needs", "responders"], "found");
  let state = 0;
  for (const [name, shift, width, table] of C.STATE_LAYOUT) {
    if (name in b) state |= enumToWire(b[name], table, `found.${name}`, (1 << width) - 1) << shift;
  }
  return {
    case: "found",
    value: {
      state,
      injuries: "injuries" in b ? flagsToWire(b.injuries, C.INJURIES, "found.injuries") : 0,
      needs: "needs" in b ? flagsToWire(b.needs, C.NEEDS, "found.needs") : 0,
      count: "count" in b ? int(b.count, "found.count", 0, C.MAX_COUNT) : 0,
      responders: "responders" in b ? int(b.responders, "found.responders", 0, C.MAX_COUNT) : 0,
    },
  } as Body;
}

function buildTrack(b0: unknown): Body {
  const b = checkKeys(b0, ["points"], "track");
  const points = b.points;
  if (!Array.isArray(points) || points.length === 0) throw err("missing_field", "a track needs 1 or more points", "track.points");
  if (points.length > C.MAX_TRACK_POINTS) throw err("invalid_value", `at most ${C.MAX_TRACK_POINTS} points per packet`, "track.points");
  const lat: number[] = [], lon: number[] = [], dt: number[] = [];
  let withTime: boolean | null = null;
  let prevLat = 0, prevLon = 0, prevS = 0;
  points.forEach((p0, i) => {
    const field = `track.points[${i}]`;
    const p = checkKeys(p0, ["lat", "lon", "s"], field);
    if (!("lat" in p) || !("lon" in p)) throw err("missing_field", "a point needs lat and lon", field);
    const la = degToE5(p.lat, `${field}.lat`), lo = degToE5(p.lon, `${field}.lon`);
    checkLatLon(la, lo, field);
    lat.push(la - prevLat);
    lon.push(lo - prevLon);
    prevLat = la;
    prevLon = lo;
    const hasS = "s" in p;
    if (withTime === null) withTime = hasS;
    else if (hasS !== withTime) throw err("invalid_value", "give s on every point or on none", "track.points");
    if (hasS) {
      const s = int(p.s, `${field}.s`, 0, 86_400);
      if (s < prevS) throw err("invalid_value", "s must not go down", `${field}.s`);
      dt.push(s - prevS);
      prevS = s;
    }
  });
  return { case: "track", value: { lat, lon, dt } } as Body;
}

function buildSector(b0: unknown): Body {
  const b = checkKeys(b0, ["sector", "status", "coverage"], "sector");
  return {
    case: "sector",
    value: {
      sector: "sector" in b ? int(b.sector, "sector.sector", 0, C.MAX_SECTOR) : 0,
      status: "status" in b ? enumToWire(b.status, C.SECTOR_STATUS, "sector.status") : 0,
      coverage: "coverage" in b ? int(b.coverage, "sector.coverage", 0, 100) : 0,
    },
  } as Body;
}

function buildRequest(b0: unknown): Body {
  const b = checkKeys(b0, ["resource", "count", "urgency"], "request");
  return {
    case: "request",
    value: {
      resource: "resource" in b ? enumToWire(b.resource, C.RESOURCE, "request.resource") : 0,
      count: "count" in b ? int(b.count, "request.count", 0, C.MAX_COUNT) : 0,
      urgency: "urgency" in b ? enumToWire(b.urgency, C.URGENCY, "request.urgency") : 0,
    },
  } as Body;
}

function buildHazard(b0: unknown): Body {
  const b = checkKeys(b0, ["kind", "severity", "radius"], "hazard");
  return {
    case: "hazard",
    value: {
      kind: "kind" in b ? enumToWire(b.kind, C.HAZARD, "hazard.kind") : 0,
      severity: "severity" in b ? enumToWire(b.severity, C.SEVERITY, "hazard.severity") : 0,
      radius: "radius" in b ? int(b.radius, "hazard.radius", 0, C.MAX_RADIUS_M) : 0,
    },
  } as Body;
}

function buildMayday(b0: unknown): Body {
  const b = checkKeys(b0, ["reason", "persons"], "mayday");
  return {
    case: "mayday",
    value: {
      reason: "reason" in b ? enumToWire(b.reason, C.MAYDAY_REASON, "mayday.reason") : 0,
      persons: "persons" in b ? int(b.persons, "mayday.persons", 0, C.MAX_COUNT) : 0,
    },
  } as Body;
}

function buildAck(b0: unknown): Body {
  const b = checkKeys(b0, ["ref", "node", "status", "eta", "team"], "ack");
  let team = "";
  if ("team" in b) {
    if (typeof b.team !== "string") throw err("invalid_value", "must be a string", "ack.team");
    team = b.team;
  }
  return {
    case: "ack",
    value: {
      ref: "ref" in b ? int(b.ref, "ack.ref", 0, C.MAX_ID) : 0,
      node: "node" in b ? nodeFromJson(b.node) : 0,
      status: "status" in b ? enumToWire(b.status, C.ACK_STATUS, "ack.status") : 0,
      eta: "eta" in b ? int(b.eta, "ack.eta", 0, C.MAX_ETA_MIN) : 0,
      team,
    },
  } as Body;
}

function buildText(b0: unknown): Body {
  const b = checkKeys(b0, ["text", "dict", "words"], "text");
  if ("dict" in b && !knownDict(int(b.dict, "text.dict", 1, 0xffffffff))) {
    throw err("invalid_value", "unknown dictionary, known: [1]", "text.dict");
  }
  if ("words" in b) {
    // A compact text: the same words as 2-byte word tokens with the shared word dictionary (spec section 3.6).
    if ("dict" in b) throw err("invalid_value", "give dict or words, not both", "text.words");
    const version = int(b.words, "text.words", 1, 0xffffffff);
    if (!Object.hasOwn(WORD_DICTS, version) || !wordDictAvailable(version)) {
      throw err("invalid_value", `unknown word dictionary, known: [${Object.keys(WORD_DICTS).join(", ")}]`, "text.words");
    }
    let words: Uint8Array = new Uint8Array(0);
    let wordsDict = 0;
    if ("text" in b) {
      if (typeof b.text !== "string") throw err("invalid_value", "must be a string", "text.text");
      if (hasLoneSurrogate(b.text)) throw err("invalid_value", "must be valid Unicode", "text.text");
      const size = byteLength(b.text);
      if (size > 0) {
        if (size > MAX_WORDS_TEXT_BYTES) throw err("invalid_value", `a compact text is at most ${MAX_WORDS_TEXT_BYTES} bytes`, "text.text");
        words = encodeWords(b.text, version);
        wordsDict = version;
      }
    }
    return { case: "text", value: { text: "", deflate: new Uint8Array(0), dict: 0, words, wordsDict } } as Body;
  }
  let text = "";
  let deflate: Uint8Array = new Uint8Array(0);
  let dict = 0;
  if ("text" in b) {
    if (typeof b.text !== "string") throw err("invalid_value", "must be a string", "text.text");
    if (hasLoneSurrogate(b.text)) throw err("invalid_value", "must be valid Unicode", "text.text");
    const size = byteLength(b.text);
    if (!("dict" in b)) text = b.text;
    else if (size > 0) {
      // A packed text: the same words as raw DEFLATE with the shared dictionary (spec section 3.5).
      if (size > MAX_UNPACKED_BYTES) throw err("invalid_value", `a packed text is at most ${MAX_UNPACKED_BYTES} bytes`, "text.text");
      dict = b.dict as number;
      deflate = packText(b.text, dict);
    }
  }
  return { case: "text", value: { text, deflate, dict } } as Body;
}

type TextBody = { text: string; deflate: Uint8Array; dict: number; words: Uint8Array; wordsDict: number };

/** The words of a Text body: `text`, `deflate` unpacked or `words` decoded. Throws CodecError. */
export function unpackedText(t: TextBody): string {
  if (t.words.length > 0) {
    try {
      return decodeWords(t.words, t.wordsDict);
    } catch (e) {
      if (e instanceof WordsError) throw err(e.code, e.message, "text.words");
      throw err("malformed", e instanceof Error ? e.message : String(e), "text.words");
    }
  }
  if (t.deflate.length === 0) return t.text;
  try {
    return unpackText(t.deflate, t.dict);
  } catch (e) {
    if (e instanceof PackError) throw err(e.code, e.message, "text.deflate");
    throw err("malformed", e instanceof Error ? e.message : String(e), "text.deflate");
  }
}

const BUILDERS: Record<C.BodyType, (b: unknown) => Body> = {
  found: buildFound, track: buildTrack, sector: buildSector, request: buildRequest,
  hazard: buildHazard, mayday: buildMayday, ack: buildAck, text: buildText,
};

/** Build a validated Envelope from a canonical JSON message. Throws CodecError. */
export function toProto(msg: unknown): Envelope {
  const m = checkKeys(msg, ["v", "id", "priority", "pos", "time", ...C.BODY_TYPES], "");
  if ("v" in m && m.v !== 2) throw err("invalid_value", "only v=2 can be encoded", "v");
  const bodies = C.BODY_TYPES.filter((k) => k in m);
  if (bodies.length === 0) throw err("no_body", "a message needs exactly one body (found, track, ...)");
  if (bodies.length > 1) throw err("multiple_bodies", `only one body allowed, got ${bodies.join(", ")}`);
  const kind = bodies[0];
  const init: Obj = {};
  if ("id" in m) init.id = int(m.id, "id", 0, C.MAX_ID);
  if ("priority" in m) init.priority = enumToWire(m.priority, C.PRIORITY, "priority");
  if ("pos" in m) {
    const pos = checkKeys(m.pos, ["lat", "lon", "acc"], "pos");
    if (!("lat" in pos) || !("lon" in pos)) throw err("missing_field", "pos needs lat and lon", "pos");
    const lat = degToE5(pos.lat, "pos.lat"), lon = degToE5(pos.lon, "pos.lon");
    checkLatLon(lat, lon, "pos");
    init.pos = { lat, lon, acc: "acc" in pos ? enumToWire(pos.acc, C.ACCURACY, "pos.acc") : 0 };
  }
  if ("time" in m) {
    const t = m.time;
    const tm = typeof t === "string" ? TIME_RE.exec(t) : null;
    if (!tm) throw err("invalid_value", 'must be "HH:MM" (UTC)', "time");
    init.time = Number(tm[1]) * 60 + Number(tm[2]);
  }
  init.body = BUILDERS[kind](m[kind]);
  const env = create(EnvelopeSchema, init as never);
  validate(env);
  return env;
}

// ---------------------------------------------------- validation (both ways)

/**
 * Body type name, or "unknown" for a body from a newer spec version. A text packed with a dictionary this
 * library does not know is "unknown" too: it can only be read after an update.
 */
export function bodyType(env: Envelope): string {
  const body = env.body;
  if (body.case === "text" && body.value.deflate.length > 0 && body.value.dict !== 0 && !knownDict(body.value.dict)) {
    return "unknown";
  }
  if (body.case === "text" && wordsUnknown(body.value)) return "unknown";
  return body.case ?? "unknown";
}

/** A compact text whose word dictionary this library does not have (or whose copy fails its SHA-256 check). */
function wordsUnknown(t: TextBody): boolean {
  return t.words.length > 0 && t.wordsDict !== 0 && !t.text && t.deflate.length === 0 && !wordDictAvailable(t.wordsDict);
}

/** Value rules shared by encoder and decoder (spec section 5). */
export function validate(env: Envelope): void {
  const kind = bodyType(env);
  if (env.id > C.MAX_ID) throw err("invalid_value", `id must be 0..${C.MAX_ID}`, "id");
  if (C.ID_REQUIRED.has(kind) && env.id === 0) throw err("missing_field", `${kind} needs an id (1..${C.MAX_ID})`, "id");
  if (env.pos) checkLatLon(env.pos.lat, env.pos.lon, "pos");
  if (env.time !== undefined && env.time > 1439) throw err("invalid_value", "time must be 0..1439 (minute of the UTC day)", "time");
  const body = env.body;
  switch (body.case) {
    case "found": {
      const f = body.value;
      if (f.count > C.MAX_COUNT) throw err("invalid_value", `must be 0..${C.MAX_COUNT}`, "found.count");
      if (f.responders > C.MAX_COUNT) throw err("invalid_value", `must be 0..${C.MAX_COUNT}`, "found.responders");
      break;
    }
    case "track": {
      const t = body.value;
      const n = t.lat.length;
      if (n === 0) throw err("missing_field", "a track needs 1 or more points", "track.points");
      if (n > C.MAX_TRACK_POINTS) throw err("invalid_value", `at most ${C.MAX_TRACK_POINTS} points per packet`, "track.points");
      if (t.lon.length !== n || (t.dt.length !== 0 && t.dt.length !== n)) {
        throw err("invalid_value", "lat, lon and dt must have the same length (dt may be empty)", "track.points");
      }
      if (t.dt.length > 0 && env.time === undefined) throw err("missing_field", "a track with times needs Envelope.time", "time");
      let lat = 0, lon = 0;
      for (let i = 0; i < n; i++) {
        lat += t.lat[i];
        lon += t.lon[i];
        checkLatLon(lat, lon, `track.points[${i}]`);
      }
      break;
    }
    case "sector": {
      const s = body.value;
      if (s.sector === 0 || s.sector > C.MAX_SECTOR) {
        throw err(s.sector === 0 ? "missing_field" : "invalid_value", `sector must be 1..${C.MAX_SECTOR}`, "sector.sector");
      }
      if (s.status === 0) throw err("missing_field", "sector needs a status", "sector.status");
      if (s.coverage > 100) throw err("invalid_value", "coverage must be 0..100", "sector.coverage");
      break;
    }
    case "request": {
      const r = body.value;
      if (r.resource === 0) throw err("missing_field", "request needs a resource", "request.resource");
      if (r.count > C.MAX_COUNT) throw err("invalid_value", `must be 0..${C.MAX_COUNT}`, "request.count");
      break;
    }
    case "hazard": {
      const h = body.value;
      if (h.kind === 0) throw err("missing_field", "hazard needs a kind", "hazard.kind");
      if (h.radius > C.MAX_RADIUS_M) throw err("invalid_value", `radius must be 0..${C.MAX_RADIUS_M}`, "hazard.radius");
      break;
    }
    case "mayday":
      if (body.value.persons > C.MAX_COUNT) throw err("invalid_value", `must be 0..${C.MAX_COUNT}`, "mayday.persons");
      break;
    case "ack": {
      const a = body.value;
      if (a.ref === 0 || a.ref > C.MAX_ID) {
        throw err(a.ref === 0 ? "missing_field" : "invalid_value", `ref must be 1..${C.MAX_ID}`, "ack.ref");
      }
      if (a.node === 0) throw err("missing_field", "ack needs the node of the answered message", "ack.node");
      if (a.status === 0) throw err("missing_field", "ack needs a status", "ack.status");
      if (a.eta > C.MAX_ETA_MIN) throw err("invalid_value", `eta must be 0..${C.MAX_ETA_MIN} minutes`, "ack.eta");
      if (byteLength(a.team) > C.MAX_TEAM_BYTES) throw err("invalid_value", `team label is at most ${C.MAX_TEAM_BYTES} bytes`, "ack.team");
      break;
    }
    case "text": {
      const t = body.value;
      if (kind === "unknown") break; // packed with a newer dictionary
      if (t.words.length > 0) {
        if (t.text || t.deflate.length > 0) throw err("invalid_value", "give one of text, deflate and words", "text.words");
        if (t.dict) throw err("invalid_value", "dict is only allowed with deflate", "text.dict");
        if (t.wordsDict === 0) throw err("missing_field", "a compact text needs its word dictionary number", "text.words_dict");
        if (!unpackedText(t)) throw err("missing_field", "text must not be empty", "text.text");
        break;
      }
      if (t.wordsDict) throw err("invalid_value", "words_dict is only allowed with words", "text.words_dict");
      if (t.deflate.length > 0) {
        if (t.text) throw err("invalid_value", "give text or deflate, not both", "text.deflate");
        if (t.dict === 0) throw err("missing_field", "a packed text needs its dictionary number", "text.dict");
        if (!unpackedText(t)) throw err("missing_field", "text must not be empty", "text.text");
        break;
      }
      if (t.dict) throw err("invalid_value", "dict is only allowed with deflate", "text.dict");
      const size = byteLength(t.text);
      if (size === 0) throw err("missing_field", "text must not be empty", "text.text");
      if (size > C.MAX_TEXT_BYTES) throw err("invalid_value", `text is at most ${C.MAX_TEXT_BYTES} bytes`, "text.text");
      break;
    }
    default:
      break;
  }
}

// ----------------------------------------------------------- Envelope -> JSON

/** Envelope to canonical JSON (no "v" key). Call validate() first. */
export function fromProto(env: Envelope): RescueMessage {
  const out: RescueMessage = {};
  if (env.id) out.id = env.id;
  if (env.priority) out.priority = enumToJson(env.priority, C.PRIORITY);
  if (env.pos) {
    out.pos = { lat: e5ToDeg(env.pos.lat), lon: e5ToDeg(env.pos.lon) };
    if (env.pos.acc) out.pos.acc = enumToJson(env.pos.acc, C.ACCURACY);
  }
  if (env.time !== undefined) {
    out.time = `${String(Math.floor(env.time / 60)).padStart(2, "0")}:${String(env.time % 60).padStart(2, "0")}`;
  }
  const body = env.body;
  switch (body.case) {
    case "found": {
      const f = body.value;
      const b: Obj = {};
      if (f.count) b.count = f.count;
      for (const [name, shift, width, table] of C.STATE_LAYOUT) {
        const value = (f.state >>> shift) & ((1 << width) - 1);
        if (value) b[name] = enumToJson(value, table);
      }
      if (f.injuries) b.injuries = flagsToJson(f.injuries, C.INJURIES);
      if (f.needs) b.needs = flagsToJson(f.needs, C.NEEDS);
      if (f.responders) b.responders = f.responders;
      out.found = b;
      break;
    }
    case "track": {
      const t = body.value;
      const points = [];
      let lat = 0, lon = 0, s = 0;
      for (let i = 0; i < t.lat.length; i++) {
        lat += t.lat[i];
        lon += t.lon[i];
        const p: { lat: number; lon: number; s?: number } = { lat: e5ToDeg(lat), lon: e5ToDeg(lon) };
        if (t.dt.length) {
          s += t.dt[i];
          p.s = s;
        }
        points.push(p);
      }
      out.track = { points };
      break;
    }
    case "sector": {
      const s = body.value;
      out.sector = { sector: s.sector, status: enumToJson(s.status, C.SECTOR_STATUS) };
      if (s.coverage) out.sector.coverage = s.coverage;
      break;
    }
    case "request": {
      const r = body.value;
      out.request = { resource: enumToJson(r.resource, C.RESOURCE) };
      if (r.count) out.request.count = r.count;
      if (r.urgency) out.request.urgency = enumToJson(r.urgency, C.URGENCY);
      break;
    }
    case "hazard": {
      const h = body.value;
      out.hazard = { kind: enumToJson(h.kind, C.HAZARD) };
      if (h.severity) out.hazard.severity = enumToJson(h.severity, C.SEVERITY);
      if (h.radius) out.hazard.radius = h.radius;
      break;
    }
    case "mayday": {
      const m = body.value;
      out.mayday = {};
      if (m.reason) out.mayday.reason = enumToJson(m.reason, C.MAYDAY_REASON);
      if (m.persons) out.mayday.persons = m.persons;
      break;
    }
    case "ack": {
      const a = body.value;
      out.ack = { ref: a.ref, node: nodeToJson(a.node), status: enumToJson(a.status, C.ACK_STATUS) };
      if (a.eta) out.ack.eta = a.eta;
      if (a.team) out.ack.team = a.team;
      break;
    }
    case "text": {
      const t = body.value;
      // Unknown word dictionary: keep its number so the reader is told why, never guess the words.
      if (bodyType(env) === "unknown") out.unknown = wordsUnknown(t) ? { words: t.wordsDict } : {};
      else if (t.words.length > 0) out.text = { text: unpackedText(t), words: t.wordsDict };
      else if (t.deflate.length > 0) out.text = { text: unpackedText(t), dict: t.dict };
      else out.text = { text: t.text };
      break;
    }
    default:
      out.unknown = {};
  }
  return out;
}

// --------------------------------------------------------------- public API

/** Canonical JSON message to v2 wire bytes. Throws CodecError. */
export function encode(msg: RescueMessage): Uint8Array {
  const data = toBinary(EnvelopeSchema, toProto(msg));
  if (data.length > C.MAX_ENCODE_BYTES) throw err("too_large", `${data.length} bytes, the limit is ${C.MAX_ENCODE_BYTES}`);
  return data;
}

/** 1 for a v1 packet (first byte 0x01), 2 for v2, 0 for reserved versions or empty input. */
export function detectVersion(data: Uint8Array): number {
  if (data.length === 0) return 0;
  if (data[0] === 0x01) return 1;
  if (data[0] <= 0x07) return 0;
  return 2;
}

/** Strings must be valid UTF-8. protobuf-es would silently replace bad bytes, so check the raw bytes. */
function checkUtf8(data: Uint8Array): void {
  const strict = new TextDecoder("utf-8", { fatal: true });
  const fieldBytes = (buf: Uint8Array, wanted: number): Uint8Array[] => {
    const r = new BinaryReader(buf);
    const found: Uint8Array[] = [];
    while (r.pos < r.len) {
      const [no, wt] = r.tag();
      if (wt === 2 && no === wanted) found.push(r.bytes());
      else r.skip(wt);
    }
    return found;
  };
  const top = new BinaryReader(data);
  while (top.pos < top.len) {
    const [no, wt] = top.tag();
    if (wt === 2 && (no === 12 || no === 13)) {
      const inner = top.bytes();
      for (const s of fieldBytes(inner, no === 12 ? 5 : 1)) strict.decode(s);
    } else top.skip(wt);
  }
}

/** v2 wire bytes to a validated Envelope. Throws CodecError. */
export function parse(data: Uint8Array): Envelope {
  if (!(data instanceof Uint8Array)) throw err("invalid_value", "payload must be bytes");
  if (data.length === 0) throw err("empty", "empty payload");
  if (data.length > C.MAX_DECODE_BYTES) throw err("too_large", `${data.length} bytes, the limit is ${C.MAX_DECODE_BYTES}`);
  if (detectVersion(data) !== 2) {
    throw err("unsupported_version", `first byte 0x${data[0].toString(16).padStart(2, "0")} is not a v2 packet`);
  }
  let env: Envelope;
  try {
    checkUtf8(data);
    env = fromBinary(EnvelopeSchema, data);
  } catch (e) {
    throw err("malformed", `not a valid protobuf Envelope: ${e instanceof Error ? e.message : String(e)}`);
  }
  if (env.body.case === undefined && !(env.$unknown && env.$unknown.length > 0)) {
    throw err("no_body", "the Envelope has no body");
  }
  validate(env);
  return env;
}

/** v2 wire bytes to canonical JSON. Throws CodecError. */
export function decode(data: Uint8Array): RescueMessage {
  return fromProto(parse(data));
}

export function messageType(msg: RescueMessage): string {
  for (const kind of [...C.BODY_TYPES, "unknown"]) if (kind in msg) return kind;
  throw err("no_body", "the message has no body");
}

/** Priority on the wire, or the default for the message type. */
export function effectivePriority(msg: RescueMessage): string {
  return msg.priority || C.DEFAULT_PRIORITY[messageType(msg)];
}

/** Suggested Meshtastic MeshPacket.priority number for a message. */
export function meshtasticPriority(msg: RescueMessage): number {
  return C.MESHTASTIC_PRIORITY[effectivePriority(msg)] ?? 70;
}

/**
 * Turn a message time "HH:MM" (UTC) into a full UTC Date: the most recent matching
 * minute that is at most 10 minutes after `now` (the receiver's clock).
 */
export function resolveTime(hhmm: string, now: Date = new Date()): Date {
  const m = TIME_RE.exec(hhmm);
  if (!m) throw err("invalid_value", 'must be "HH:MM" (UTC)', "time");
  const c = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate(), Number(m[1]), Number(m[2])));
  if (c.getTime() > now.getTime() + 10 * 60_000) c.setUTCDate(c.getUTCDate() - 1);
  return c;
}

/** Normalise a JSON message (flag order, rounding) by encoding and decoding it. */
export function canonical(msg: RescueMessage): RescueMessage {
  return decode(encode(msg));
}

// ------------------------------------------------------------------ compact frame (spec section 3.7)

/** First byte of a compact frame: 0x02, the word dictionary version, the word stream (3.6). No Envelope, no id. */
export const COMPACT_FRAME = 0x02;

/** True when the payload is a compact frame (first byte 0x02), not an Envelope. */
export function isCompactFrame(data: Uint8Array): boolean {
  return data.length > 0 && data[0] === COMPACT_FRAME;
}

/**
 * Text -> compact frame: 0x02, 1 byte word dictionary version, the word stream. No id, no position, no time.
 * Throws CodecError (missing_field for an empty text, invalid_value for an unknown dictionary, too_large).
 */
export function encodeCompact(text: string, version: number = CURRENT_WORD_DICT): Uint8Array {
  if (typeof text !== "string") throw err("invalid_value", "must be a string", "text.text");
  if (!text) throw err("missing_field", "a compact frame needs a text", "text.text");
  if (!Number.isInteger(version) || version < 1 || version > 255 || !wordDictAvailable(version)) {
    throw err("invalid_value", `unknown word dictionary, known: [${Object.keys(WORD_DICTS).join(", ")}]`, "text.words");
  }
  let stream: Uint8Array;
  try {
    stream = encodeWords(text, version);
  } catch (e) {
    if (e instanceof WordsError) throw err(e.code, e.message, "text.text");
    throw e;
  }
  const data = new Uint8Array(2 + stream.length);
  data[0] = COMPACT_FRAME;
  data[1] = version;
  data.set(stream, 2);
  if (data.length > C.MAX_ENCODE_BYTES) {
    throw err("too_large", `${data.length} bytes, the limit is ${C.MAX_ENCODE_BYTES}`);
  }
  return data;
}

/**
 * Compact frame -> canonical JSON `{text: {text, words: n}}` (no id). A dictionary this library does not have (or
 * whose copy fails the SHA-256 check) gives `{unknown: {words: n}}`. Throws CodecError.
 */
export function decodeCompact(data: Uint8Array): RescueMessage {
  if (data.length === 0 || data[0] !== COMPACT_FRAME) throw err("unsupported_version", "not a compact frame");
  if (data.length > C.MAX_DECODE_BYTES) {
    throw err("too_large", `${data.length} bytes, the limit is ${C.MAX_DECODE_BYTES}`);
  }
  if (data.length < 2) throw err("malformed", "the compact frame is cut off (no dictionary version)", "text.words_dict");
  const version = data[1];
  if (version === 0) throw err("malformed", "word dictionary 0 does not exist", "text.words_dict");
  if (!wordDictAvailable(version)) return { unknown: { words: version } };
  try {
    return { text: { text: decodeWords(data.subarray(2), version), words: version } };
  } catch (e) {
    if (e instanceof WordsError) throw err(e.code, e.message, "text.words");
    throw err("malformed", e instanceof Error ? e.message : String(e), "text.words");
  }
}
