// Read v1 packets (first byte 0x01) as v2 canonical JSON, so old senders still show up.
// v1 is only decoded, never produced. The result carries "v": 1 and may hold values a v2
// encoder refuses (for example a 32-bit id), so do not re-encode it.
import * as C from "./codes.js";
import { COMPACT_FRAME, DOMAIN_FRAME, decode, decodeCompact, decodeDomain, detectVersion } from "./codec.js";
import { CodecError } from "./errors.js";
import type { Found, RescueMessage } from "./types.js";

const V1_MAX = 200;
const HEADER = 3;
const BODY = 8; // id(4) states(1) equipment mask(1) request(1) flags(1)

const bad = (msg: string) => new CodecError("malformed", `bad v1 packet: ${msg}`);

const PRIORITY: Record<number, string> = { 1: "routine", 2: "urgent", 3: "critical" };
const EQUIPMENT = ["oxygen", "first_aid", "aed", "stretcher", "oxygen", "water", "radio", "other"];
const ASSISTANCE: Record<number, string> = { 2: "medic", 3: "other", 4: "rope", 5: "other", 6: "other" };
const ACCURACY: Record<number, string> = { 3: "gps", 2: "estimated", 1: "area", 0: "estimated" };

export function decodeV1(data: Uint8Array): RescueMessage {
  if (data.length < 3 || data[0] !== 0x01) throw new CodecError("malformed", "not a v1 packet");
  const dv = new DataView(data.buffer, data.byteOffset, data.byteLength);
  const codec = data[1];
  const schema = data[2];
  if (codec === 0) {
    if (data.length < HEADER + 5 || data.length > V1_MAX || schema !== 0) throw bad("invalid text packet");
    const id = dv.getUint32(HEADER);
    let text: string;
    try {
      text = new TextDecoder("utf-8", { fatal: true }).decode(data.subarray(HEADER + 4));
    } catch {
      throw bad("invalid UTF-8 text");
    }
    const out: RescueMessage = { v: 1 };
    if (id) out.id = id;
    out.text = { text };
    return out;
  }
  if (codec === 7) {
    if (data.length !== 8 || schema !== 0) throw bad("invalid confirmation");
    const status = data[7];
    if (status !== 1 && status !== 2) throw bad("invalid confirmation status");
    return { v: 1, ack: { ref: dv.getUint32(HEADER), status: status === 1 ? "accepted" : "done" } } as RescueMessage;
  }
  // person-found report
  if (data.length < HEADER + BODY || data.length > V1_MAX) throw bad("truncated or oversized packet");
  if (codec !== 1 || schema !== 6) throw bad(`unsupported codec/schema: ${codec}/${schema}`);
  const id = dv.getUint32(HEADER);
  const states = data[7], mask = data[8], request = data[9], flags = data[10];
  let offset = HEADER + BODY;
  if (flags & ~0x0f) throw bad("reserved flag bits must be zero");
  const expected = offset + (flags & 1 ? 8 : 0) + (flags & 2 ? 4 : 0) + (flags & 4 ? 1 : 0) + (flags & 8 ? 1 : 0);
  if (data.length !== expected) throw bad("payload length does not match option flags");
  if (request > 6) throw bad("invalid categorical value");
  const consciousness = states >> 6, breathing = (states >> 4) & 3, urgency = (states >> 2) & 3, quality = states & 3;

  const out: RescueMessage = { v: 1, id };
  if (urgency in PRIORITY) out.priority = PRIORITY[urgency];
  if (flags & 1) {
    const lat = dv.getInt32(offset), lon = dv.getInt32(offset + 4);
    offset += 8;
    if (Math.abs(lat) > 9_000_000 || Math.abs(lon) > 18_000_000) throw bad("position out of range");
    out.pos = { lat: lat / 100000, lon: lon / 100000 };
    const acc = ACCURACY[quality];
    if (acc !== "gps") out.pos.acc = acc;
  }
  if (flags & 2) {
    const ts = dv.getUint32(offset);
    offset += 4;
    const d = new Date(ts * 1000);
    out.time = `${String(d.getUTCHours()).padStart(2, "0")}:${String(d.getUTCMinutes()).padStart(2, "0")}`;
    out.v1_timestamp = ts;
  }
  let count = 0, responders = 0;
  if (flags & 4) {
    count = data[offset++];
    if (count < 1 || count > 15) throw bad("person count must be 1..15");
  }
  if (flags & 8) {
    responders = data[offset++];
    if (responders < 1 || responders > 15) throw bad("minimum responders needed must be 1..15");
  }
  const found: Found = {};
  if (count) found.count = count;
  if (consciousness === 1) found.consciousness = "conscious";
  else if (consciousness === 2) found.consciousness = "unconscious";
  if (breathing === 1) found.breathing = "normal";
  else if (breathing === 2) found.breathing = "none";
  const needs = new Set<string>();
  for (let bit = 0; bit < 8; bit++) if (mask & (1 << bit)) needs.add(EQUIPMENT[bit]);
  if (request in ASSISTANCE) needs.add(ASSISTANCE[request]);
  if (needs.size) found.needs = [...needs].sort((a, b) => C.NEEDS[a] - C.NEEDS[b]);
  if (responders) found.responders = responders;
  out.found = found;
  return out;
}

/**
 * Decode a RescueCodec payload of any version. First byte 0x01 is v1 (result has "v": 1). 0x02 is a compact frame
 * (spec section 3.7, "v": 2, a text without id). 0x03 is a domain frame (spec section 3.8, "v": 2, a text without id).
 * Bytes 0x00 and 0x04..0x07 are reserved and rejected with "unsupported_version". Anything else is a v2 Envelope
 * (result has "v": 2).
 */
export function decodeAny(data: Uint8Array): RescueMessage {
  if (data.length === 0) throw new CodecError("empty", "empty payload");
  if (data[0] === COMPACT_FRAME) return { v: 2, ...decodeCompact(data) }; // a text without id (spec section 3.7)
  if (data[0] === DOMAIN_FRAME) return { v: 2, ...decodeDomain(data) }; // a text without id (spec section 3.8)
  if (detectVersion(data) === 1) return decodeV1(data);
  return { v: 2, ...decode(data) };
}
