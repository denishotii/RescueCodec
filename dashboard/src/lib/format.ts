import type { Health, MeshNode, MessageRecord, MessageType, Priority, Task } from "../api/types";
import { type Lang, type Key, t } from "./i18n";

export const pad = (n: number) => String(n).padStart(2, "0");

export function clock(iso: string, withSeconds = true): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "--:--";
  return `${pad(d.getHours())}:${pad(d.getMinutes())}${withSeconds ? ":" + pad(d.getSeconds()) : ""}`;
}

export function relTime(iso: string | null | undefined, now: number, lang: Lang): string {
  if (!iso) return "";
  const s = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000));
  if (s < 10) return t(lang, "justNow");
  const m = Math.floor(s / 60);
  const txt = s < 60 ? `${s} s` : m < 60 ? `${m} min` : `${Math.floor(m / 60)} h ${m % 60} min`;
  return lang === "de" ? `vor ${txt}` : `${txt} ago`;
}

export function nodeLabel(id: string, nodes: Record<string, MeshNode>): string {
  const n = nodes[id];
  return n?.long_name || n?.short_name || id;
}

const TYPE_KEY: Record<MessageType, Key> = {
  found: "typeFound", track: "typeTrack", sector: "typeSector", request: "typeRequest", hazard: "typeHazard",
  mayday: "typeMayday", ack: "typeAck", text: "typeText", unknown: "typeUnknown",
};
export const typeName = (lang: Lang, type: MessageType) => t(lang, TYPE_KEY[type]);

const PRIO_KEY: Record<Priority, Key> = { routine: "prioRoutine", normal: "prioNormal", urgent: "prioUrgent", critical: "prioCritical" };
export const prioName = (lang: Lang, p: Priority) => t(lang, PRIO_KEY[p]);

const PRIO_RANK: Record<Priority, number> = { critical: 3, urgent: 2, normal: 1, routine: 0 };
export const prioRank = (p: Priority) => PRIO_RANK[p];

export const isActiveMayday = (task: Task) => task.type === "mayday" && (task.state === "open" || task.state === "accepted");

export function sortTasks(a: Task, b: Task): number {
  return prioRank(b.priority) - prioRank(a.priority) || a.created.localeCompare(b.created);
}

export function bytesLabel(n: number | null | undefined): string {
  if (n == null) return "–";
  return n >= 10000 ? `${(n / 1000).toFixed(1)} kB` : `${Math.round(n)} B`;
}

export function textBytes(r: MessageRecord): number {
  return new TextEncoder().encode(r.text.en).length;
}

export function fmtNum(n: number | null | undefined, digits = 0): string {
  if (n == null || Number.isNaN(n)) return "–";
  return n.toLocaleString("en-US", { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

/**
 * How an outgoing message ended. The gateway reports `failed` with error `max_retransmit` when a broadcast
 * was sent but no neighbour radio repeated it. That is normal when no relay is in range, and the other side
 * may well have heard it, so the dashboard says "no relay confirmed" and not "failed".
 */
export function isNoRelay(rec: Pick<MessageRecord, "direction" | "status" | "error">): boolean {
  return rec.direction === "out" && rec.status === "failed" && rec.error === "max_retransmit";
}

/**
 * The gateway writes the position into the sentence ("… Position 50.94891, 6.98153 (estimated).").
 * The dashboard shows a map pin for it, so the words drop it.
 */
export function stripPosition(text: string): string {
  return text.replace(/\s*Position -?\d+(?:\.\d+)?, -?\d+(?:\.\d+)?(?: \([^)]*\))?\.?/g, "").trim();
}

/** Raw node ids ("!9ea67594") in a sentence become the radio's name when the dashboard knows it. */
export function nameIds(text: string, nodes: Record<string, MeshNode>): string {
  return text.replace(/!([0-9a-f]{8})\b/g, (id) => nodeLabel(id, nodes));
}

/** Words for a card: no "Message:" in front of a plain text, no position (a pin shows it), names for node ids. */
export function cardText(raw: string, type: MessageType, lang: Lang, nodes: Record<string, MeshNode>): string {
  const prefix = `${typeName(lang, type)}: `;
  const body = type === "text" && raw.startsWith(prefix) ? raw.slice(prefix.length) : raw;
  return nameIds(stripPosition(body), nodes);
}

type Coded = Pick<MessageRecord, "codec" | "message">;

/**
 * How it went on air: "compact" (word tokens, a compact frame or a domain frame with a team vocabulary), "packed"
 * (DEFLATE or a RescueCodec report), or null for a plain text.
 */
export function codecForm(rec: Coded): "compact" | "packed" | null {
  if (rec.codec.version === 0) return null;
  const form = rec.codec.form;
  if (form === "words" || form === "lean" || form === "domain") return "compact";
  const body = rec.message.text ?? rec.message.unknown;
  return body?.words || body?.vocab != null ? "compact" : "packed";
}

/**
 * The team vocabulary of a domain frame for the tag, e.g. "S&R" (the gateway's `codec.vocab_label`). Null for every
 * other form, and for a vocabulary the gateway does not have (the line itself says "unsupported vocabulary").
 */
export function vocabLabel(rec: Coded): string | null {
  return rec.codec.form === "domain" ? rec.codec.vocab_label ?? null : null;
}

/** The tag of a coded message: "compact · S&R · 9 B", "compact · 6 B", "packed · 23 B"; null for a plain text. */
export function formTag(rec: Coded, lang: Lang): string | null {
  const form = codecForm(rec);
  if (!form) return null;
  return [t(lang, form === "compact" ? "formCompact" : "formPacked"), vocabLabel(rec), bytesLabel(rec.codec.size)]
    .filter(Boolean).join(" · ");
}

/** Hover text of the tag for a domain frame: "Team vocabulary: Search & Rescue (10-bit codes)". */
export function vocabTitle(rec: Coded, health: Health | null, lang: Lang): string | undefined {
  const short = vocabLabel(rec);
  if (!short) return undefined;
  const v = health?.vocabularies?.find((x) => x.domain === rec.codec.vocab);
  return `${t(lang, "teamVocabulary")}: ${v?.label ?? short}${v ? ` (${v.bits}-bit)` : ""}`;
}
