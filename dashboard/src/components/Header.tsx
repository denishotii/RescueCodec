import { useEffect, useRef, useState } from "react";
import { ChartColumn, Moon, Sun, Tv, X } from "lucide-react";
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
  const { stats, nodes } = useData();
  const t = useT();
  const onAir = stats ? (stats.bytes_in.value ?? 0) + (stats.bytes_out.value ?? 0) : 0;
  const asText = stats?.text_equivalent_bytes.value ?? 0;
  const ratio = stats?.size_ratio.value;
  const radios = Object.keys(nodes).length;
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
      <span className="saving-radios num">{radios} {t("radiosShort")}</span>
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

export function Header() {
  const { conn, health, lastContact } = useData();
  const ui = useUi();
  const t = useT();
  const [details, setDetails] = useState(false);
  useNow(5000);

  const connLabel = conn === "live" ? t("connected") : conn === "connecting" ? t("connecting") : conn === "reconnecting" ? t("reconnecting") : t("offline");
  const radio = health?.radio;
  const stale = conn === "offline"; // radio and MQTT state is unknown while the gateway is out of reach
  const radioOk = !!radio?.connected && !stale;

  return (
    <header className="topbar">
      <div className="brand">
        <Mark />
        <div>
          <p className="brand-name">{t("appName")}</p>
          <p className="brand-sub">{t("commandPost")}</p>
        </div>
      </div>

      <ul className="status-row" aria-label="Status">
        <li className={`status status-${conn === "live" ? "ok" : conn === "offline" ? "bad" : "wait"}`}>
          <span className="dot" aria-hidden="true" />
          <span>{connLabel}</span>
          {lastContact && conn !== "live" && <span className="muted num">{t("lastData")} {clock(new Date(lastContact).toISOString(), false)}</span>}
        </li>
        <li className={`status status-${health && !stale ? (radioOk ? "ok" : "bad") : "idle"}`}>
          <span className="dot" aria-hidden="true" />
          <span title={radioOk ? [radio?.region, radio?.preset, radio?.channel].filter(Boolean).join(" · ") : undefined}>
            {stale ? `${t("radio")}: ?` : radioOk ? `${t("radio")} ${radio?.node ?? ""}` : t("radioOff")}
          </span>
          {radioOk && radio?.channel_error && <span role="alert">{radio.channel_error}</span>}
        </li>
        <li className={`status status-${health?.mqtt.connected && !stale ? "ok" : "idle"}`}>
          <span className="dot" aria-hidden="true" />
          <span>{t("mqtt")}</span>
        </li>
      </ul>

      <Saving onOpen={() => setDetails(true)} />

      <div className="controls">
        <div className="seg" role="group" aria-label={t("language")}>
          {(["en", "de"] as const).map((l) => (
            <button key={l} className={ui.lang === l ? "on" : ""} aria-pressed={ui.lang === l} onClick={() => setUi({ lang: l })}>{l.toUpperCase()}</button>
          ))}
        </div>
        <button className="icon-btn" aria-pressed={ui.room} title={ui.room ? t("roomOn") : t("roomOff")} aria-label={t("room")} onClick={() => setUi({ room: !ui.room })}>
          <Tv size={18} aria-hidden="true" />
        </button>
        <button className="icon-btn" title={t("theme")} aria-label={`${t("theme")}: ${ui.theme === "dark" ? t("dark") : t("light")}`}
          onClick={() => setUi({ theme: ui.theme === "dark" ? "light" : "dark" })}>
          {ui.theme === "dark" ? <Sun size={18} aria-hidden="true" /> : <Moon size={18} aria-hidden="true" />}
        </button>
      </div>
      <DetailsDrawer open={details} onClose={() => setDetails(false)} />
    </header>
  );
}
