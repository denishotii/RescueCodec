import { describe, expect, it } from "vitest";
import {
  MAX_PACKED_TEXT_BYTES, MAX_TEXT_BYTES, QUICK, buildSend, byteLength, counterView, defaultTarget, directOptions,
  extractBody, headOf, inputCap, parseTargetKey, plainOnlyReason, previewView, sendChannels, sendErrorText, targetKey,
  textLimit, type Target,
} from "../src/lib/compose";
import type { Channel, ExtractResult, Health, MeshNode, RadioChannel } from "../src/api/types";
import { t } from "../src/lib/i18n";

const RC: Channel = { index: 1, name: "RescueCodec", public: false };
const DR: Channel = { index: 2, name: "draeger-srs", public: false };
const LF: Channel = { index: 0, name: "LongFast", public: true };
const health = (channels: RadioChannel[], channel: string | null = "RescueCodec"): Health => ({
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

describe("compact texts and the 400-byte limit on a chat that packs", () => {
  const PACKS: RadioChannel = { ...RC, mode: "auto", send_as: "compressed" };
  const NORMAL: RadioChannel = { ...DR, mode: "normal", send_as: "normal" };
  const h = { ...health([PACKS, NORMAL, LF]), compact_text: true, text_limits: { text: 180, packed: 400 } };
  const team: Target = { kind: "channel", name: "RescueCodec" };
  const WORDS: ExtractResult = {
    ...TEXT, message: { text: { text: "Hi, are you still at the station?" } }, coded_bytes: 58, text_bytes: 151,
    send_as: "compressed", max_text_bytes: 400, form: "words", cut: false,
  };

  it("takes 400 B on a chat that packs and 180 B elsewhere", () => {
    expect(MAX_PACKED_TEXT_BYTES).toBe(400);
    expect(textLimit(team, h, null)).toBe(400);
    expect(textLimit({ kind: "channel", name: "rescuecodec" }, h, null)).toBe(400);
    expect(textLimit({ kind: "channel", name: "draeger-srs" }, h, null)).toBe(180);
    expect(textLimit({ kind: "channel", name: "LongFast" }, h, null)).toBe(180);
    expect(textLimit({ kind: "direct", node: "!0000e002" }, h, null)).toBe(180);
    expect(textLimit(null, null, null)).toBe(180);
  });
  it("an older gateway without send_as or text_limits keeps 180 B", () => {
    expect(textLimit(team, health([RC]), null)).toBe(180);
    expect(textLimit(team, health([{ ...RC, send_as: "compressed" }]), null)).toBe(400);
  });
  it("the gateway's preview has the last word, a direct message too", () => {
    expect(textLimit({ kind: "direct", node: "!0000e002" }, h, { ...TEXT, max_text_bytes: 400 })).toBe(400);
    expect(textLimit(team, h, { ...TEXT, max_text_bytes: 180 })).toBe(180);
    expect(textLimit(team, h, TEXT)).toBe(400);
  });
  it("asks the gateway for the chosen chat", () => {
    expect(extractBody("  Hi there ", team)).toEqual({ text: "Hi there", channel: "RescueCodec" });
    expect(extractBody("Hi", { kind: "direct", node: "!0000e002" })).toEqual({ text: "Hi", to: "!0000e002" });
    expect(extractBody("Hi", null)).toEqual({ text: "Hi" });
  });
  it("counts against the limit and warns when the text will be cut", () => {
    expect(counterView("x".repeat(300), 400, null)).toEqual({ used: 300, limit: 400, near: false, cut: false });
    expect(counterView("x".repeat(390), 400, null)).toEqual({ used: 390, limit: 400, near: true, cut: false });
    expect(counterView("x".repeat(300), 400, { ...WORDS, cut: true }).cut).toBe(true);
    expect(counterView("x".repeat(300), 180, null)).toEqual({ used: 300, limit: 180, near: true, cut: true });
    expect(counterView("Grüß", 180, null).used).toBe(6);
  });
  it("never cuts text that is already there when the limit drops", () => {
    expect(inputCap(400, "x".repeat(10))).toBe(400);
    expect(inputCap(180, "x".repeat(300))).toBe(300);
  });
  it("shows compact words with the plain size", () => {
    expect(previewView("Hi, are you still at the station?", WORDS, false, "en")).toEqual({
      report: false, head: "Compact words", bytes: 58, textBytes: 151, chips: [],
    });
    expect(previewView("Hi, are you still at the station?", WORDS, true, "de")).toEqual({
      report: false, head: "Kompakte Wörter", bytes: 58, textBytes: 151, chips: [],
    });
    expect(previewView("hello world", { ...WORDS, form: "lean", coded_bytes: 6, text_bytes: 11 }, false, "en")).toEqual({
      report: false, head: "Compact words", bytes: 6, textBytes: 11, chips: [],
    });
    expect(previewView("Hi", { ...WORDS, form: "deflate" }, false, "en").head).toBe("");
  });
  it("shows the team vocabulary of a domain frame", () => {
    const text = "Patient is conscious, bleeding from the leg, apply a tourniquet.";
    const DOMAIN: ExtractResult = { ...WORDS, message: { text: { text } }, form: "domain", vocab: "medical", vocab_label: "Medical", coded_bytes: 19, text_bytes: 64 };
    expect(previewView(text, DOMAIN, false, "en")).toEqual({
      report: false, head: "Compact words · Medical", bytes: 19, textBytes: 64, chips: [],
    });
    expect(previewView(text, { ...DOMAIN, vocab: "sar", vocab_label: "S&R" }, false, "de").head).toBe("Kompakte Wörter · S&R");
    // an older gateway without vocab_label: still compact words
    expect(previewView(text, { ...DOMAIN, vocab_label: undefined }, false, "en").head).toBe("Compact words");
    // a domain frame is a text: it goes to /v2/text with the words, the gateway picks the form again
    expect(buildSend(text, team, DOMAIN, false, h)).toEqual({ path: "/v2/text", body: { text, channel: "RescueCodec" } });
    expect(previewView("Hi", { ...WORDS, form: "plain", coded_bytes: 151 }, false, "en")).toEqual({
      report: false, head: "", bytes: 151, textBytes: null, chips: [],
    });
  });
  it("says it in plain words, in both languages", () => {
    expect(t("en", "willBeCut")).toBe("Too long: will be cut to fit one radio message");
    expect(t("de", "willBeCut")).toBe("Zu lang: wird gekürzt, damit es in eine Funknachricht passt");
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
