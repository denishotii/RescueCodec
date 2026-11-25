// Logic of the Send panel (the command post's composer), kept pure so it is unit tested.
// Who to send to, what the preview says, and which API call a send becomes.
import type { Channel, ExtractResult, Health, MeshNode, SendMessageBody, SendTextBody } from "../api/types";
import { t, type Lang } from "./i18n";

/** A channel (by name, like the gateway) or one person (a direct message to a node). */
export type Target = { kind: "channel"; name: string } | { kind: "direct"; node: string };

/** The gateway checks a text at 180 bytes of UTF-8 (spec), so the composer stops there. */
export const MAX_TEXT_BYTES = 180;

export const byteLength = (s: string) => new TextEncoder().encode(s).length;

/** Channels the command post may pick: the gateway's channels (health), private ones first, the default one first. */
export function sendChannels(health: Health | null): Channel[] {
  const all = health?.radio.channels ?? [];
  const dflt = health?.radio.channel?.toLowerCase();
  const rank = (c: Channel) => (c.public ? 2 : c.name.toLowerCase() === dflt ? 0 : 1);
  return [...all].sort((a, b) => rank(a) - rank(b) || a.index - b.index);
}

/** The team channel: the gateway's default channel, else the first private one. Null when there is none yet. */
export function defaultTarget(health: Health | null): Target | null {
  const list = sendChannels(health);
  const first = list.find((c) => !c.public);
  if (first) return { kind: "channel", name: first.name };
  const named = health?.radio.channel;
  return named ? { kind: "channel", name: named } : null;
}

export const nodeName = (n: Pick<MeshNode, "node" | "long_name" | "short_name">) => n.long_name || n.short_name || n.node;

/** People for a direct message: every radio but our own, by name. */
export function directOptions(nodes: Record<string, MeshNode>): MeshNode[] {
  return Object.values(nodes).filter((n) => !n.is_gateway)
    .sort((a, b) => nodeName(a).localeCompare(nodeName(b)) || a.node.localeCompare(b.node));
}

/** A target as one string for a <select>: "ch:<name>" or "dm:<node>". */
export const targetKey = (t: Target) => (t.kind === "channel" ? `ch:${t.name}` : `dm:${t.node}`);
export function parseTargetKey(key: string): Target | null {
  if (key.startsWith("ch:") && key.length > 3) return { kind: "channel", name: key.slice(3) };
  if (/^dm:![0-9a-f]{8}$/.test(key)) return { kind: "direct", node: key.slice(3) };
  return null;
}

/**
 * Why this target takes plain text only, or null. A direct message is always a plain Meshtastic text (the gateway
 * refuses a report there). On a public channel (no key or the default key, where stock Meshtastic users are) the
 * gateway never sends a report either.
 */
export function plainOnlyReason(target: Target | null, health: Health | null): "direct" | "public" | null {
  if (!target) return null;
  if (target.kind === "direct") return "direct";
  const ch = health?.radio.channels?.find((c) => c.name.toLowerCase() === target.name.toLowerCase());
  return ch?.public ? "public" : null;
}

/** What the preview line shows. `report` = it goes out as a compact report; else as a plain text. */
export interface PreviewView {
  report: boolean;
  /** "Person found", "Request", ... from the readable line; "" for a text (the UI says "Text"). */
  head: string;
  bytes: number;
  /** Plain-text size of the same words, only when the report is smaller. */
  textBytes: number | null;
  chips: string[];
}

/** The head of a readable line: "[URGENT] Request: 2 x stretcher." -> "Request". */
export const headOf = (line: string) => line.replace(/^\[[^\]]*\]\s*/, "").split(":")[0].trim();

/**
 * The preview for the current text. `plain`: the operator ticked "Send as plain text" or the target takes plain
 * text only. Without a preview from the gateway yet, the text's own size is shown.
 */
export function previewView(text: string, preview: ExtractResult | null, plain: boolean, lang: Lang): PreviewView {
  const words = text.trim();
  if (!preview || plain || preview.type === "text") {
    const bytes = !preview ? byteLength(words) : plain ? preview.text_bytes : preview.coded_bytes;
    return { report: false, head: "", bytes, textBytes: null, chips: [] };
  }
  return {
    report: true,
    head: headOf(preview.text[lang]),
    bytes: preview.coded_bytes,
    textBytes: preview.text_bytes > preview.coded_bytes ? preview.text_bytes : null,
    chips: preview.fields.map((f) => f.text[lang]),
  };
}

export type SendCall =
  | { path: "/v2/messages"; body: SendMessageBody }
  | { path: "/v2/text"; body: SendTextBody };

/**
 * The API call for a send. A report found in the text goes to /v2/messages as the extracted message (the gateway adds
 * the id). Everything else, a direct message, a public channel or "plain text" ticked, goes to /v2/text with the words.
 * `preview` must belong to this text (the caller checks); null means "no report".
 */
export function buildSend(text: string, target: Target, preview: ExtractResult | null, plain: boolean, health: Health | null): SendCall {
  const words = text.trim();
  const where = target.kind === "channel" ? { channel: target.name } : { to: target.node };
  if (!plain && !plainOnlyReason(target, health) && preview && preview.type !== "text") {
    return { path: "/v2/messages", body: { message: preview.message, ...where } };
  }
  return { path: "/v2/text", body: { text: words, ...where } };
}

/** Prefilled sentences of the quick buttons. "Meet at" ends open: the operator types the place. */
export type QuickId = "recall" | "status" | "meet" | "request" | "hazard";
export const QUICK: Record<QuickId, Record<Lang, string>> = {
  recall: { en: "Recall: all teams return to the command post now.", de: "Rückruf: alle Trupps sofort zurück zur Einsatzleitung." },
  status: { en: "Status check: all teams report position and status.", de: "Statusabfrage: alle Trupps Position und Lage melden." },
  meet: { en: "Meet at ", de: "Treffpunkt: " },
  request: { en: "We need 2 stretchers now", de: "Wir brauchen sofort 2 Tragen" },
  hazard: { en: "Hazard: rockfall on the north trail, high risk", de: "Gefahr: Steinschlag am Nordweg, hohe Gefahr" },
};

/** One line for a failed send, in the operator's words. 429 carries the wait from the gateway. */
export function sendErrorText(e: { status: number; code: string; message: string; retryAfter?: number }, lang: Lang): string {
  if (e.code === "rate_limited") return `${t(lang, "rateLimited")} ${e.retryAfter ?? 60} s`;
  if (e.code === "airtime_limit") return `${t(lang, "airtimeLimit")} ${Math.ceil((e.retryAfter ?? 60) / 60)} ${t(lang, "min")}`;
  if (e.code === "radio_unavailable") return t(lang, "radioUnavailable");
  if (e.status === 0) return t(lang, "offline");
  return `${t(lang, "sendFailed")}: ${e.message}`;
}
