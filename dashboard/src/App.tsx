import { useEffect, useMemo } from "react";
import { Activity, ClipboardList, Map as MapIcon, Radio, Rss } from "lucide-react";
import { DataStore } from "./state/store";
import { StoreContext, useData, useT } from "./state/hooks";
import { applyDocument, setUi, useUi } from "./state/ui";
import { Header } from "./components/Header";
import { MaydayBanner } from "./components/MaydayBanner";
import { ActionsBoard } from "./components/ActionsBoard";
import { Feed } from "./components/Feed";
import { Nodes } from "./components/Nodes";
import { StatsBand } from "./components/StatsBand";
import { MapPanel } from "./map/MapPanel";
import type { Key } from "./lib/i18n";

const TABS: { id: ReturnType<typeof useUi>["tab"]; label: Key; Icon: typeof Rss }[] = [
  { id: "actions", label: "tabActions", Icon: ClipboardList },
  { id: "feed", label: "tabFeed", Icon: Rss },
  { id: "map", label: "tabMap", Icon: MapIcon },
  { id: "stats", label: "tabStats", Icon: Activity },
  { id: "nodes", label: "tabNodes", Icon: Radio },
];

function Notices() {
  const { conn, health, loaded } = useData();
  const t = useT();
  if (conn === "offline") return <p className="notice notice-bad" role="status">{t("staleBanner")}</p>;
  if (loaded && health && !health.radio.connected) return <p className="notice notice-warn" role="status">{t("noRadioBanner")}</p>;
  return null;
}

function Shell() {
  const ui = useUi();
  const t = useT();
  return (
    <div className="app" data-tab={ui.tab}>
      <a className="skip-link" href="#main-feed">{t("skipToMain")}</a>
      <MaydayBanner />
      <Header />
      <Notices />
      <main className="board">
        <div className="slot slot-actions"><ActionsBoard /></div>
        <div className="slot slot-map"><MapPanel /></div>
        <div className="slot slot-feed"><Feed /></div>
        <div className="slot slot-nodes"><Nodes /></div>
        <div className="slot slot-stats"><StatsBand /></div>
      </main>
      <nav className="tabbar" aria-label="Panels">
        {TABS.map(({ id, label, Icon }) => (
          <button key={id} className={ui.tab === id ? "on" : ""} aria-current={ui.tab === id ? "page" : undefined} onClick={() => setUi({ tab: id })}>
            <Icon size={20} aria-hidden="true" /><span>{t(label)}</span>
          </button>
        ))}
      </nav>
    </div>
  );
}

export function App() {
  const store = useMemo(() => new DataStore(), []);
  useEffect(() => { applyDocument(); store.start(); return () => store.stop(); }, [store]);
  return <StoreContext.Provider value={store}><Shell /></StoreContext.Provider>;
}
