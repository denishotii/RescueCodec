// Types written from spec/api/openapi.yaml (version 2.0.0). Keep them in sync with that file.

export type Priority = "routine" | "normal" | "urgent" | "critical";
export type MessageType = "found" | "track" | "sector" | "request" | "hazard" | "mayday" | "ack" | "text" | "unknown";
export type TaskType = "found" | "request" | "hazard" | "mayday";
export type TaskState = "open" | "accepted" | "declined" | "done";
export type AckStatus = "accepted" | "declined" | "done";
export type Metric = { value: number | null; unit: string; source: "measured" | "estimated"; note?: string };

export interface Position { lat: number; lon: number; acc?: "gps" | "gps_poor" | "estimated" | "area" }
export interface Found {
  count?: number; age?: string; consciousness?: string; breathing?: string; mobility?: string;
  injuries?: string[]; needs?: string[]; responders?: number;
}
export interface TrackPoint { lat: number; lon: number; s?: number }
export interface Message {
  v?: 1 | 2; id?: number; priority?: Priority; pos?: Position; time?: string; v1_timestamp?: number;
  found?: Found;
  track?: { points: TrackPoint[] };
  sector?: { sector: number; status: "cleared" | "partial" | "blocked"; coverage?: number };
  request?: { resource: string; count?: number; urgency?: "now" | "soon" | "later" };
  hazard?: { kind: string; severity?: "low" | "medium" | "high"; radius?: number };
  mayday?: { reason?: string; persons?: number };
  ack?: { ref: number; node?: string; status: AckStatus; eta?: number; team?: string };
  /** `dict`: sent packed (DEFLATE); `words`: sent as compact word tokens (spec 3.6). */
  text?: { text: string; dict?: number; words?: number };
  /** `words`: a compact text with a word dictionary the gateway does not have. */
  unknown?: { words?: number };
}
export interface Text2 { en: string; de: string }
/** A Meshtastic channel, known by its name. public = no key or the default key (anyone can read it). */
export interface Channel { index: number; name: string; public: boolean }
/** How a text goes out on a channel now: "compressed" = the chat packs (DEFLATE or compact words), up to 400 B typed. */
export type SendAs = "compressed" | "normal";
/** A channel in GET /v2/health with its text mode. */
export interface RadioChannel extends Channel { mode?: "auto" | "compressed" | "normal"; send_as?: SendAs }
export interface RadioFacts {
  packet_id: number; rssi: number | null; snr: number | null; hops: number | null; channel: number;
  via: "serial" | "tcp" | "ble" | "sim";
}
export interface MessageRecord {
  seq: number; key: string | null; direction: "in" | "out"; from: string; type: MessageType; priority: Priority;
  status: "received" | "queued" | "sent" | "delivered" | "failed" | "dry_run"; error: string | null;
  time: string; event_time: string | null;
  /** version 0: a normal Meshtastic text (port 1), not a RescueCodec packet; hex is the UTF-8 text (SPEC 2.1). */
  codec: { version: 0 | 1 | 2; size: number; hex: string };
  radio: RadioFacts | null;
  /** The channel it came in or went out on. */
  channel: Channel;
  /** Direct messages only: the peer we sent to (out) or our own node (in). Null for a channel broadcast. */
  to: string | null;
  message: Message; text: Text2; task_key: string | null;
}
export interface TaskAck { by: string; status: AckStatus; eta?: number | null; team?: string | null; time: string; seq: number }
export interface Task {
  key: string; from: string; id: number; type: TaskType; priority: Priority; state: TaskState;
  created: string; updated: string; pos: Position | null; text: Text2; message_seq: number;
  /** Channel of the report. Answers go out on it. */
  channel: Channel; acks: TaskAck[];
}
export interface Stats {
  since: string;
  messages_in: Metric; messages_out: Metric; bytes_in: Metric; bytes_out: Metric;
  text_equivalent_bytes: Metric; size_ratio: Metric; airtime_out_s: Metric;
  airtime_last_hour_pct: Metric; airtime_limit_pct: Metric; last_rssi: Metric; last_snr: Metric;
  by_type: Record<string, { count: number; bytes: number }>;
}
export interface MeshNode {
  node: string; num: number; short_name?: string | null; long_name?: string | null; hw_model?: string | null;
  role?: string | null; last_heard: string | null; rssi?: number | null; snr?: number | null;
  hops_away?: number | null; battery_pct?: number | null; pos?: Position | null; is_gateway: boolean;
}
export interface Health {
  status: "ok" | "degraded"; version: string; spec_version: string; uptime_s: number;
  radio: {
    connected: boolean; kind: "serial" | "tcp" | "ble" | "sim" | "none"; node: string | null; port: number;
    channel?: string | null; channel_index?: number | null; channels?: RadioChannel[]; channel_error?: string | null; other_channel?: number; region?: string | null; preset?: string | null; firmware?: string | null;
  };
  mqtt: { connected: boolean };
  /** Compact word texts (spec 3.6) are on: a packing chat may send a text as word tokens when that is smaller. */
  compact_text?: boolean;
  /** Most bytes of typed text: `text` for a normal chat, `packed` for a chat that packs. */
  text_limits?: { text: number; packed: number };
}
export interface MessageStatusEvent { seq: number; status: "queued" | "sent" | "delivered" | "failed"; error?: string | null; updated: string }
export type StreamEvent =
  | { event: "hello"; data: { last_seq: number; health: Health } }
  | { event: "message"; data: MessageRecord }
  | { event: "message_status"; data: MessageStatusEvent }
  | { event: "task"; data: Task }
  | { event: "node"; data: MeshNode }
  | { event: "stats"; data: Stats }
  | { event: "health"; data: Health }
  | { event: "pong"; data: Record<string, never> };

/** POST /v2/extract: what a typed sentence would send. Nothing is sent. */
export interface ExtractField { field: string; code: string | number; text: Text2 }
export interface ExtractResult {
  type: "found" | "sector" | "request" | "hazard" | "mayday" | "text";
  /** Canonical message without an id; post it to /v2/messages. A text when no report fits. */
  message: Message;
  unsupported: boolean; reason: string | null; lang: "en" | "de" | "unknown";
  fields: ExtractField[]; text: Text2;
  /** Bytes on air for `message` (id counted). */
  coded_bytes: number;
  /** Bytes on air for the same words as a plain text. */
  text_bytes: number;
  /** How a text goes out to the asked channel or person. */
  send_as?: SendAs;
  /** Most bytes of typed text there: 400 on a chat that packs, else 180. */
  max_text_bytes?: number;
  /** Form of a text: plain, packed (deflate), compact word tokens in the Envelope (words) or a compact frame (lean). */
  form?: "plain" | "deflate" | "words" | "lean";
  /** True when the text will be shortened to fit one radio message. */
  cut?: boolean;
}
/** Body of POST /v2/extract: the sentence, and where it would go (a channel name or a node). */
export interface ExtractBody { text: string; channel?: string; to?: string }
/** Body of POST /v2/messages and POST /v2/text: `channel` (a name) or `to` (a node, direct message). */
export interface SendMessageBody { message: Message; channel?: string; to?: string }
export interface SendTextBody { text: string; channel?: string; to?: string }

export interface ApiErrorBody { error: { code: string; message: string; field: string | null; retry_after_s?: number } }
