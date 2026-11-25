import { describe, expect, it } from "vitest";
import {
  MAX_TEXT_BYTES, QUICK, buildSend, byteLength, defaultTarget, directOptions, headOf, parseTargetKey, plainOnlyReason,
  previewView, sendChannels, sendErrorText, targetKey, type Target,
} from "../src/lib/compose";
import type { Channel, ExtractResult, Health, MeshNode } from "../src/api/types";

const RC: Channel = { index: 1, name: "RescueCodec", public: false };
const DR: Channel = { index: 2, name: "draeger-srs", public: false };
const LF: Channel = { index: 0, name: "LongFast", public: true };
const health = (channels: Channel[], channel: string | null = "RescueCodec"): Health => ({
  status: "ok", version: "x", spec_version: "2.0.0", uptime_s: 1, mqtt: { connected: false },
  radio: { connected: true, kind: "sim", node: "!0badc0de", port: 256, channel, channels },
});
const node = (id: string, long: string | null, extra: Partial<MeshNode> = {}): MeshNode => ({
  node: id, num: 1, long_name: long, short_name: long ? long.slice(0, 4) : null, last_heard: null, is_gateway: false, ...extra,
});

const FOUND: ExtractResult = {
  type: "found", message: { found: { age: "child", consciousness: "conscious", needs: ["stretcher"] } },
  unsupported: false, reason: null, lang: "de",
  fields: [
    { field: "found.age", code: "child", text: { en: "child", de: "Kind" } },
    { field: "found.consciousness", code: "conscious", text: { en: "conscious", de: "ansprechbar" } },
    { field: "found.needs", code: "stretcher", text: { en: "needs stretcher", de: "braucht Trage" } },
  ],
  text: { en: "Person found: child, conscious, needs stretcher.", de: "Person gefunden: Kind, ansprechbar, braucht Trage." },
  coded_bytes: 10, text_bytes: 65,
};
const TEXT: ExtractResult = {
  type: "text", message: { text: { text: "Recall all" } }, unsupported: true, reason: "no_supported_type", lang: "en",
  fields: [], text: { en: "Message: Recall all.", de: "Nachricht: Recall all." }, coded_bytes: 10, text_bytes: 10,
};

describe("who to send to", () => {
  it("lists the default channel first, private before public", () => {
    expect(sendChannels(health([LF, DR, RC])).map((c) => c.name)).toEqual(["RescueCodec", "draeger-srs", "LongFast"]);
    expect(sendChannels(null)).toEqual([]);
  });
  it("defaults to the team channel", () => {
    expect(defaultTarget(health([LF, DR, RC]))).toEqual({ kind: "channel", name: "RescueCodec" });
    expect(defaultTarget(health([LF, DR], "Gone"))).toEqual({ kind: "channel", name: "draeger-srs" });
    expect(defaultTarget(health([], "RescueCodec"))).toEqual({ kind: "channel", name: "RescueCodec" });
    expect(defaultTarget(health([LF], null))).toBeNull();
    expect(defaultTarget(null)).toBeNull();
  });
  it("offers every radio but our own for a direct message, by name", () => {
    const nodes = {
      "!0badc0de": node("!0badc0de", "Gateway", { is_gateway: true }),
      "!0000e002": node("!0000e002", "Trupp 2"),
      "!0000e001": node("!0000e001", "Bergwacht 1"),
      "!0000e003": node("!0000e003", null),
    };
    expect(directOptions(nodes).map((n) => n.node)).toEqual(["!0000e003", "!0000e001", "!0000e002"]);
  });
  it("round-trips a target through the select value", () => {
    const targets: Target[] = [{ kind: "channel", name: "draeger-srs" }, { kind: "direct", node: "!0000e002" }];
    for (const tg of targets) expect(parseTargetKey(targetKey(tg))).toEqual(tg);
    expect(parseTargetKey("dm:nope")).toBeNull();
    expect(parseTargetKey("ch:")).toBeNull();
    expect(parseTargetKey("")).toBeNull();
  });
  it("direct messages and public channels take plain text only", () => {
    const h = health([LF, RC]);
    expect(plainOnlyReason({ kind: "direct", node: "!0000e002" }, h)).toBe("direct");
    expect(plainOnlyReason({ kind: "channel", name: "longfast" }, h)).toBe("public");
    expect(plainOnlyReason({ kind: "channel", name: "RescueCodec" }, h)).toBeNull();
    expect(plainOnlyReason(null, h)).toBeNull();
  });
});

describe("preview line", () => {
  it("a report shows its head, coded size, text size and chips", () => {
    expect(previewView("Kind gefunden …", FOUND, false, "en")).toEqual({
      report: true, head: "Person found", bytes: 10, textBytes: 65, chips: ["child", "conscious", "needs stretcher"],
    });
    expect(previewView("Kind gefunden …", FOUND, false, "de").head).toBe("Person gefunden");
    expect(previewView("Kind gefunden …", FOUND, false, "de").chips[0]).toBe("Kind");
  });
  it("plain text shows the text size and no chips", () => {
    expect(previewView("Kind gefunden …", FOUND, true, "en")).toEqual({ report: false, head: "", bytes: 65, textBytes: null, chips: [] });
    expect(previewView("Recall all", TEXT, false, "en")).toEqual({ report: false, head: "", bytes: 10, textBytes: null, chips: [] });
  });
  it("without a preview it counts the typed bytes", () => {
    expect(previewView("  Grüß Gott  ", null, false, "en").bytes).toBe(byteLength("Grüß Gott"));
  });
  it("takes the head of a line with a priority mark", () => {
    expect(headOf("[URGENT] Request: 2 x stretcher.")).toBe("Request");
    expect(headOf("2 persons found: unconscious.")).toBe("2 persons found");
  });
});

describe("send payload", () => {
  const h = health([LF, RC, DR]);
  const team: Target = { kind: "channel", name: "draeger-srs" };
  it("a report goes to /v2/messages as the extracted message, on the picked channel", () => {
    expect(buildSend(" Kind gefunden ", team, FOUND, false, h)).toEqual({
      path: "/v2/messages", body: { message: FOUND.message, channel: "draeger-srs" },
    });
  });
  it("plain text ticked, a text result or no preview go to /v2/text with the words", () => {
    const words = { path: "/v2/text", body: { text: "Kind gefunden", channel: "draeger-srs" } };
    expect(buildSend(" Kind gefunden ", team, FOUND, true, h)).toEqual(words);
    expect(buildSend(" Kind gefunden ", team, null, false, h)).toEqual(words);
    expect(buildSend("Recall all", team, TEXT, false, h)).toEqual({ path: "/v2/text", body: { text: "Recall all", channel: "draeger-srs" } });
  });
  it("a direct message is always plain text with `to`", () => {
    expect(buildSend("Kind gefunden", { kind: "direct", node: "!0000e002" }, FOUND, false, h)).toEqual({
      path: "/v2/text", body: { text: "Kind gefunden", to: "!0000e002" },
    });
  });
  it("a public channel never gets a report", () => {
    expect(buildSend("Kind gefunden", { kind: "channel", name: "LongFast" }, FOUND, false, h).path).toBe("/v2/text");
  });
});

describe("quick buttons and errors", () => {
  it("every prefilled sentence fits a text, in both languages", () => {
    for (const q of Object.values(QUICK)) for (const line of Object.values(q)) expect(byteLength(line)).toBeLessThanOrEqual(MAX_TEXT_BYTES);
  });
  it("429 and 503 read as plain words with the wait", () => {
    expect(sendErrorText({ status: 429, code: "rate_limited", message: "x", retryAfter: 23 }, "en")).toBe("Too many messages, send again in 23 s");
    expect(sendErrorText({ status: 429, code: "airtime_limit", message: "x", retryAfter: 300 }, "de")).toBe("Sendezeit aufgebraucht, nochmal senden in 5 Min.");
    expect(sendErrorText({ status: 503, code: "radio_unavailable", message: "x" }, "en")).toBe("No radio connected");
    expect(sendErrorText({ status: 0, code: "unreachable", message: "x" }, "en")).toBe("Gateway unreachable");
    expect(sendErrorText({ status: 400, code: "invalid_value", message: "no channel \"x\"" }, "en")).toBe("Could not send: no channel \"x\"");
  });
});
