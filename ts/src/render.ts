// Rebuild a readable sentence from a canonical JSON message (spec section 10).
// The algorithm is fixed by the spec; the words come from spec/i18n/<lang>.json (bundled).
import * as C from "./codes.js";
import { degToE5, messageType } from "./codec.js";
import { LANGUAGES } from "./i18n-data.js";
import type { RescueMessage } from "./types.js";

export interface Language {
  lang: string;
  name: string;
  status: string;
  spec_version?: string;
  warning?: string;
  dir: string;
  punct: { head_sep: string; part_sep: string; list_sep: string; list_last: string; end: string };
  head: Record<string, string>;
  part: Record<string, string>;
  value: Record<string, string>;
}

/** Language codes that ship with the library (en, de reviewed; uk, tr, ar are machine drafts). */
export function languages(): string[] {
  return Object.keys(LANGUAGES).sort();
}

export function loadLanguage(lang: string): Language {
  const l = Object.hasOwn(LANGUAGES, lang) ? LANGUAGES[lang] : undefined;
  if (!l) throw new Error(`no language file for ${JSON.stringify(lang)}`);
  return l;
}

/** Integer degrees x 1e5 to "-50.69212" (always 5 decimals, "." as separator). */
export function formatE5(e5: number): string {
  const sign = e5 < 0 ? "-" : "";
  const a = Math.abs(e5);
  return `${sign}${Math.floor(a / 100000)}.${String(a % 100000).padStart(5, "0")}`;
}

type Vals = Record<string, string | number>;

function fill(template: string, values: Vals): string {
  let t = template;
  for (const [k, v] of Object.entries(values)) t = t.split("{" + k + "}").join(String(v));
  return t;
}

const coord = (v: number, field: string) => formatE5(degToE5(v, field));

/** Canonical JSON message to one readable line in `lang` (en, de, uk, tr, ar). */
export function render(msg: RescueMessage, lang = "en"): string {
  const L = loadLanguage(lang);
  const p = L.punct;
  const value = (group: string, name: string) =>
    name.startsWith("unknown_") || name.startsWith("bit_")
      ? fill(L.part.unknown_value, { n: name.split("_").slice(1).join("_") })
      : L.value[`${group}.${name}`];
  const part = (key: string, v: Vals = {}) => fill(L.part[key], v);
  const head = (key: string, v: Vals = {}) => fill(L.head[key], v);
  const joinList = (items: string[]) =>
    items.length <= 1 ? items.join("") : items.slice(0, -1).join(p.list_sep) + p.list_last + items[items.length - 1];

  const kind = messageType(msg);
  const body = ((msg as Record<string, unknown>)[kind] ?? {}) as Record<string, any>;
  const parts: string[] = [];
  let h: string;

  switch (kind) {
    case "found": {
      const count = body.count ?? 0;
      h = count >= 2 ? head("found_many", { n: count }) : head("found");
      for (const name of ["age", "consciousness", "breathing", "mobility"]) if (name in body) parts.push(value(name, body[name]));
      for (const i of body.injuries ?? []) parts.push(value("injuries", i));
      if (body.needs?.length) parts.push(part("found.needs", { list: joinList(body.needs.map((n: string) => value("needs", n))) }));
      if (body.responders) parts.push(part("found.responders", { n: body.responders }));
      break;
    }
    case "track": {
      const points: any[] = body.points ?? [];
      h = head("track");
      parts.push(points.length === 1 ? part("track.points_1") : part("track.points", { n: points.length }));
      if (points.length && "s" in points[0]) {
        const span = points[points.length - 1].s - points[0].s;
        if (span > 0) parts.push(part("track.minutes", { n: Math.floor((span + 30) / 60) }));
      }
      if (points.length) {
        const last = points[points.length - 1];
        parts.push(part("track.last", { lat: coord(last.lat, "lat"), lon: coord(last.lon, "lon") }));
      }
      break;
    }
    case "sector":
      h = head("sector", { sector: body.sector ?? 0 });
      parts.push(value("sector", body.status));
      if (body.coverage) parts.push(part("sector.coverage", { n: body.coverage }));
      break;
    case "request": {
      h = head("request");
      const resource = value("resource", body.resource);
      const count = body.count ?? 0;
      parts.push(count >= 2 ? part("request.count", { n: count, resource }) : resource);
      if ("urgency" in body) parts.push(value("urgency", body.urgency));
      break;
    }
    case "hazard":
      h = head("hazard");
      parts.push(value("hazard", body.kind));
      if ("severity" in body) parts.push(value("severity", body.severity));
      if (body.radius) parts.push(part("hazard.radius", { n: body.radius }));
      break;
    case "mayday":
      h = head("mayday");
      if ("reason" in body) parts.push(value("mayday", body.reason));
      if (body.persons === 1) parts.push(part("mayday.persons_1"));
      else if (body.persons) parts.push(part("mayday.persons", { n: body.persons }));
      break;
    case "ack": {
      const status = body.status ?? "";
      const key = `ack.${status}` in L.head ? `ack.${status}` : "ack.other";
      h = head(key, { ref: body.ref ?? 0 });
      if (body.node) parts.push(part("ack.node", { node: body.node }));
      if (body.team) parts.push(part("ack.team", { team: body.team }));
      if (body.eta) parts.push(part("ack.eta", { n: body.eta }));
      break;
    }
    case "text":
      h = head("text");
      parts.push(body.text ?? "");
      break;
    default:
      // A compact text with a word dictionary we do not have: say so, never guess the words.
      h = "words" in body && Object.hasOwn(L.head, "unknown_dict") ? head("unknown_dict", { n: body.words }) : head("unknown");
  }

  const endWith = (s: string) => (s.length > 0 && [".", "!", "?", p.end].includes(s[s.length - 1]) ? s : s + p.end);
  let sentence = endWith(h + (parts.length ? p.head_sep + parts.join(p.part_sep) : ""));
  if (msg.priority && msg.priority !== C.DEFAULT_PRIORITY[kind]) sentence = "[" + value("priority", msg.priority) + "] " + sentence;
  if (msg.pos) {
    let where = part("pos", { lat: coord(msg.pos.lat, "pos.lat"), lon: coord(msg.pos.lon, "pos.lon") });
    if ((msg.pos.acc ?? "gps") !== "gps") where += " (" + value("acc", msg.pos.acc!) + ")";
    sentence += " " + where + p.end;
  }
  if (msg.time !== undefined) sentence += " " + part("time", { time: msg.time }) + p.end;
  return sentence;
}
