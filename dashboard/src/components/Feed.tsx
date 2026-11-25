import { useEffect, useMemo, useState } from "react";
import { MapPin } from "lucide-react";
import type { MessageRecord } from "../api/types";
import { useData, useNow, useT } from "../state/hooks";
import { focusOn, setUi, useUi, type Filter } from "../state/ui";
import { bytesLabel, clock, isNoRelay, nodeLabel, relTime, typeName } from "../lib/format";
import { utmLabel } from "../lib/geo";
import { Panel, PriorityChip, StateChip, TypeTile } from "./bits";
import type { Key } from "../lib/i18n";

const FILTERS: { id: Filter; label: Key }[] = [
  { id: "all", label: "all" }, { id: "found", label: "filterFound" }, { id: "request", label: "filterRequest" },
  { id: "hazard", label: "filterHazard" }, { id: "track", label: "filterTrack" }, { id: "other", label: "filterOther" },
];

function matches(f: Filter, r: MessageRecord): boolean {
  if (f === "all") return true;
  if (f === "other") return !["found", "request", "hazard", "track", "mayday"].includes(r.type);
  if (f === "found") return r.type === "found" || r.type === "mayday";
  return r.type === f;
}

const STATUS_KEY: Record<MessageRecord["status"], Key> = {
  received: "statusReceived", queued: "statusQueued", sent: "statusSent", delivered: "statusDelivered", failed: "statusFailed", dry_run: "statusQueued",
};

export function ReportCard({ rec, fresh }: { rec: MessageRecord; fresh: boolean }) {
  const { nodes, tasks } = useData();
  const { lang } = useUi();
  const t = useT();
  const now = useNow();
  const task = rec.task_key ? tasks[rec.task_key] : undefined;
  const pos = rec.message.pos ?? rec.message.track?.points.at(-1) ?? null;
  const compact = rec.type === "ack" || rec.type === "track" || rec.type === "sector";
  const when = rec.event_time ?? rec.time;
  const noRelay = isNoRelay(rec);

  return (
    <li className={`report report-${rec.type} dir-${rec.direction} ${fresh ? "fresh" : ""} ${compact ? "compact" : ""}`} data-seq={rec.seq}>
      <div className="report-top">
        <TypeTile type={rec.type} size={20} />
        <span className="report-type">{typeName(lang, rec.type)}</span>
        <PriorityChip priority={rec.priority} />
        {rec.direction === "out" && (
          <span className={`chip out-chip status-${noRelay ? "norelay" : rec.status}`}>{t("reportOut")} · {t(noRelay ? "statusNoRelay" : STATUS_KEY[rec.status])}</span>
        )}
        <span className="report-time num" title={rec.time}>{clock(when)}</span>
      </div>
      <p className="report-text">{rec.text[lang]}</p>
      <div className="report-meta">
        <span className="from">{rec.direction === "out" ? t("reportOut") : t("from")} {nodeLabel(rec.from, nodes)}</span>
        {pos && (
          <button className="grid-ref" onClick={() => focusOn(rec.seq, rec.task_key)} title={t("showOnMap")}>
            <MapPin size={13} aria-hidden="true" /><span className="mono">{utmLabel(pos.lat, pos.lon)}</span>
          </button>
        )}
        <span className="muted num">{relTime(rec.time, now, lang)}</span>
      </div>
      <div className="report-radio num">
        <span title="Payload size">{bytesLabel(rec.codec.size)}</span>
        {rec.radio?.rssi != null && <span title="RSSI">{rec.radio.rssi} dBm</span>}
        {rec.radio?.snr != null && <span title="SNR">SNR {rec.radio.snr.toFixed(1)}</span>}
        {rec.radio?.hops != null && <span title={t("hops")}>{rec.radio.hops} {t("hops")}</span>}
        {rec.codec.version === 1 && <span>v1</span>}
        {task && task.state !== "open" && (
          <StateChip state={task.state}>{t(task.state)}</StateChip>
        )}
        {rec.status === "failed" && rec.error && !noRelay && <span className="chip status-failed">{rec.error}</span>}
      </div>
    </li>
  );
}

export function Feed() {
  const { messages, fresh, loaded } = useData();
  const { filter } = useUi();
  const t = useT();
  const [, tick] = useState(0);

  // forget "fresh" flags after the highlight ends
  useEffect(() => { const id = setInterval(() => tick((n) => n + 1), 2000); return () => clearInterval(id); }, []);
  const nowMs = Date.now();

  const shown = useMemo(() => {
    const out: MessageRecord[] = [];
    for (let i = messages.length - 1; i >= 0 && out.length < 150; i--) if (matches(filter, messages[i])) out.push(messages[i]);
    return out;
  }, [messages, filter]);

  const counts = useMemo(() => {
    const c: Record<Filter, number> = { all: messages.length, found: 0, request: 0, hazard: 0, track: 0, other: 0 };
    for (const m of messages) for (const f of FILTERS) if (f.id !== "all" && matches(f.id, m)) c[f.id]++;
    return c;
  }, [messages]);

  return (
    <Panel id="feed" title={t("feed")} count={messages.length} className="panel-feed">
      <div className="filters" role="group" aria-label="Filter">
        {FILTERS.map((f) => (
          <button key={f.id} className={`filter ${filter === f.id ? "on" : ""}`} aria-pressed={filter === f.id} onClick={() => setUi({ filter: f.id })}>
            {t(f.label)} <span className="num">{counts[f.id]}</span>
          </button>
        ))}
      </div>
      <div className="panel-body scroll" id="main-feed" tabIndex={-1}>
        {!loaded ? (
          <ul className="report-list" aria-busy="true">{[0, 1, 2].map((i) => <li key={i} className="report skeleton" />)}</ul>
        ) : shown.length ? (
          <ul className="report-list">
            {shown.map((r) => <ReportCard key={r.seq} rec={r} fresh={nowMs - (fresh[r.seq] ?? 0) < 6000} />)}
          </ul>
        ) : <p className="empty">{messages.length ? t("noMatch") : t("noReports")}</p>}
      </div>
    </Panel>
  );
}
