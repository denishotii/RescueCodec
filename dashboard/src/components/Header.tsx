import { useEffect, useRef, useState } from "react";
import { ChartColumn, Ellipsis, Moon, Sun, Tv, X } from "lucide-react";
import { useData, useNow, useT } from "../state/hooks";
import { setUi, useUi } from "../state/ui";
import { clock, fmtNum } from "../lib/format";
import { StatsBand } from "./StatsBand";
import { Nodes } from "./Nodes";

function Mark() {
  // three contour lines around a summit point: the sheet, the search, the finding
  return (
    <svg className="mark" viewBox="0 0 32 32" width="30" height="30" aria-hidden="true">
      <path d="M3 22c3-9 9-16 17-16 6 0 9 4 9 9 0 7-6 12-14 12-6 0-10-2-12-5Z" fill="none" stroke="currentColor" strokeWidth="2" />
      <path d="M9 21c2-6 6-10 11-10 4 0 6 2 6 5 0 4-4 8-9 8-4 0-6-1-8-3Z" fill="none" stroke="currentColor" strokeWidth="2" />
      <circle cx="19" cy="17" r="2.6" fill="var(--mayday)" />
    </svg>
  );
}

/** One line in the header: what went on air against plain text. The full numbers live in Details. */
function Saving({ onOpen }: { onOpen: () => void }) {
  const { stats } = useData();
  const t = useT();
  const onAir = stats ? (stats.bytes_in.value ?? 0) + (stats.bytes_out.value ?? 0) : 0;
  const asText = stats?.text_equivalent_bytes.value ?? 0;
  const ratio = stats?.size_ratio.value;
  const tip = stats
    ? `${t("bytesOnAir")}: ${t(stats.bytes_in.source)}. ${t("asText")}: ${t(stats.text_equivalent_bytes.source)}. ${t(stats.text_equivalent_bytes.source === "measured" ? "measuredTip" : "estimatedTip")}`
    : t("details");
  return (
    <button className="saving" onClick={onOpen} title={tip} aria-haspopup="dialog">
      <ChartColumn size={16} aria-hidden="true" />
      {onAir > 0 ? (
        <span className="saving-line num">
          <b>{fmtNum(onAir)} B</b> {t("onAirInsteadOf")} {fmtNum(asText)} B
          {ratio ? <> · <em>{fmtNum(ratio, 1)}× {t("smaller")}</em></> : null}
        </span>
      ) : <span className="saving-line">{t("details")}</span>}
    </button>
  );
}

/** Traffic numbers and the radio list, out of the way until someone asks. */
function DetailsDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const ref = useRef<HTMLDialogElement>(null);
  const t = useT();
  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) d.showModal();
    if (!open && d.open) d.close();
  }, [open]);
  return (
    <dialog ref={ref} className="drawer" aria-labelledby="details-h" onClose={onClose}
      onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      {open && (
        <div className="drawer-body">
          <header className="drawer-head">
            <h2 id="details-h">{t("detailsTitle")}</h2>
            <button className="icon-btn" onClick={onClose} aria-label={t("close")} title={t("close")}><X size={18} aria-hidden="true" /></button>
          </header>
          <StatsBand />
          <Nodes />
        </div>
      )}
    </dialog>
  );
}

/** One pill for the link that matters: is a radio on the gateway, and which one. */
function StatusPill() {
  const { conn, health, nodes, lastContact } = useData();
  const t = useT();
  const radio = health?.radio;
  let tone: "ok" | "wait" | "bad" = "ok";
  let label: string;
  if (conn === "offline") { tone = "bad"; label = t("offline"); }
  else if (!health) { tone = "wait"; label = conn === "reconnecting" ? t("reconnecting") : t("connecting"); }
  else if (!radio?.connected) { tone = "bad"; label = t("radioNotConnected"); }
  else {
    // the radio's own name from /v2/nodes ("Base"), the node id only when no name is known
    const me = (radio.node && nodes[radio.node]) || Object.values(nodes).find((n) => n.is_gateway);
    const name = me?.long_name || me?.short_name || radio.node;
    label = `${t("radioConnected")}${name ? ` · ${name}` : ""}`;
    if (conn === "reconnecting") tone = "wait";
  }
  const tip = radio?.connected
    ? [radio.node, radio.region, radio.preset, radio.channel ? `#${radio.channel}` : null, radio.firmware].filter(Boolean).join(" · ")
    : undefined;
  return (
    <p className={`status-pill status-${tone}`} role="status" title={tip}>
      <span className="dot" aria-hidden="true" />
      <span className="status-label">{label}</span>
      {lastContact && conn === "offline" && <span className="muted num">{t("lastData")} {clock(new Date(lastContact).toISOString(), false)}</span>}
      {radio?.connected && radio.channel_error && <span className="status-err" role="alert">{radio.channel_error}</span>}
    </p>
  );
}

/** MQTT shows only once it has been up in this session (the gateway does not say whether it is configured). */
function MqttPill() {
  const { health, conn } = useData();
  const t = useT();
  const seen = useRef(false);
  const up = !!health?.mqtt.connected && conn !== "offline";
  if (up) seen.current = true;
  if (!seen.current) return null;
  return (
    <p className={`status-pill status-${up ? "ok" : "bad"} status-small`} role="status">
      <span className="dot" aria-hidden="true" /><span>{t("mqtt")}</span>
    </p>
  );
}

/** Room view, theme and the Details drawer: things a command post sets once. */
function Menu({ onDetails }: { onDetails: () => void }) {
  const ui = useUi();
  const { nodes } = useData();
  const t = useT();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const away = (e: PointerEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false); };
    const esc = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
    document.addEventListener("pointerdown", away);
    document.addEventListener("keydown", esc);
    return () => { document.removeEventListener("pointerdown", away); document.removeEventListener("keydown", esc); };
  }, [open]);
  const dark = ui.theme === "dark";
  return (
    <div className="menu-wrap" ref={ref}>
      <button className="icon-btn" aria-expanded={open} aria-haspopup="true" aria-label={t("menu")} title={t("menu")} onClick={() => setOpen((o) => !o)}>
        <Ellipsis size={18} aria-hidden="true" />
      </button>
      {open && (
        <div className="menu" role="group" aria-label={t("menu")}>
          <button className="menu-item" onClick={() => { setOpen(false); onDetails(); }}>
            <ChartColumn size={16} aria-hidden="true" /><span>{t("detailsTitle")}</span>
            <span className="menu-state num">{Object.keys(nodes).length} {t("radiosShort")}</span>
          </button>
          <button className="menu-item" aria-pressed={ui.room} onClick={() => setUi({ room: !ui.room })}>
            <Tv size={16} aria-hidden="true" /><span>{t("room")}</span>
            <span className="menu-state">{ui.room ? "✓" : ""}</span>
          </button>
          <button className="menu-item" onClick={() => setUi({ theme: dark ? "light" : "dark" })}>
            {dark ? <Sun size={16} aria-hidden="true" /> : <Moon size={16} aria-hidden="true" />}
            <span>{t("theme")}</span>
            <span className="menu-state">{dark ? t("dark") : t("light")}</span>
          </button>
        </div>
      )}
    </div>
  );
}

export function Header() {
  const ui = useUi();
  const t = useT();
  const [details, setDetails] = useState(false);
  useNow(5000);

  return (
    <header className="topbar">
      <div className="brand">
        <Mark />
        <div>
          <p className="brand-name">{t("appName")}</p>
          <p className="brand-sub">{t("commandPost")}</p>
        </div>
      </div>

      <div className="status-row" aria-label="Status">
        <StatusPill />
        <MqttPill />
      </div>

      <Saving onOpen={() => setDetails(true)} />

      <div className="controls">
        <div className="seg" role="group" aria-label={t("language")}>
          {(["en", "de"] as const).map((l) => (
            <button key={l} className={ui.lang === l ? "on" : ""} aria-pressed={ui.lang === l} onClick={() => setUi({ lang: l })}>{l.toUpperCase()}</button>
          ))}
        </div>
        <Menu onDetails={() => setDetails(true)} />
      </div>
      <DetailsDrawer open={details} onClose={() => setDetails(false)} />
    </header>
  );
}
