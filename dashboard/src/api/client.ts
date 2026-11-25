// REST client. Every path here must exist in spec/api/openapi.yaml (tests/contract.test.ts checks that).
import type {
  AckStatus, ApiErrorBody, Health, MeshNode, MessageRecord, Stats, Task,
} from "./types";

export interface RuntimeConfig {
  /** Gateway origin. Empty string means the page's own origin (the normal case). */
  apiBase: string;
  /** Folder with the offline map files, relative to the page or absolute. */
  mapBase: string;
  /** Where the map starts when there is nothing to show yet, for example the event site. */
  home?: { lon: number; lat: number; zoom?: number };
}

export const config: RuntimeConfig = { apiBase: "", mapBase: "map" };

/** Reads ./config.json (optional) and the ?api= query for development. Never throws. */
export async function loadConfig(): Promise<RuntimeConfig> {
  try {
    const r = await fetch("config.json", { cache: "no-store" });
    if (r.ok) {
      const j = (await r.json()) as Partial<RuntimeConfig>;
      if (typeof j.apiBase === "string") config.apiBase = j.apiBase.replace(/\/$/, "");
      if (typeof j.mapBase === "string") config.mapBase = j.mapBase.replace(/\/$/, "");
      const h = j.home;
      if (h && Number.isFinite(h.lon) && Number.isFinite(h.lat)) {
        config.home = { lon: h.lon, lat: h.lat, zoom: Number.isFinite(h.zoom) ? h.zoom : undefined };
      }
    }
  } catch { /* no config file: defaults */ }
  const q = new URLSearchParams(location.search).get("api");
  if (q) config.apiBase = q.replace(/\/$/, "");
  return config;
}

/** The gateway token, if it runs with --token: from ?token=... in the page address, then kept for this tab. */
export function authToken(): string | null {
  try {
    const q = new URLSearchParams(location.search).get("token");
    if (q) { try { sessionStorage.setItem("rc-token", q); } catch { /* private window */ } return q; }
    return sessionStorage.getItem("rc-token");
  } catch { return null; }
}

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public retryAfter?: number, public field?: string | null) {
    super(message);
  }
}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  const token = authToken();
  try {
    res = await fetch(config.apiBase + path, {
      ...init,
      headers: { Accept: "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(init?.body ? { "Content-Type": "application/json" } : {}), ...init?.headers },
    });
  } catch {
    throw new ApiError(0, "unreachable", "Gateway not reachable");
  }
  if (!res.ok) {
    let body: Partial<ApiErrorBody> = {};
    try { body = (await res.json()) as ApiErrorBody; } catch { /* not JSON */ }
    const e = body.error;
    throw new ApiError(res.status, e?.code ?? "http_" + res.status, e?.message ?? res.statusText, e?.retry_after_s, e?.field);
  }
  return (await res.json()) as T;
}

export const api = {
  health: () => call<Health>("/v2/health"),
  messages: (sinceSeq = 0, limit = 500) =>
    call<{ items: MessageRecord[]; next_since_seq: number }>(`/v2/messages?since_seq=${sinceSeq}&limit=${limit}`),
  tasks: () => call<{ items: Task[] }>("/v2/tasks"),
  nodes: () => call<{ items: MeshNode[] }>("/v2/nodes"),
  stats: () => call<Stats>("/v2/stats"),
  ack: (key: string, body: { status: AckStatus; eta?: number; team?: string }) =>
    call<{ task: Task; record: MessageRecord }>(`/v2/tasks/${encodeURIComponent(key)}/ack`, { method: "POST", body: JSON.stringify(body) }),
  geojsonUrl: () => config.apiBase + "/v2/export.geojson" + (authToken() ? `?token=${encodeURIComponent(authToken()!)}` : ""),
};

export function wsUrl(sinceSeq: number): string {
  const base = config.apiBase || location.origin;
  const u = new URL("/v2/stream", base);
  u.protocol = u.protocol === "https:" ? "wss:" : "ws:";
  if (sinceSeq > 0) u.searchParams.set("since_seq", String(sinceSeq));
  const token = authToken();
  if (token) u.searchParams.set("token", token);
  return u.toString();
}
