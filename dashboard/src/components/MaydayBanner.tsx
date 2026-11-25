import { useEffect } from "react";
import { MapPin, Siren } from "lucide-react";
import { useData, useNow, useT } from "../state/hooks";
import { focusOn, useUi } from "../state/ui";
import { cardText, isActiveMayday, nodeLabel, relTime, sortTasks } from "../lib/format";
import { utmLabel } from "../lib/geo";
import { AckControl } from "./AckControl";

/** Overrides everything: full width, above the header, and the browser tab says MAYDAY. */
export function MaydayBanner() {
  const { tasks, nodes, messages } = useData();
  const { lang } = useUi();
  const t = useT();
  const now = useNow(5000);
  const active = Object.values(tasks).filter(isActiveMayday).sort(sortTasks);

  useEffect(() => {
    const base = "RescueCodec";
    document.title = active.length ? `MAYDAY (${active.length}) · ${base}` : base;
  }, [active.length]);

  if (!active.length) return null;
  return (
    <div className="mayday" role="alert" aria-live="assertive">
      {active.map((task) => {
        const rec = messages.find((m) => m.seq === task.message_seq);
        const persons = rec?.message.mayday?.persons;
        const acc = [...task.acks].reverse().find((a) => a.status === "accepted");
        return (
          <div className="mayday-row" key={task.key}>
            <div className="mayday-mark">
              <Siren size={34} strokeWidth={2.2} aria-hidden="true" />
              <span className="mayday-word">{t("mayday")}</span>
            </div>
            <div className="mayday-body">
              <p className="mayday-text">{cardText(task.text[lang], task.type, lang, nodes)}</p>
              <p className="mayday-meta">
                <span>{t("maydayFrom")} <b>{nodeLabel(task.from, nodes)}</b></span>
                {persons ? <span className="num">{persons} {persons === 1 ? t("maydayPerson") : t("maydayPersons")}</span> : null}
                <span className="num">{relTime(task.created, now, lang)}</span>
                {task.pos && <span className="mono">{utmLabel(task.pos.lat, task.pos.lon)}</span>}
                {acc && <span className="mayday-acc">{t("acceptedBy")} <b>{acc.team || nodeLabel(acc.by, nodes)}</b>{acc.eta ? ` · ${t("etaShort")} ${acc.eta} ${t("min")}` : ""}</span>}
              </p>
            </div>
            <div className="mayday-actions">
              {task.pos && (
                <button className="btn btn-banner-ghost" onClick={() => focusOn(task.message_seq, task.key)}>
                  <MapPin size={16} aria-hidden="true" /> {t("showOnMap")}
                </button>
              )}
              <AckControl task={task} tone="banner" />
            </div>
          </div>
        );
      })}
    </div>
  );
}
