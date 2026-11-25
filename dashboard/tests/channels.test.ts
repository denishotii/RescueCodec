import { describe, expect, it } from "vitest";
import { channelOptions, channelsInUse, chipFor, matchesChannel, showChannels } from "../src/lib/channels";
import type { Channel, Health } from "../src/api/types";
import { t } from "../src/lib/i18n";

const RC: Channel = { index: 1, name: "RescueCodec", public: false };
const DR: Channel = { index: 2, name: "draeger-srs", public: false };
const MM: Channel = { index: 3, name: "MeshMeister", public: false };
const on = (channel: Channel) => ({ channel });
const health = (channels: Channel[]): Health => ({
  status: "ok", version: "x", spec_version: "2.0.0", uptime_s: 1, mqtt: { connected: false },
  radio: { connected: true, kind: "sim", node: "!0badc0de", port: 256, channel: "RescueCodec", channels },
});

describe("channel filter", () => {
  it("All matches every channel", () => {
    expect(matchesChannel("all", "RescueCodec")).toBe(true);
    expect(matchesChannel("all", "draeger-srs")).toBe(true);
    expect(matchesChannel("all", undefined)).toBe(true);
  });
  it("a channel matches only itself, without case", () => {
    expect(matchesChannel("draeger-srs", "draeger-srs")).toBe(true);
    expect(matchesChannel("Draeger-SRS", "draeger-srs")).toBe(true);
    expect(matchesChannel("draeger-srs", "RescueCodec")).toBe(false);
    expect(matchesChannel("draeger-srs", null)).toBe(false);
  });
  it("filters a mixed feed", () => {
    const feed = [on(RC), on(DR), on(RC), on(MM)];
    expect(feed.filter((r) => matchesChannel("RescueCodec", r.channel.name))).toHaveLength(2);
    expect(feed.filter((r) => matchesChannel("draeger-srs", r.channel.name))).toHaveLength(1);
    expect(feed.filter((r) => matchesChannel("all", r.channel.name))).toHaveLength(4);
  });
});

describe("channels in use", () => {
  it("lists each channel once, in order of first use, from records and tasks", () => {
    expect(channelsInUse([on(RC), on(DR), on(RC)], [on(MM), on(DR)])).toEqual(["RescueCodec", "draeger-srs", "MeshMeister"]);
    expect(channelsInUse([], [])).toEqual([]);
  });
  it("shows chips and the filter only with more than one channel", () => {
    expect(showChannels(channelsInUse([on(RC), on(RC)], [on(RC)]))).toBe(false);
    expect(showChannels(channelsInUse([on(RC), on(DR)], []))).toBe(true);
    expect(showChannels([])).toBe(false);
  });
  it("offers the gateway's channels first, then others seen in the data", () => {
    expect(channelOptions(health([RC, DR]), [on(RC)], [])).toEqual(["RescueCodec", "draeger-srs"]);
    expect(channelOptions(health([RC]), [on(MM)], [on(RC)])).toEqual(["RescueCodec", "MeshMeister"]);
    expect(channelOptions(null, [on(DR)], [])).toEqual(["draeger-srs"]);
  });
  it("has words for the chip in both languages", () => {
    expect(t("en", "direct")).toBe("Direct");
    expect(t("de", "direct")).toBe("Direkt");
    expect(t("de", "allChannels")).toBe("Alle Kanäle");
  });
});

describe("channel chip on a card", () => {
  it("only with more than one channel, and not for the gateway's own channel", () => {
    expect(chipFor(false, "draeger-srs", "RescueCodec")).toBe(false);
    expect(chipFor(true, "draeger-srs", "RescueCodec")).toBe(true);
    expect(chipFor(true, "rescuecodec", "RescueCodec")).toBe(false);
    expect(chipFor(true, "RescueCodec", null)).toBe(true);
    expect(chipFor(true, undefined, "RescueCodec")).toBe(false);
  });
});
