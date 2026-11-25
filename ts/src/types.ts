/** Canonical JSON message (spec section 4). Same shape as the REST API, MQTT and test vectors. */
export interface Pos { lat: number; lon: number; acc?: string }
export interface Found {
  count?: number; age?: string; consciousness?: string; breathing?: string; mobility?: string;
  injuries?: string[]; needs?: string[]; responders?: number;
}
export interface TrackPoint { lat: number; lon: number; s?: number }
export interface Track { points: TrackPoint[] }
export interface Sector { sector: number; status: string; coverage?: number }
export interface Request { resource: string; count?: number; urgency?: string }
export interface Hazard { kind: string; severity?: string; radius?: number }
export interface Mayday { reason?: string; persons?: number }
export interface Ack { ref: number; node: string; status: string; eta?: number; team?: string }
/**
 * `dict` set: the text travels packed with that dictionary (spec section 3.5).
 * `words` set: the text travels as compact word tokens with that word dictionary (spec section 3.6).
 * `vocab` set: the text came as a domain frame with that team vocabulary id (spec section 3.8; decode only, a domain
 * frame is built with encodeDomain).
 */
export interface Text { text: string; dict?: number; words?: number; vocab?: number }

export interface RescueMessage {
  /** Set by decodeAny: 1 for old v1 packets, 2 for v2. Encode accepts only 2. */
  v?: number;
  id?: number;
  priority?: string;
  pos?: Pos;
  /** "HH:MM", UTC. */
  time?: string;
  /** Only set on decoded v1 packets. */
  v1_timestamp?: number;
  found?: Found;
  track?: Track;
  sector?: Sector;
  request?: Request;
  hazard?: Hazard;
  mayday?: Mayday;
  ack?: Ack;
  text?: Text;
  /**
   * A body type from a newer spec version: `{}`. A compact text with a word dictionary this library does not have
   * (or whose bundled copy fails its SHA-256 check): `{ words: n }`, shown as "dictionary this app does not have".
   * A domain frame with a team vocabulary this library does not have (or whose copy fails its SHA-256 check):
   * `{ vocab: n }`, shown as "unsupported vocabulary".
   */
  unknown?: { words?: number; vocab?: number };
}
