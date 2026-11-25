import { useEffect, useMemo, useRef, useState } from "react";
import { SendHorizontal } from "lucide-react";
import { ApiError, api } from "../api/client";
import type { ExtractResult } from "../api/types";
import { useData, useStore, useT } from "../state/hooks";
import { useUi } from "../state/ui";
import {
  QUICK, buildSend, counterView, defaultTarget, directOptions, extractBody, inputCap, nodeName, parseTargetKey,
  plainOnlyReason, previewView, sendChannels, sendErrorText, targetKey, textLimit, type QuickId,
} from "../lib/compose";
import { isNoRelay } from "../lib/format";
import type { Key } from "../lib/i18n";
import { clampBytes } from "./AckControl";
import { ChannelChip, Panel } from "./bits";
import { STATUS_KEY } from "./Feed";

// the three a command post uses most; reports (requests, hazards) are typed in plain words
const QUICK_BUTTONS: { id: QuickId; label: Key }[] = [
  { id: "status", label: "quickStatus" }, { id: "recall", label: "quickRecall" }, { id: "meet", label: "quickMeet" },
];
const DEBOUNCE_MS = 250;

type Outcome = { ok: true; seq: number } | { ok: false; text: string };

/** The command post's composer: pick a channel or a person, type, see what goes on air, send. */
export function SendPanel() {
  const { health, nodes, conn, messages } = useData();
  const { lang } = useUi();
  const t = useT();
  const store = useStore();
  const inputRef = useRef<HTMLTextAreaElement>(null);

  const channels = useMemo(() => sendChannels(health), [health]);
  const people = useMemo(() => directOptions(nodes), [nodes]);
  const [pick, setPick] = useState<string | null>(null);
  const picked = pick ? parseTargetKey(pick) : null;
  const target = picked ?? defaultTarget(health);

  const [text, setText] = useState("");
  const [plainTick, setPlainTick] = useState(false);
  // a preview is for one text on one target (the gateway answers for that chat: its limit, its form)
  const [preview, setPreview] = useState<{ words: string; key: string; result: ExtractResult } | null>(null);
  const [reading, setReading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [outcome, setOutcome] = useState<Outcome | null>(null);

  const words = text.trim();
  const key = target ? targetKey(target) : "";
  const plainReason = plainOnlyReason(target, health);
  const plain = plainTick || plainReason !== null;
  const sameTarget = preview && preview.key === key ? preview : null;
  const current = sameTarget && sameTarget.words === words ? sameTarget.result : null;

  // live preview: ask the gateway what this sentence sends to this target, after a short pause in typing
  useEffect(() => {
    if (!words || (preview?.words === words && preview.key === key)) { setReading(false); return; }
    setReading(true);
    const ctl = new AbortController();
    const id = setTimeout(() => {
      api.extract(extractBody(words, target), ctl.signal)
        .then((result) => { if (!ctl.signal.aborted) setPreview({ words, key, result }); })
        .catch(() => { /* no preview: the line shows the text size, a send still works */ })
        .finally(() => { if (!ctl.signal.aborted) setReading(false); });
    }, DEBOUNCE_MS);
    return () => { clearTimeout(id); ctl.abort(); };
  }, [words, key]); // eslint-disable-line react-hooks/exhaustive-deps

  // while the next preview loads, the last one for this target stays (no flicker)
  const view = previewView(words, current ?? (reading ? sameTarget?.result ?? null : null), plain, lang);
  // 400 B on a chat that packs, 180 B elsewhere; the gateway's preview has the last word
  const limit = textLimit(target, health, sameTarget?.result ?? null);
  const counter = counterView(text, limit, current);
  const radioDown = !!health && !health.radio.connected;
  const canSend = !!words && !!target && !busy && conn !== "offline" && !radioDown;

  async function send() {
    if (!canSend || !target) return;
    setBusy(true); setOutcome(null);
    try {
      let result = current;
      if (!result && !plain) {
        try { result = await api.extract(extractBody(words, target)); setPreview({ words, key, result }); } catch { result = null; }
      }
      const call = buildSend(words, target, result, plain, health);
      const rec = call.path === "/v2/messages" ? await api.sendMessage(call.body) : await api.sendText(call.body);
      store.applyRecord(rec);
      setOutcome({ ok: true, seq: rec.seq });
      setText("");
      inputRef.current?.focus();
    } catch (e) {
      setOutcome({ ok: false, text: e instanceof ApiError ? sendErrorText(e, lang) : t("sendFailed") });
    } finally { setBusy(false); }
  }

  function quick(id: QuickId) {
    const line = QUICK[id][lang];
    setText(line);
    setOutcome(null);
    requestAnimationFrame(() => {
      const el = inputRef.current;
      if (el) { el.focus(); el.setSelectionRange(line.length, line.length); }
    });
  }

  const sent = outcome?.ok ? messages.findLast((m) => m.seq === outcome.seq) : undefined;

  return (
    <Panel id="send" title={t("composeTitle")} className="panel-send"
      actions={
        <label className="send-to">
          <span>{t("to")}</span>
          <select value={target ? targetKey(target) : ""} disabled={!target} onChange={(e) => { setPick(e.target.value); setOutcome(null); }}>
            {!target && <option value="">{t("noTarget")}</option>}
            <optgroup label={t("channelsGroup")}>
              {channels.map((c) => <option key={c.name} value={`ch:${c.name}`}>#{c.name}</option>)}
              {target?.kind === "channel" && !channels.some((c) => c.name === target.name) && <option value={`ch:${target.name}`}>#{target.name}</option>}
            </optgroup>
            {people.length > 0 && (
              <optgroup label={t("peopleGroup")}>
                {people.map((n) => <option key={n.node} value={`dm:${n.node}`}>{nodeName(n)}{n.short_name && n.long_name ? ` (${n.short_name})` : ""}</option>)}
              </optgroup>
            )}
          </select>
        </label>
      }>
      <form className="send-body" onSubmit={(e) => { e.preventDefault(); void send(); }}>
        <div className="quick-row" role="group" aria-label={t("quickGroup")}>
          {QUICK_BUTTONS.map((q) => (
            <button key={q.id} type="button" className="quick" onClick={() => quick(q.id)}>
              {t(q.label)}
            </button>
          ))}
        </div>
        <div className="send-row">
          <label className="send-input">
            <span className="sr-only">{t("composeLabel")}</span>
            <textarea ref={inputRef} rows={2} value={text} placeholder={t("composePlaceholder")} aria-describedby="send-preview"
              onChange={(e) => { setText(clampBytes(e.target.value, inputCap(limit, text))); if (outcome && !outcome.ok) setOutcome(null); }}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); void send(); }
              }} />
          </label>
          <button type="submit" className="btn btn-accept send-btn" disabled={!canSend} title={t("sendShortcut")}>
            <SendHorizontal size={18} aria-hidden="true" />{t("send")}
          </button>
        </div>
        <div id="send-preview" className={`send-preview ${reading ? "is-reading" : ""}`} aria-live="polite">
          {words ? (
            <>
              <p className="send-as">
                <span className="muted">{t("sendsAs")}:</span>{" "}
                <b>{view.head || t("textKind")}</b>
                <span className="num"> · <b>{view.bytes} B</b></span>
                {view.textBytes != null && <span className="muted num"> ({t("asTextShort")} {view.textBytes} B)</span>}
                {reading && <span className="muted"> · {t("reading")}</span>}
              </p>
              {view.chips.length > 0 && (
                <ul className="send-chips">{view.chips.map((c, i) => <li key={i} className="chip">{c}</li>)}</ul>
              )}
              {counter.cut && <p className="send-warn">{t("willBeCut")}</p>}
            </>
          ) : null}
        </div>
        <div className="send-foot">
          <label className={`plain-toggle ${plainReason ? "locked" : ""}`}>
            <input type="checkbox" checked={plain} disabled={plainReason !== null} onChange={(e) => setPlainTick(e.target.checked)} />
            <span>{plainReason === "direct" ? t("plainDirect") : plainReason === "public" ? t("plainPublic") : t("plainText")}</span>
          </label>
          <span className={`send-count num ${counter.used > counter.limit ? "over" : counter.near ? "near" : ""}`}
            aria-label={`${counter.used} of ${counter.limit} bytes`}>{counter.used}/{counter.limit} B</span>
        </div>
        {outcome && (
          <p className={`send-result ${outcome.ok ? "ok" : "err"}`} role={outcome.ok ? "status" : "alert"}>
            {outcome.ok ? (
              sent ? (
                <>
                  <span className={`chip out-chip status-${isNoRelay(sent) ? "norelay" : sent.status}`}>{t(isNoRelay(sent) ? "statusNoRelay" : STATUS_KEY[sent.status])}</span>
                  <ChannelChip channel={sent.channel} to={sent.to} from={sent.from} direction="out" nodes={nodes} />
                  <span className="send-result-text">{sent.text[lang]}</span>
                </>
              ) : <span className="chip out-chip">{t("statusQueued")}</span>
            ) : outcome.text}
          </p>
        )}
      </form>
    </Panel>
  );
}
