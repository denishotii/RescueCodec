import { useSyncExternalStore } from "react";
import type { Lang } from "../lib/i18n";

export type Theme = "light" | "dark";
export type Filter = "all" | "found" | "request" | "hazard" | "track" | "other";
export interface Layers { finds: boolean; tracks: boolean; sectors: boolean; hazards: boolean; nodes: boolean }

export interface UiState {
  lang: Lang; theme: Theme; room: boolean; filter: Filter; layers: Layers; view3d: boolean;
  /** "all" or a channel name: which channel the feed and the actions show. A Mayday always shows. */
  channel: string;
  team: string; eta: number;
  /** Message seq or task key the operator picked; the map flies there. */
  focus: { seq: number; key: string | null; at: number } | null;
  tab: "actions" | "feed" | "map" | "stats" | "nodes";
}

const LS = "rescuecodec.dashboard.v2";
/** The theme is kept on its own and only when someone picks it: the default is Light, whatever the OS says. */
const THEME_LS = "rescuecodec.dashboard.theme";

function readSaved(): Partial<UiState> {
  try { return JSON.parse(localStorage.getItem(LS) ?? "{}") as Partial<UiState>; } catch { return {}; }
}

function defaults(): UiState {
  const saved = readSaved();
  const q = new URLSearchParams(typeof location !== "undefined" ? location.search : "");
  let picked: string | null = null;
  try { picked = localStorage.getItem(THEME_LS); } catch { /* storage blocked */ }
  const qTheme = q.get("theme");
  const qLang = q.get("lang");
  return {
    lang: qLang === "de" || qLang === "en" ? qLang : saved.lang === "de" ? "de" : "en",
    theme: qTheme === "dark" || qTheme === "light" ? qTheme : picked === "dark" ? "dark" : "light",
    room: q.get("room") === "1" ? true : saved.room ?? false,
    filter: "all",
    channel: "all",
    layers: { finds: true, tracks: true, sectors: true, hazards: true, nodes: true, ...(saved.layers ?? {}) },
    view3d: q.get("view") === "3d",
    team: typeof saved.team === "string" ? saved.team : "Leitstelle",
    eta: typeof saved.eta === "number" ? saved.eta : 15,
    focus: null,
    tab: "actions",
  };
}

let state = defaults();
const listeners = new Set<() => void>();

export function setUi(patch: Partial<UiState>) {
  state = { ...state, ...patch };
  try {
    const { lang, room, layers, team, eta } = state;
    localStorage.setItem(LS, JSON.stringify({ lang, room, layers, team, eta }));
    if (patch.theme) localStorage.setItem(THEME_LS, patch.theme);
  } catch { /* storage blocked: fine */ }
  applyDocument();
  listeners.forEach((l) => l());
}

export function applyDocument() {
  const el = document.documentElement;
  el.dataset.theme = state.theme;
  el.dataset.room = state.room ? "on" : "off";
  el.lang = state.lang;
}

export function focusOn(seq: number, key: string | null) { setUi({ focus: { seq, key, at: Date.now() } }); }

const subscribe = (l: () => void) => { listeners.add(l); return () => { listeners.delete(l); }; };
export const useUi = () => useSyncExternalStore(subscribe, () => state);
export const getUi = () => state;
