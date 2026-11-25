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
export interface Text { text: string }

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
  /** A body type from a newer spec version. */
  unknown?: Record<string, never>;
}
