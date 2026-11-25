import { createContext, useContext, useEffect, useMemo, useState, useSyncExternalStore } from "react";
import type { DataStore, DataState } from "./store";
import { useUi } from "./ui";
import { t as tr, type Key } from "../lib/i18n";

export const StoreContext = createContext<DataStore | null>(null);

export function useStore(): DataStore {
  const s = useContext(StoreContext);
  if (!s) throw new Error("StoreContext missing");
  return s;
}

export function useData(): DataState {
  const s = useStore();
  return useSyncExternalStore(s.subscribe, s.getSnapshot);
}

/** Translate with the current UI language. */
export function useT() {
  const { lang } = useUi();
  return useMemo(() => (k: Key) => tr(lang, k), [lang]);
}

/** A ticking clock so "2 min ago" labels stay right. */
export function useNow(intervalMs = 15_000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => { const id = setInterval(() => setNow(Date.now()), intervalMs); return () => clearInterval(id); }, [intervalMs]);
  return now;
}
