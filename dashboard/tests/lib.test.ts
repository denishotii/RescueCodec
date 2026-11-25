import { describe, expect, it } from "vitest";
import { toUtm, utmLabel, circlePolygon } from "../src/lib/geo";
import { airtimeSaving, loraAirtimeSeconds, packetAirtime } from "../src/lib/airtime";
import { applyStatus, lastSeq, mergeMessages, upsertMessage } from "../src/state/store";
import type { MessageRecord } from "../src/api/types";

const rec = (seq: number, over: Partial<MessageRecord> = {}): MessageRecord => ({
  seq, key: null, direction: "in", from: "!a1b2c3d4", type: "text", priority: "normal", status: "received", error: null,
  time: "2026-11-25T14:32:05Z", event_time: null, codec: { version: 2, size: 10, hex: "" }, radio: null,
  message: { text: { text: "x" } }, text: { en: "x", de: "x" }, task_key: null, ...over,
});

describe("UTM", () => {
  it("matches the known value on the central meridian at 50 N", () => {
    const u = toUtm(50, 9);
    expect(u.zone).toBe(32);
    expect(u.band).toBe("U");
    expect(u.easting).toBeCloseTo(500000, 0);
    expect(Math.abs(u.northing - 5538629.8)).toBeLessThan(2); // series accuracy is better than 2 m, plenty for a grid reference
  });
  it("puts Siebengebirge in zone 32U", () => {
    expect(utmLabel(50.69212, 7.21345)).toMatch(/^32U 3\d{5} 56\d{5}$/);
  });
  it("draws a closed circle", () => {
    const ring = circlePolygon(50.69, 7.21, 100, 12);
    expect(ring).toHaveLength(13);
    expect(ring[0]).toEqual(ring[12]);
  });
});

describe("airtime estimate", () => {
  it("matches the examples in the API spec", () => {
    expect(packetAirtime(23)).toBeCloseTo(0.6, 1);
    expect(packetAirtime(122)).toBeCloseTo(1.34, 2);
  });
  it("grows with size", () => {
    expect(loraAirtimeSeconds(100)).toBeGreaterThan(loraAirtimeSeconds(50));
  });
  it("saves air time when the codec is smaller than text", () => {
    const s = airtimeSaving([{ size: 23, textBytes: 122 }]);
    expect(s.savedS).toBeCloseTo(1.34 - 0.6, 1);
    expect(airtimeSaving([]).savedS).toBe(0);
  });
});

describe("message list reducers", () => {
  it("appends, replaces and sorts", () => {
    let l = upsertMessage([], rec(1));
    l = upsertMessage(l, rec(3));
    l = upsertMessage(l, rec(2));
    expect(l.map((m) => m.seq)).toEqual([1, 2, 3]);
    l = upsertMessage(l, rec(2, { status: "sent" }));
    expect(l).toHaveLength(3);
    expect(l[1].status).toBe("sent");
    expect(lastSeq(l)).toBe(3);
  });
  it("is idempotent when the WebSocket repeats a record", () => {
    const once = mergeMessages([], [rec(1), rec(2)]);
    const twice = mergeMessages(once, [rec(1), rec(2)]);
    expect(twice).toHaveLength(2);
  });
  it("applies status events", () => {
    const l = applyStatus([rec(5, { direction: "out", status: "queued" })], { seq: 5, status: "failed", error: "no_ack", updated: "x" });
    expect(l[0].status).toBe("failed");
    expect(l[0].error).toBe("no_ack");
    expect(applyStatus(l, { seq: 99, status: "sent", updated: "x" })).toBe(l);
  });
});

import { clampBytes } from "../src/components/AckControl";
describe("team label", () => {
  it("never exceeds 12 bytes of UTF-8 and never splits a character", () => {
    expect(clampBytes("Trupp 3", 12)).toBe("Trupp 3");
    expect(clampBytes("Einsatzleitung", 12)).toBe("Einsatzleitu");
    expect(new TextEncoder().encode(clampBytes("Bergwacht Köln Süd", 12)).length).toBeLessThanOrEqual(12);
    expect(clampBytes("ääääääääää", 12)).toBe("ääääää");
  });
});
