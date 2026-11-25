import { Moon, Sun, Tv } from "lucide-react";
import { useData, useNow, useT } from "../state/hooks";
import { setUi, useUi } from "../state/ui";
import { clock } from "../lib/format";

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

export function Header() {
  const { conn, health, lastContact } = useData();
  const ui = useUi();
  const t = useT();
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
          <span>{stale ? `${t("radio")}: ?` : radioOk ? `${t("radio")} ${radio?.node ?? ""}` : t("radioOff")}</span>
          {radioOk && <span className="muted">{[radio?.region, radio?.preset, radio?.channel].filter(Boolean).join(" · ")}</span>}
          {radioOk && radio?.channel_error && <span role="alert">{radio.channel_error}</span>}
        </li>
        <li className={`status status-${health?.mqtt.connected && !stale ? "ok" : "idle"}`}>
          <span className="dot" aria-hidden="true" />
          <span>{t("mqtt")}</span>
        </li>
      </ul>

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
    </header>
  );
}
