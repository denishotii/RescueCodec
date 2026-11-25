import { useEffect, useRef, useState } from "react";
import { ApiError, api } from "../api/client";
import type { Task } from "../api/types";
import { useData, useStore, useT } from "../state/hooks";
import { setUi, useUi } from "../state/ui";

const ETAS = [5, 10, 15, 30, 45];

/** The API takes at most 12 bytes of UTF-8 for a team label. */
export function clampBytes(s: string, max: number): string {
  let out = "";
  for (const ch of s) { if (new TextEncoder().encode(out + ch).length > max) break; out += ch; }
  return out;
}

/** Accept / Done / Decline for one task. Used on the Actions board and in the Mayday banner. */
export function AckControl({ task, tone = "board" }: { task: Task; tone?: "board" | "banner" }) {
  const t = useT();
  const store = useStore();
  const { conn } = useData();
  const ui = useUi();
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const confirmRef = useRef<HTMLButtonElement>(null);
  const offline = conn === "offline";

  useEffect(() => { if (editing) confirmRef.current?.focus(); }, [editing]);

  async function send(status: "accepted" | "declined" | "done", extra?: { eta?: number; team?: string }) {
    setBusy(true); setErr(null);
    try {
      const res = await api.ack(task.key, { status, ...extra });
      store.applyLocal(res.task, res.record);
      setEditing(false);
    } catch (e) {
      if (e instanceof ApiError) {
        if (e.code === "radio_unavailable") setErr(t("radioUnavailable"));
        else if (e.code === "airtime_limit") setErr(`${t("airtimeLimit")} ${Math.ceil((e.retryAfter ?? 60) / 60)} ${t("min")}`);
        else setErr(`${t("sendFailed")}: ${e.message}`);
      } else setErr(t("sendFailed"));
    } finally { setBusy(false); }
  }

  const team = clampBytes(ui.team.trim(), 12);
  const confirm = () => {
    setUi({ team: team });
    void send("accepted", { eta: ui.eta, ...(team ? { team } : {}) });
  };

  const state = task.state;
  const showAccept = state === "open" || state === "declined";
  const showDone = state === "accepted";
  const showDecline = state === "open" || state === "accepted";

  if (state === "done") return null;

  return (
    <div className={`ack ack-${tone}`}>
      {!editing && (
        <div className="ack-row">
          {showAccept && (
            <button className="btn btn-accept" disabled={busy || offline} onClick={() => setEditing(true)}>{t("accept")}</button>
          )}
          {showDone && (
            <button className="btn btn-done" disabled={busy || offline} onClick={() => void send("done")}>{t("markDone")}</button>
          )}
          {showDecline && (
            <button className="btn btn-quiet" disabled={busy || offline} onClick={() => void send("declined")}>{t("decline")}</button>
          )}
        </div>
      )}
      {editing && (
        <form className="ack-form" onSubmit={(e) => { e.preventDefault(); confirm(); }} onKeyDown={(e) => { if (e.key === "Escape") setEditing(false); }}>
          <label className="field">
            <span>{t("team")}</span>
            <input value={ui.team} maxLength={12} onChange={(e) => setUi({ team: clampBytes(e.target.value, 12) })} autoComplete="off" />
          </label>
          <fieldset className="eta">
            <legend>{t("eta")}</legend>
            <div className="eta-row">
              {ETAS.map((m) => (
                <label key={m} className={`eta-opt ${ui.eta === m ? "on" : ""}`}>
                  <input type="radio" name={`eta-${task.key}-${tone}`} checked={ui.eta === m} onChange={() => setUi({ eta: m })} />
                  <span className="num">{m}</span>
                </label>
              ))}
              <span className="eta-unit">{t("min")}</span>
            </div>
          </fieldset>
          <div className="ack-row">
            <button ref={confirmRef} type="submit" className="btn btn-accept" disabled={busy || offline}>{t("confirm")}</button>
            <button type="button" className="btn btn-quiet" onClick={() => setEditing(false)}>{t("cancel")}</button>
          </div>
        </form>
      )}
      {err && <p className="ack-err" role="alert">{err}</p>}
    </div>
  );
}
