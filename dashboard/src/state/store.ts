import { api, ApiError, wsUrl } from "../api/client";
import type {
  Health, MeshNode, MessageRecord, MessageStatusEvent, Stats, StreamEvent, Task,
} from "../api/types";

export type Conn = "connecting" | "live" | "reconnecting" | "offline";

export interface DataState {
  conn: Conn;
  /** Wall-clock ms of the last frame or successful fetch. */
  lastContact: number | null;
  loaded: boolean;
  health: Health | null;
  /** Records, ascending seq. */
  messages: MessageRecord[];
  tasks: Record<string, Task>;
  nodes: Record<string, MeshNode>;
  stats: Stats | null;
  /** Seqs that arrived live after the first load (used to flash new cards). */
  fresh: Record<number, number>;
}

export const initialData = (): DataState => ({
  conn: "connecting", lastContact: null, loaded: false, health: null,
  messages: [], tasks: {}, nodes: {}, stats: null, fresh: {},
});

// ---- pure reducers (unit tested) -------------------------------------------------

export function upsertMessage(list: MessageRecord[], rec: MessageRecord): MessageRecord[] {
  const last = list[list.length - 1];
  if (!last || rec.seq > last.seq) return [...list, rec];
  const i = list.findIndex((m) => m.seq === rec.seq);
  if (i >= 0) { const next = list.slice(); next[i] = rec; return next; }
  const next = [...list, rec];
  next.sort((a, b) => a.seq - b.seq);
  return next;
}

export function applyStatus(list: MessageRecord[], ev: MessageStatusEvent): MessageRecord[] {
  const i = list.findIndex((m) => m.seq === ev.seq);
  if (i < 0) return list;
  const next = list.slice();
  next[i] = { ...list[i], status: ev.status, error: ev.error ?? null };
  return next;
}

export function mergeMessages(list: MessageRecord[], add: MessageRecord[]): MessageRecord[] {
  let out = list;
  for (const m of add) out = upsertMessage(out, m);
  return out;
}

export const lastSeq = (list: MessageRecord[]) => (list.length ? list[list.length - 1].seq : 0);

// ---- store -----------------------------------------------------------------------

type Listener = () => void;

export class DataStore {
  private state: DataState = initialData();
  private listeners = new Set<Listener>();
  private ws: WebSocket | null = null;
  private retry = 0;
  private timers: ReturnType<typeof setTimeout>[] = [];
  private watchdog: ReturnType<typeof setInterval> | null = null;
  private stopped = false;
  private loadedOnce = false;

  subscribe = (l: Listener) => { this.listeners.add(l); return () => { this.listeners.delete(l); }; };
  getSnapshot = () => this.state;

  private set(patch: Partial<DataState>) {
    this.state = { ...this.state, ...patch };
    this.listeners.forEach((l) => l());
  }
  private touch() { this.state = { ...this.state, lastContact: Date.now() }; }

  start() {
    this.stopped = false;
    void this.connect();
    this.watchdog = setInterval(() => {
      // stats arrive every 10 s, so 35 s of silence means the link is dead
      const s = this.state;
      if (s.conn === "live" && s.lastContact && Date.now() - s.lastContact > 35_000) this.ws?.close();
      else if (s.conn === "live") this.ws?.send(JSON.stringify({ op: "ping" }));
    }, 15_000);
  }

  stop() {
    this.stopped = true;
    this.timers.forEach(clearTimeout);
    if (this.watchdog) clearInterval(this.watchdog);
    this.ws?.close();
  }

  /** Initial load or catch-up after a reconnect. */
  private async loadAll() {
    const since = lastSeq(this.state.messages);
    const [health, tasks, nodes, stats] = await Promise.all([api.health(), api.tasks(), api.nodes(), api.stats()]);
    let cursor = since;
    let msgs = this.state.messages;
    for (let guard = 0; guard < 20; guard++) {
      const page = await api.messages(cursor, 500);
      if (!page.items.length) break;
      msgs = mergeMessages(msgs, page.items);
      cursor = page.items[page.items.length - 1].seq;
      if (page.items.length < 500) break;
    }
    this.touch();
    this.set({
      health, stats, messages: msgs, loaded: true,
      tasks: Object.fromEntries(tasks.items.map((t) => [t.key, t])),
      nodes: Object.fromEntries(nodes.items.map((n) => [n.node, n])),
    });
    this.loadedOnce = true;
  }

  private async connect() {
    if (this.stopped) return;
    this.set({ conn: this.loadedOnce ? "reconnecting" : "connecting" });
    try {
      await this.loadAll();
    } catch (e) {
      this.scheduleRetry(e instanceof ApiError && e.status === 0 ? "offline" : "reconnecting");
      return;
    }
    this.openSocket();
  }

  private openSocket() {
    if (this.stopped) return;
    let ws: WebSocket;
    try { ws = new WebSocket(wsUrl(lastSeq(this.state.messages))); } catch { this.scheduleRetry("reconnecting"); return; }
    this.ws = ws;
    ws.onopen = () => { this.retry = 0; this.touch(); this.set({ conn: "live" }); };
    ws.onmessage = (ev) => {
      try { this.handle(JSON.parse(String(ev.data)) as StreamEvent); } catch { /* ignore a bad frame */ }
    };
    ws.onclose = () => { if (this.ws === ws) { this.ws = null; this.scheduleRetry("reconnecting"); } };
    ws.onerror = () => { try { ws.close(); } catch { /* already closed */ } };
  }

  private scheduleRetry(conn: Conn) {
    if (this.stopped) return;
    this.retry += 1;
    this.set({ conn: this.retry > 3 ? "offline" : conn });
    const wait = Math.min(1000 * 2 ** Math.min(this.retry - 1, 3), 8000);
    this.timers.push(setTimeout(() => void this.connect(), wait));
  }

  private handle(ev: StreamEvent) {
    this.touch();
    const s = this.state;
    switch (ev.event) {
      case "hello": this.set({ health: ev.data.health, conn: "live" }); break;
      case "message": {
        const rec = ev.data;
        const known = s.messages.some((m) => m.seq === rec.seq);
        this.set({
          messages: upsertMessage(s.messages, rec),
          fresh: known || !s.loaded ? s.fresh : { ...s.fresh, [rec.seq]: Date.now() },
        });
        break;
      }
      case "message_status": this.set({ messages: applyStatus(s.messages, ev.data) }); break;
      case "task": this.set({ tasks: { ...s.tasks, [ev.data.key]: ev.data } }); break;
      case "node": this.set({ nodes: { ...s.nodes, [ev.data.node]: ev.data } }); break;
      case "stats": this.set({ stats: ev.data }); break;
      case "health": this.set({ health: ev.data }); break;
      case "pong": this.set({}); break;
    }
  }

  /** Called after our own Accept/Done. The WebSocket repeats it, upserts are idempotent. */
  applyLocal(task: Task, record: MessageRecord) {
    this.set({ tasks: { ...this.state.tasks, [task.key]: task }, messages: upsertMessage(this.state.messages, record) });
  }

  /** Called after our own send, so the record shows at once. The WebSocket repeats it, upserts are idempotent. */
  applyRecord(record: MessageRecord) {
    const s = this.state;
    const known = s.messages.some((m) => m.seq === record.seq);
    this.set({ messages: upsertMessage(s.messages, record), fresh: known ? s.fresh : { ...s.fresh, [record.seq]: Date.now() } });
  }

  /** Refresh stats on demand (for example after a send). */
  async refreshStats() { try { this.set({ stats: await api.stats() }); } catch { /* keep the old numbers */ } }
}
