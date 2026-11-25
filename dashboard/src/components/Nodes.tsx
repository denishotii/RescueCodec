import { useMemo, useState } from "react";
import { BatteryLow, BatteryMedium, BatteryFull, Radio } from "lucide-react";
import { useData, useNow, useT } from "../state/hooks";
import { focusOn, useUi } from "../state/ui";
import { relTime } from "../lib/format";
import { Panel } from "./bits";

function Battery({ pct }: { pct: number | null | undefined }) {
  if (pct == null) return <span className="muted">–</span>;
  const Icon = pct < 20 ? BatteryLow : pct < 60 ? BatteryMedium : BatteryFull;
  return <span className={`battery num ${pct < 20 ? "low" : ""}`}><Icon size={16} aria-hidden="true" />{pct}%</span>;
}

export function Nodes({ collapsible = false }: { collapsible?: boolean }) {
  const [collapsed, setCollapsed] = useState(true);
  const { nodes } = useData();
  const { lang } = useUi();
  const t = useT();
  const now = useNow();
  const list = useMemo(
    () => Object.values(nodes).sort((a, b) => (b.last_heard ?? "").localeCompare(a.last_heard ?? "")),
    [nodes],
  );
  return (
    <Panel id={collapsible ? "nodes-side" : "nodes"} title={t("nodes")} count={list.length} className={`panel-nodes ${collapsible && collapsed ? "collapsed" : ""}`}
      collapse={collapsible ? { collapsed, onToggle: () => setCollapsed((c) => !c) } : undefined}>
      {!(collapsible && collapsed) && <div className="panel-body scroll">
        {list.length ? (
          <ul className="node-list">
            {list.map((n) => {
              const age = n.last_heard ? (now - new Date(n.last_heard).getTime()) / 60000 : Infinity;
              return (
                <li key={n.node} className={`node ${age > 30 ? "stale" : ""}`}>
                  <span className="node-name">
                    <Radio size={16} aria-hidden="true" />
                    <span className="node-long">{n.long_name || n.short_name || n.node}</span>
                    {n.is_gateway && <span className="chip chip-gw">{t("gateway")}</span>}
                  </span>
                  <span className="node-sub">
                    <span className="mono">{n.short_name ?? ""} {n.node}</span>
                    <span>{relTime(n.last_heard, now, lang) || "–"}</span>
                  </span>
                  <span className="node-stats num">
                    <Battery pct={n.battery_pct} />
                    {n.rssi != null && <span>{n.rssi} dBm</span>}
                    {n.snr != null && <span>SNR {n.snr.toFixed(1)}</span>}
                    {n.hops_away != null && <span>{n.hops_away} {t("hops")}</span>}
                    {n.pos && (
                      <button className="link-btn" onClick={() => focusOn(-1, "node:" + n.node)}>{t("showOnMap")}</button>
                    )}
                  </span>
                </li>
              );
            })}
          </ul>
        ) : <p className="empty">{t("noNodes")}</p>}
      </div>}
    </Panel>
  );
}
