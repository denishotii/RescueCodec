// Code tables: JSON names <-> wire numbers. Mirrors spec/rescuecodec/v2/rescuecodec.proto.
// test/codes.test.ts checks that these agree with the generated protobuf code.

export const SPEC_VERSION = "2.3.0";
/** Meshtastic port number used for RescueCodec packets (PRIVATE_APP). */
export const PORTNUM = 256;

export const MAX_ENCODE_BYTES = 200;
export const MAX_DECODE_BYTES = 233;
export const MAX_ID = 16383;
export const MAX_TRACK_POINTS = 16;
export const MAX_TEXT_BYTES = 180;
/** Typed text a chat that packs (DEFLATE or word tokens) accepts; the packet stays <= 200 bytes. */
export const MAX_PACKED_TEXT_BYTES = 400;
export const MAX_TEAM_BYTES = 12;
export const MAX_COUNT = 255;
export const MAX_RADIUS_M = 100_000;
export const MAX_ETA_MIN = 1440;
export const MAX_SECTOR = 65535;

export const BODY_TYPES = ["found", "track", "sector", "request", "hazard", "mayday", "ack", "text"] as const;
export type BodyType = (typeof BODY_TYPES)[number];
export const ID_REQUIRED: ReadonlySet<string> = new Set(["found", "request", "hazard", "mayday"]);

export type Table = Readonly<Record<string, number>>;

export const PRIORITY: Table = { routine: 1, normal: 2, urgent: 3, critical: 4 };
export const DEFAULT_PRIORITY: Readonly<Record<string, string>> = {
  mayday: "critical", found: "urgent", hazard: "urgent", ack: "urgent",
  request: "normal", text: "normal", sector: "routine", track: "routine", unknown: "normal",
};
/** Suggested Meshtastic MeshPacket.priority per RescueCodec priority. */
export const MESHTASTIC_PRIORITY: Readonly<Record<string, number>> = { critical: 110, urgent: 100, normal: 70, routine: 10 };

export const ACCURACY: Table = { gps: 0, gps_poor: 1, estimated: 2, area: 3 };

export const CONSCIOUSNESS: Table = { conscious: 1, confused: 2, unconscious: 3 };
export const BREATHING: Table = { normal: 1, difficult: 2, none: 3 };
export const MOBILITY: Table = { walks: 1, cannot_walk: 2, trapped: 3 };
export const AGE: Table = { child: 1, teen: 2, adult: 3, senior: 4 };
/** Found.state packing: [json name, shift, bit width, table]. */
export const STATE_LAYOUT: ReadonlyArray<readonly [string, number, number, Table]> = [
  ["consciousness", 0, 2, CONSCIOUSNESS],
  ["breathing", 2, 2, BREATHING],
  ["mobility", 4, 2, MOBILITY],
  ["age", 6, 3, AGE],
];

/** Bit flags: name -> bit number. */
export const INJURIES: Table = {
  hypothermia: 0, bleeding: 1, fracture: 2, head: 3, spine: 4, exhaustion: 5,
  other: 6, cardiac: 7, heat: 8, burn: 9, allergy: 10,
};
export const NEEDS: Table = {
  stretcher: 0, medic: 1, helicopter: 2, warmth: 3, water: 4, vehicle: 5, other: 6,
  aed: 7, oxygen: 8, first_aid: 9, rope: 10, light: 11, food: 12, radio: 13,
};

export const SECTOR_STATUS: Table = { cleared: 1, partial: 2, blocked: 3 };
export const RESOURCE: Table = {
  stretcher: 1, medic: 2, helicopter: 3, vehicle: 4, searchers: 5, dog_team: 6, drone: 7, light: 8,
  water: 9, food: 10, warmth: 11, batteries: 12, rope_team: 13, ambulance: 14, fire_brigade: 15, other: 16,
};
export const URGENCY: Table = { now: 1, soon: 2, later: 3 };
export const HAZARD: Table = {
  blocked_path: 1, rockfall: 2, water: 3, fire: 4, cliff: 5, ice: 6, animal: 7,
  power_line: 8, hunting: 9, weather: 10, other: 11,
};
export const SEVERITY: Table = { low: 1, medium: 2, high: 3 };
export const MAYDAY_REASON: Table = {
  injured: 1, lost: 2, missing_member: 3, medical: 4, trapped: 5, threat: 6, other: 7,
};
export const ACK_STATUS: Table = { accepted: 1, declined: 2, done: 3 };
