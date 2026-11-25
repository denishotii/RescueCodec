import { useMemo, useState } from "react";
import { ChevronRight, MapPin } from "lucide-react";
import type { MeshNode, Task } from "../api/types";
import { useChannels, useData, useNow, useT } from "../state/hooks";
import { focusOn, useUi } from "../state/ui";
import { isActiveMayday, nodeLabel, relTime, sortTasks, typeName } from "../lib/format";
import { utmLabel } from "../lib/geo";
import { matchesChannel } from "../lib/channels";
import { ChannelChip, Panel, PriorityChip, StateChip, TypeTile } from "./bits";
import { AckControl } from "./AckControl";

function lastAck(task: Task, status: "accepted" | "done" | "declined") {
  for (let i = task.acks.length - 1; i >= 0; i--) if (task.acks[i].status === status) return task.acks[i];
  return null;
}

function who(a: { by: string; team?: string | null }, nodes: Record<string, MeshNode>) {
  return a.team || nodeLabel(a.by, nodes);
}

/** `chips`: show the channel (the parent knows whether more than one channel is in use). */
export function TaskCard({ task, chips = false }: { task: Task; chips?: boolean }) {
  const { nodes } = useData();
  const { lang } = useUi();
  const t = useT();
  const now = useNow();
  const acc = lastAck(task, "accepted");
  const done = lastAck(task, "done");
  const dec = task.state === "declined" ? lastAck(task, "declined") : null;
  const pos = task.pos;
  const isMayday = task.type === "mayday";
  // the words already say what it is ("Hazard: ...", "Person found: ..."), so the top line says who and when
  const where = pos ? (
    <button className="map-pin task-pin" onClick={() => focusOn(task.message_seq, task.key)} title={`${t("showOnMap")} · ${utmLabel(pos.lat, pos.lon)}`} aria-label={t("showOnMap")}>
      <MapPin size={18} aria-hidden="true" />
    </button>
  ) : null;

  return (
    <li className={`task task-${task.state} ${isMayday ? "task-mayday" : ""} prio-card-${task.priority}`}>
      <div className="task-top">
        <span title={typeName(lang, task.type)}><TypeTile type={task.type} size={18} /></span>
        <div className="task-id">
          <span className="task-from">{nodeLabel(task.from, nodes)}</span>
          <PriorityChip priority={task.priority} />
          {chips && <ChannelChip channel={task.channel} />}
        </div>
        <span className="task-age num" title={task.created}>{relTime(task.created, now, lang)}</span>
      </div>
      <p className="task-text">{task.text[lang]}</p>
      {task.state === "accepted" && acc && (
        <p className="task-status">
          <StateChip state="accepted">{t("acceptedBy")} {who(acc, nodes)}</StateChip>
          {acc.eta ? <span className="eta-badge num">{t("etaShort")} {acc.eta} {t("min")}</span> : null}
        </p>
      )}
      {task.state === "done" && (
        <p className="task-status"><StateChip state="done">{t("doneBy")} {done ? who(done, nodes) : "–"}</StateChip></p>
      )}
      {dec && <p className="task-status"><StateChip state="declined">{t("declinedBy")} {who(dec, nodes)}</StateChip></p>}
      {task.state !== "done" && (
        <div className="task-foot">
          <AckControl task={task} />
          {where}
        </div>
      )}
    </li>
  );
}

export function ActionsBoard() {
  const { tasks } = useData();
  const { pick, chips } = useChannels();
  const t = useT();
  // Open is what needs a decision, so it is always shown; Accepted and Done fold into one line each
  const [shown, setShown] = useState({ accepted: false, done: false });

  const groups = useMemo(() => {
    // an active Mayday lives in the red banner, so it is not listed twice
    const all = Object.values(tasks).filter((x) => !isActiveMayday(x) && matchesChannel(pick, x.channel?.name));
    return {
      open: all.filter((x) => x.state === "open" || x.state === "declined").sort(sortTasks),
      accepted: all.filter((x) => x.state === "accepted").sort(sortTasks),
      done: all.filter((x) => x.state === "done").sort((a, b) => b.updated.localeCompare(a.updated)),
    };
  }, [tasks, pick]);

  const jump = (id: "open" | "accepted" | "done") => {
    if (id !== "open") setShown((s) => ({ ...s, [id]: true }));
    requestAnimationFrame(() => document.getElementById(`lane-${id}`)?.scrollIntoView({ block: "start", behavior: "smooth" }));
  };
  const folded = [
    { id: "accepted", label: t("accepted"), items: groups.accepted },
    { id: "done", label: t("done"), items: groups.done },
  ] as const;

  return (
    <Panel
      id="actions" title={pick === "all" ? t("actions") : `${t("actions")} · #${pick}`} className="panel-actions"
      actions={
        <div className="tally" aria-label={`${groups.open.length} ${t("open")}, ${groups.accepted.length} ${t("accepted")}, ${groups.done.length} ${t("done")}`}>
          <button className="tally-item state-open" onClick={() => jump("open")}><b className="num">{groups.open.length}</b> {t("open")}</button>
          <button className="tally-item state-accepted" onClick={() => jump("accepted")}><b className="num">{groups.accepted.length}</b> {t("accepted")}</button>
          <button className="tally-item state-done" onClick={() => jump("done")}><b className="num">{groups.done.length}</b> {t("done")}</button>
        </div>
      }
    >
      <div className="lanes">
        <section id="lane-open" className={`lane lane-open ${groups.open.length ? "" : "lane-empty"}`} aria-label={t("open")}>
          <h3 className="lane-h state-open"><span>{t("open")}</span><span className="num">{groups.open.length}</span></h3>
          {groups.open.length
            ? <ul className="task-list lane-body">{groups.open.map((x) => <TaskCard key={x.key} task={x} chips={chips} />)}</ul>
            : <p className="empty">{t("nothingOpen")}</p>}
        </section>
        {folded.map((l) => {
          const open = shown[l.id] && l.items.length > 0;
          return (
            <section key={l.id} id={`lane-${l.id}`} className={`lane lane-${l.id} lane-fold`} aria-label={l.label}>
              <button className={`lane-h lane-toggle state-${l.id}`} aria-expanded={open} disabled={!l.items.length}
                onClick={() => setShown((s) => ({ ...s, [l.id]: !s[l.id] }))}>
                <span>{l.label} <span className="num">{l.items.length}</span></span>
                <ChevronRight className="chev" size={18} aria-hidden="true" />
              </button>
              {open && <ul className="task-list lane-body">{l.items.map((x) => <TaskCard key={x.key} task={x} chips={chips} />)}</ul>}
            </section>
          );
        })}
      </div>
    </Panel>
  );
}
