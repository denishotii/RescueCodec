import { describe, expect, it } from "vitest";
import * as C from "../src/codes.js";
import * as pb from "../src/gen/rescuecodec/v2/rescuecodec_pb.js";

// The code tables must agree with the generated protobuf enums.
const enums: Array<[string, C.Table, string]> = [
  ["Accuracy", C.ACCURACY, "ACCURACY_"], ["Priority", C.PRIORITY, "PRIORITY_"],
  ["Consciousness", C.CONSCIOUSNESS, "CONSCIOUSNESS_"], ["Breathing", C.BREATHING, "BREATHING_"],
  ["Mobility", C.MOBILITY, "MOBILITY_"], ["AgeGroup", C.AGE, "AGE_"],
  ["SectorStatus", C.SECTOR_STATUS, "SECTOR_"], ["Resource", C.RESOURCE, "RESOURCE_"],
  ["Urgency", C.URGENCY, "URGENCY_"], ["HazardType", C.HAZARD, "HAZARD_"],
  ["Severity", C.SEVERITY, "SEVERITY_"], ["MaydayReason", C.MAYDAY_REASON, "MAYDAY_"],
  ["AckStatus", C.ACK_STATUS, "ACK_"],
];

// protobuf-es strips an enum prefix only when it matches the enum name, so try both spellings.
const pick = (e: Record<string, number | string>, prefix: string, json: string) =>
  e[prefix + json.toUpperCase()] ?? e[json.toUpperCase()];

describe("code tables match the proto", () => {
  it.each(enums)("%s", (name, table, prefix) => {
    const e = (pb as any)[name] as Record<string, number | string>;
    for (const [json, n] of Object.entries(table)) expect(pick(e, prefix, json)).toBe(n);
    const count = Object.keys(e).filter((k) => typeof e[k] === "number" && e[k] !== 0).length;
    expect(count).toBe(Object.values(table).filter((n) => n !== 0).length);
  });

  it.each([["Injury", C.INJURIES, "INJURY_"], ["Need", C.NEEDS, "NEED_"]] as const)("%s flags", (name, table, prefix) => {
    const e = (pb as any)[name] as Record<string, number | string>;
    for (const [json, bit] of Object.entries(table)) expect(pick(e, prefix, json)).toBe(2 ** bit);
  });
});
