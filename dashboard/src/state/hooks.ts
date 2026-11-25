import { createContext, useContext, useEffect, useMemo, useState, useSyncExternalStore } from "react";
import type { DataStore, DataState } from "./store";
import { useUi } from "./ui";
import { t as tr, type Key } from "../lib/i18n";
import { channelOptions, channelsInUse, chipFor, showChannels } from "../lib/channels";

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

/**
 * Channels for the UI. `options`: filter choices. `chips`: more than one channel in use. `chipOn(name)`: a card shows its channel chip.
 * `pick`: the channel to show, "all" when the chosen one is gone or there is nothing to choose.
 */
export function useChannels() {
  const { health, messages, tasks } = useData();
  const { channel } = useUi();
  return useMemo(() => {
    const list = Object.values(tasks);
    const options = channelOptions(health, messages, list);
    const filter = showChannels(options);
    const known = options.some((o) => o.toLowerCase() === channel.toLowerCase());
    const many = showChannels(channelsInUse(messages, list));
    const home = health?.radio.channel ?? null;
    /** Show the channel chip on a card for this channel name. */
    const chipOn = (name: string | null | undefined) => chipFor(many, name, home);
    return { options, filter, chips: many, chipOn, pick: filter && known ? channel : "all" };
  }, [health, messages, tasks, channel]);
}

/** A ticking clock so "2 min ago" labels stay right. */
export function useNow(intervalMs = 15_000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => { const id = setInterval(() => setNow(Date.now()), intervalMs); return () => clearInterval(id); }, [intervalMs]);
  return now;
}
