import { useMemo } from "react";
import { MapPin } from "lucide-react";
import type { MeshNode, Task } from "../api/types";
import { useData, useNow, useT } from "../state/hooks";
import { focusOn, useUi } from "../state/ui";
import { isActiveMayday, nodeLabel, relTime, sortTasks, typeName } from "../lib/format";
import { utmLabel } from "../lib/geo";
import { Panel, PriorityChip, StateChip, TypeTile } from "./bits";
import { AckControl } from "./AckControl";

function lastAck(task: Task, status: "accepted" | "done" | "declined") {
  for (let i = task.acks.length - 1; i >= 0; i--) if (task.acks[i].status === status) return task.acks[i];
  return null;
}

function who(a: { by: string; team?: string | null }, nodes: Record<string, MeshNode>) {
  return a.team || nodeLabel(a.by, nodes);
}

export function TaskCard({ task }: { task: Task }) {
  const { nodes } = useData();
  const { lang } = useUi();
  const t = useT();
  const now = useNow();
  const acc = lastAck(task, "accepted");
  const done = lastAck(task, "done");
  const dec = task.state === "declined" ? lastAck(task, "declined") : null;
  const pos = task.pos;
  const isMayday = task.type === "mayday";

  return (
    <li className={`task task-${task.state} ${isMayday ? "task-mayday" : ""} prio-card-${task.priority}`}>
      <div className="task-top">
        <TypeTile type={task.type} />
        <div className="task-id">
          <span className="task-type">{typeName(lang, task.type)}</span>
          <PriorityChip priority={task.priority} />
        </div>
        <span className="task-age num" title={task.created}>{relTime(task.created, now, lang)}</span>
      </div>
      <p className="task-text">{task.text[lang]}</p>
      <div className="task-meta">
        <span className="from">{nodeLabel(task.from, nodes)}</span>
        {pos ? (
          <button className="grid-ref" onClick={() => focusOn(task.message_seq, task.key)} title={t("showOnMap")}>
            <MapPin size={13} aria-hidden="true" />
            <span className="mono">{utmLabel(pos.lat, pos.lon)}</span>
          </button>
        ) : (
          <span className="muted">{t("noPosition")}</span>
        )}
      </div>
      {task.state === "accepted" && acc && (
        <div className="task-foot">
          <p className="task-status">
            <StateChip state="accepted">{t("acceptedBy")} {who(acc, nodes)}</StateChip>
            {acc.eta ? <span className="eta-badge num">{t("etaShort")} {acc.eta} {t("min")}</span> : null}
          </p>
          <AckControl task={task} />
        </div>
      )}
      {task.state === "done" && (
        <p className="task-status"><StateChip state="done">{t("doneBy")} {done ? who(done, nodes) : "–"}</StateChip></p>
      )}
      {dec && <p className="task-status"><StateChip state="declined">{t("declinedBy")} {who(dec, nodes)}</StateChip></p>}
      {task.state !== "accepted" && <AckControl task={task} />}
    </li>
  );
}

export function ActionsBoard() {
  const { tasks } = useData();
  const t = useT();

  const groups = useMemo(() => {
    // an active Mayday lives in the red banner, so it is not listed twice
    const all = Object.values(tasks).filter((x) => !isActiveMayday(x));
    return {
      open: all.filter((x) => x.state === "open" || x.state === "declined").sort(sortTasks),
      accepted: all.filter((x) => x.state === "accepted").sort(sortTasks),
      done: all.filter((x) => x.state === "done").sort((a, b) => b.updated.localeCompare(a.updated)),
    };
  }, [tasks]);

  const jump = (id: string) => document.getElementById(`lane-${id}`)?.scrollIntoView({ block: "start", behavior: "smooth" });
  const lanes = [
    { id: "open", label: t("open"), items: groups.open, empty: t("nothingOpen") },
    { id: "accepted", label: t("accepted"), items: groups.accepted, empty: t("nothingAccepted") },
    { id: "done", label: t("done"), items: groups.done, empty: t("nothingDone") },
  ] as const;

  return (
    <Panel
      id="actions" title={t("actions")} className="panel-actions"
      actions={
        <div className="tally" aria-label={`${groups.open.length} ${t("open")}, ${groups.accepted.length} ${t("accepted")}, ${groups.done.length} ${t("done")}`}>
          <button className="tally-item state-open" onClick={() => jump("open")}><b className="num">{groups.open.length}</b> {t("open")}</button>
          <button className="tally-item state-accepted" onClick={() => jump("accepted")}><b className="num">{groups.accepted.length}</b> {t("accepted")}</button>
          <button className="tally-item state-done" onClick={() => jump("done")}><b className="num">{groups.done.length}</b> {t("done")}</button>
        </div>
      }
    >
      <div className="lanes">
        {lanes.map((l) => (
          <section key={l.id} id={`lane-${l.id}`} className={`lane lane-${l.id} ${l.items.length ? "" : "lane-empty"}`} aria-label={l.label}>
            <h3 className={`lane-h state-${l.id}`}><span>{l.label}</span><span className="num">{l.items.length}</span></h3>
            {l.items.length
              ? <ul className="task-list lane-body">{l.items.map((x) => <TaskCard key={x.key} task={x} />)}</ul>
              : <p className="empty">{l.empty}</p>}
          </section>
        ))}
      </div>
    </Panel>
  );
}
