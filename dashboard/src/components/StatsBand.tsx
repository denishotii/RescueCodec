import { useMemo } from "react";
import { useData, useT } from "../state/hooks";
import { airtimeSaving } from "../lib/airtime";
import { fmtNum, textBytes } from "../lib/format";
import { SourceTag } from "./bits";

function secs(s: number): string {
  if (s >= 60) return `${Math.floor(s / 60)} min ${Math.round(s % 60)} s`;
  return `${s.toFixed(1)} s`;
}

export function StatsBand() {
  const { stats, messages } = useData();
  const t = useT();

  const saving = useMemo(
    () => airtimeSaving(messages.filter((m) => m.status !== "dry_run").map((m) => ({ size: m.codec.size, textBytes: textBytes(m) }))),
    [messages],
  );
  const out = useMemo(() => {
    let delivered = 0, sent = 0, queued = 0, failed = 0;
    for (const m of messages) {
      if (m.direction !== "out") continue;
      if (m.status === "delivered") delivered++; else if (m.status === "sent") sent++; else if (m.status === "queued") queued++; else if (m.status === "failed") failed++;
    }
    return { delivered, sent, queued, failed, total: delivered + sent + queued + failed };
  }, [messages]);

  const onAir = stats ? (stats.bytes_in.value ?? 0) + (stats.bytes_out.value ?? 0) : 0;
  const asText = stats?.text_equivalent_bytes.value ?? 0;
  const max = Math.max(onAir, asText, 1);
  const hourPct = stats?.airtime_last_hour_pct.value ?? 0;
  const limit = stats?.airtime_limit_pct.value ?? 10;

  return (
    <section className="stats" aria-label={t("stats")}>
      <div className="stat stat-bytes">
        <h3>{t("bytesOnAir")} <SourceTag source={stats?.bytes_in.source ?? "measured"} /></h3>
        <div className="bars" role="img" aria-label={`${onAir} B, ${asText} B`}>
          <div className="bar-row"><span className="bar-label">RescueCodec</span><span className="bar"><i className="bar-fill codec" style={{ width: `${(onAir / max) * 100}%` }} /></span><b className="num">{fmtNum(onAir)} B</b></div>
          <div className="bar-row"><span className="bar-label">{t("asText")}</span><span className="bar"><i className="bar-fill text" style={{ width: `${(asText / max) * 100}%` }} /></span><b className="num">{fmtNum(asText)} B</b></div>
        </div>
        {stats?.size_ratio.value ? <p className="stat-note num"><b>{fmtNum(stats.size_ratio.value, 1)}×</b> {t("smaller")}</p> : null}
      </div>

      <div className="stat">
        <h3>{t("airtimeSaved")} <SourceTag source="estimated" /></h3>
        <p className="stat-big num">{secs(saving.savedS)}</p>
        <p className="stat-note num">{secs(saving.codecS)} vs {secs(saving.textS)} · {saving.packets} {t("reports")}</p>
      </div>

      <div className="stat">
        <h3>{t("deliveredOfSent")} <SourceTag source="measured" /></h3>
        <p className="stat-big num">{out.delivered}<span className="stat-of"> {t("of")} {out.total}</span></p>
        <p className="stat-note num">{out.sent} {t("sentShort")} · {out.queued} {t("waiting")} · {out.failed} {t("failedShort")}</p>
      </div>

      <div className="stat">
        <h3>{t("signal")} <SourceTag source={stats?.last_rssi.source ?? "measured"} /></h3>
        <p className="stat-big num">{stats?.last_rssi.value != null ? `${stats.last_rssi.value}` : "–"}<span className="stat-of"> dBm</span></p>
        <p className="stat-note num">SNR {stats?.last_snr.value != null ? `${fmtNum(stats.last_snr.value, 1)} dB` : "–"}</p>
      </div>

      <div className="stat">
        <h3>{t("airtimeUse")} <SourceTag source={stats?.airtime_last_hour_pct.source ?? "estimated"} /></h3>
        <p className="stat-big num">{fmtNum(hourPct, 2)}<span className="stat-of"> % {t("of")} {limit} %</span></p>
        <span className="bar gauge" role="img" aria-label={`${hourPct} % / ${limit} %`}><i className="bar-fill codec" style={{ width: `${Math.min(100, (hourPct / limit) * 100)}%` }} /></span>
      </div>
    </section>
  );
}
