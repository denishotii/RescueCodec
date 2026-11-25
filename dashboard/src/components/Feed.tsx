import { useEffect, useMemo, useState } from "react";
import { Check, CheckCheck, CircleAlert, Clock, MapPin } from "lucide-react";
import type { MessageRecord } from "../api/types";
import { useChannels, useData, useT } from "../state/hooks";
import { focusOn, setUi, useUi, type Filter } from "../state/ui";
import { cardText, clock, formTag, isNoRelay, nodeLabel, typeName, vocabTitle } from "../lib/format";
import { utmLabel } from "../lib/geo";
import { matchesChannel } from "../lib/channels";
import { ChannelChip, Panel, PriorityChip, TYPE_ICON } from "./bits";
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

export const STATUS_KEY: Record<MessageRecord["status"], Key> = {
  received: "statusReceived", queued: "statusQueued", sent: "statusSent", delivered: "statusDelivered", failed: "statusFailed", dry_run: "statusQueued",
};

const STATUS_ICON: Record<MessageRecord["status"] | "norelay", typeof Check | null> = {
  received: null, queued: Clock, dry_run: Clock, sent: Check, delivered: CheckCheck, failed: CircleAlert, norelay: Check,
};

/** One report: a single meta line (who, when, and only what matters) and the words. */
export function ReportCard({ rec, fresh, chips = false }: { rec: MessageRecord; fresh: boolean; chips?: boolean }) {
  const { nodes, health } = useData();
  const { lang } = useUi();
  const t = useT();
  const pos = rec.message.pos ?? rec.message.track?.points.at(-1) ?? null;
  const compact = rec.type === "ack" || rec.type === "track" || rec.type === "sector";
  const when = rec.event_time ?? rec.time;
  const noRelay = isNoRelay(rec);
  const Icon = TYPE_ICON[rec.type];
  const body = cardText(rec.text[lang], rec.type, lang, nodes);
  const tag = formTag(rec, lang);
  const radio = [
    rec.radio?.rssi != null ? `${rec.radio.rssi} dBm` : "", rec.radio?.snr != null ? `SNR ${rec.radio.snr.toFixed(1)}` : "",
    rec.radio?.hops != null ? `${rec.radio.hops} ${t("hops")}` : "", rec.codec.version === 0 ? t("viaMeshtasticNote") : "",
  ].filter(Boolean).join(" · ");
  const state = noRelay ? "norelay" : rec.status;
  const StatusIcon = rec.direction === "out" ? STATUS_ICON[state] : null;
  const stateText = t(noRelay ? "statusNoRelay" : STATUS_KEY[rec.status]);

  return (
    <li className={`report report-${rec.type} dir-${rec.direction} ${fresh ? "fresh" : ""} ${compact ? "compact" : ""}`} data-seq={rec.seq}>
      <div className="report-meta">
        {rec.type !== "text" && (
          <span className={`report-icon type-${rec.type}`} title={typeName(lang, rec.type)}><Icon size={14} strokeWidth={2.2} aria-label={typeName(lang, rec.type)} /></span>
        )}
        <span className="from">{nodeLabel(rec.from, nodes)}</span>
        <span className="report-time num" title={[rec.time, radio].filter(Boolean).join(" · ")}>{clock(when, false)}</span>
        {(chips || rec.to) && <ChannelChip channel={rec.channel} to={rec.to} from={rec.from} direction={rec.direction} nodes={nodes} />}
        <PriorityChip priority={rec.priority} />
        <span className="report-end">
          {tag && <span className="report-tag num" title={vocabTitle(rec, health, lang)}>{tag}</span>}
          {StatusIcon && (
            <span className={`out-state status-${state}`} role="img" aria-label={stateText}
              title={rec.status === "failed" && rec.error && !noRelay ? `${stateText}: ${rec.error}` : stateText}>
              <StatusIcon size={15} strokeWidth={2.4} aria-hidden="true" />
            </span>
          )}
          {pos && (
            <button className="map-pin" onClick={() => focusOn(rec.seq, rec.task_key)} title={`${t("showOnMap")} · ${utmLabel(pos.lat, pos.lon)}`} aria-label={t("showOnMap")}>
              <MapPin size={14} aria-hidden="true" />
            </button>
          )}
        </span>
      </div>
      <p className="report-text">{body}</p>
    </li>
  );
}

export function Feed() {
  const { messages, fresh, loaded } = useData();
  const { filter } = useUi();
  const { pick, chipOn, options, filter: manyChannels } = useChannels();
  const t = useT();
  const [, tick] = useState(0);

  // forget "fresh" flags after the highlight ends
  useEffect(() => { const id = setInterval(() => tick((n) => n + 1), 2000); return () => clearInterval(id); }, []);
  const nowMs = Date.now();

  // the channel pick narrows the feed (and the actions) first, then the type filter
  const inChannel = useMemo(() => (pick === "all" ? messages : messages.filter((m) => matchesChannel(pick, m.channel?.name))), [messages, pick]);

  const shown = useMemo(() => {
    const out: MessageRecord[] = [];
    for (let i = inChannel.length - 1; i >= 0 && out.length < 150; i--) if (matches(filter, inChannel[i])) out.push(inChannel[i]);
    return out;
  }, [inChannel, filter]);

  // one small "Show" menu: a channel (when there is more than one) or a type
  const value = filter !== "all" ? `type:${filter}` : `ch:${pick}`;
  const choose = (v: string) => {
    if (v.startsWith("type:")) setUi({ filter: v.slice(5) as Filter });
    else setUi({ channel: v.slice(3), filter: "all" });
  };

  return (
    <Panel id="feed" title={pick === "all" ? t("feed") : `${t("feed")} · #${pick}`} count={inChannel.length} className="panel-feed"
      actions={
        <label className="show-pick">
          <span className="sr-only">{t("show")}:</span>
          <select value={value} onChange={(e) => choose(e.target.value)}>
            <option value="ch:all">{t("allChannels")}</option>
            {manyChannels && (
              <optgroup label={t("channel")}>
                {options.map((name) => <option key={name} value={`ch:${name}`}>#{name}</option>)}
              </optgroup>
            )}
            <optgroup label={t("typeGroup")}>
              {FILTERS.filter((f) => f.id !== "all").map((f) => <option key={f.id} value={`type:${f.id}`}>{t(f.label)}</option>)}
            </optgroup>
          </select>
        </label>
      }>
      <div className="panel-body scroll" id="main-feed" tabIndex={-1}>
        {!loaded ? (
          <ul className="report-list" aria-busy="true">{[0, 1, 2].map((i) => <li key={i} className="report skeleton" />)}</ul>
        ) : shown.length ? (
          <ul className="report-list">
            {shown.map((r) => <ReportCard key={r.seq} rec={r} chips={chipOn(r.channel?.name)} fresh={nowMs - (fresh[r.seq] ?? 0) < 6000} />)}
          </ul>
        ) : <p className="empty">{inChannel.length || pick !== "all" ? t("noMatch") : t("noReports")}</p>}
      </div>
    </Panel>
  );
}
