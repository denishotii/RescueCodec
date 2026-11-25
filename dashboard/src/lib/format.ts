import type { MeshNode, MessageRecord, MessageType, Priority, Task } from "../api/types";
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
