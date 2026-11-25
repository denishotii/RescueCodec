import { describe, expect, it } from "vitest";
import { toUtm, utmLabel, circlePolygon } from "../src/lib/geo";
import { airtimeSaving, loraAirtimeSeconds, packetAirtime } from "../src/lib/airtime";
import { applyStatus, lastSeq, mergeMessages, upsertMessage } from "../src/state/store";
import type { MessageRecord } from "../src/api/types";
import { isNoRelay } from "../src/lib/format";
import { t } from "../src/lib/i18n";
import { buildMapModel } from "../src/map/data";

const rec = (seq: number, over: Partial<MessageRecord> = {}): MessageRecord => ({
  seq, key: null, direction: "in", from: "!a1b2c3d4", type: "text", priority: "normal", status: "received", error: null,
  time: "2026-11-25T14:32:05Z", event_time: null, codec: { version: 2, size: 10, hex: "" }, radio: null,
  channel: { index: 1, name: "RescueCodec", public: false }, to: null,
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

describe("outgoing status wording", () => {
  it("calls a broadcast that no relay repeated 'no relay confirmed', not failed", () => {
    expect(isNoRelay(rec(1, { direction: "out", status: "failed", error: "max_retransmit" }))).toBe(true);
    expect(isNoRelay(rec(2, { direction: "out", status: "failed", error: "radio_error" }))).toBe(false);
    expect(isNoRelay(rec(3, { direction: "out", status: "delivered" }))).toBe(false);
    expect(isNoRelay(rec(4, { direction: "in", status: "received" }))).toBe(false);
    expect(t("en", "statusNoRelay")).toBe("No relay confirmed");
    expect(t("de", "statusNoRelay")).toBe("Kein Relais bestätigt");
  });
});

describe("map model", () => {
  const colors = ["#111", "#222", "#333"];
  const name = (id: string) => id.slice(-4);
  const pos = (lat: number, lon: number) => ({ lat, lon, acc: "estimated" as const });

  it("pins a text message that has a position", () => {
    const m = buildMapModel([rec(1, { message: { id: 1, pos: pos(50.948, 6.981), text: { text: "test" } } })], {}, colors, name);
    expect(m.markers).toHaveLength(1);
    expect(m.markers[0]).toMatchObject({ kind: "text", taskKey: null, lat: 50.948, lon: 6.981 });
    expect(m.bounds).toEqual([[6.981, 50.948]]);
  });

  it("leaves a text message without a position off the map", () => {
    const m = buildMapModel([rec(1)], {}, colors, name);
    expect(m.markers).toHaveLength(0);
    expect(m.trackEnds).toHaveLength(0);
  });

  it("shows every team member once, at their newest position", () => {
    const m = buildMapModel([
      rec(1, { from: "!aaaa0001", message: { id: 1, pos: pos(50.1, 7.1), text: { text: "a" } } }),
      rec(2, { from: "!bbbb0002", type: "found", message: { id: 2, pos: pos(50.2, 7.2), found: {} } }),
      rec(3, { from: "!aaaa0001", message: { id: 3, pos: pos(50.3, 7.3), text: { text: "b" } } }),
      rec(4, { from: "!cccc0003", direction: "out", message: { id: 4, pos: pos(50.4, 7.4), text: { text: "ours" } } }),
    ], {}, colors, name);
    const ends = Object.fromEntries(m.trackEnds.map((e) => [e.from, e]));
    expect(Object.keys(ends).sort()).toEqual(["!aaaa0001", "!bbbb0002"]);
    expect(ends["!aaaa0001"]).toMatchObject({ lat: 50.3, lon: 7.3, label: "0001" });
    expect(ends["!bbbb0002"]).toMatchObject({ lat: 50.2, lon: 7.2 });
    expect(ends["!aaaa0001"].color).not.toBe(ends["!bbbb0002"].color);
  });
});
