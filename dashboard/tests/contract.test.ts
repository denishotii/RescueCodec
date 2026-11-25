// Keeps the dashboard honest: it may only call endpoints that exist in spec/api/openapi.yaml,
// and the mock server must answer with every field the spec marks as required.
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { spawn, type ChildProcess } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { parse } from "yaml";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const spec = parse(readFileSync(path.join(root, "spec/api/openapi.yaml"), "utf8"));
const specPaths = Object.keys(spec.paths);

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((f) => {
    const p = path.join(dir, f);
    return statSync(p).isDirectory() ? walk(p) : p.endsWith(".ts") || p.endsWith(".tsx") ? [p] : [];
  });
}

describe("only the public API is used", () => {
  const src = walk(path.join(root, "dashboard/src")).map((f) => readFileSync(f, "utf8")).join("\n");
  const used = [...src.matchAll(/["'`](\/v2\/[A-Za-z0-9_./${}()\-]*)/g)].map((m) => m[1].replace(/\$\{[^}]*\}/g, "{}").replace(/\?.*$/, ""));
  it("finds the calls", () => expect(used.length).toBeGreaterThan(5));
  it.each([...new Set(used)])("%s is in openapi.yaml", (u) => {
    const norm = (s: string) => s.replace(/\{[^}]*\}/g, "{}").replace(/\/$/, "");
    expect(specPaths.map(norm)).toContain(norm(u));
  });
});

// ---- mock server answers the shapes in the spec
function resolve(schema: any): any {
  if (schema?.$ref) return resolve(schema.$ref.split("/").slice(1).reduce((o: any, k: string) => o[k], spec));
  return schema;
}
function checkRequired(schema: any, value: any, where: string) {
  const s = resolve(schema);
  if (!s || typeof value !== "object" || value === null) return;
  for (const k of s.required ?? []) expect(value, `${where}.${k}`).toHaveProperty(k);
  for (const [k, sub] of Object.entries<any>(s.properties ?? {})) if (k in value && value[k] !== null) {
    const r = resolve(sub);
    if (r?.type === "object" || r?.properties) checkRequired(r, value[k], `${where}.${k}`);
  }
}

let proc: ChildProcess;
const PORT = 8799;
const base = `http://127.0.0.1:${PORT}`;

beforeAll(async () => {
  proc = spawn("node", [path.join(root, "dashboard/mock/server.mjs"), "--port", String(PORT), "--no-live", "--mayday"], { stdio: "ignore" });
  for (let i = 0; i < 40; i++) {
    try { if ((await fetch(base + "/v2/health")).ok) return; } catch { /* not up yet */ }
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error("mock did not start");
});
afterAll(() => { proc?.kill(); });

describe("mock server matches the spec", () => {
  it("health", async () => checkRequired(spec.components.schemas.Health, await (await fetch(base + "/v2/health")).json(), "health"));
  it("messages", async () => {
    const j = await (await fetch(base + "/v2/messages?limit=1000")).json();
    expect(j.items.length).toBeGreaterThan(10);
    for (const r of j.items) checkRequired(spec.components.schemas.MessageRecord, r, `record ${r.seq}`);
  });
  it("tasks", async () => {
    const j = await (await fetch(base + "/v2/tasks")).json();
    expect(j.items.some((t: any) => t.type === "mayday" && t.state === "open")).toBe(true);
    for (const t of j.items) checkRequired(spec.components.schemas.Task, t, `task ${t.key}`);
  });
  it("stats and nodes", async () => {
    checkRequired(spec.components.schemas.Stats, await (await fetch(base + "/v2/stats")).json(), "stats");
    for (const n of (await (await fetch(base + "/v2/nodes")).json()).items) checkRequired(spec.components.schemas.Node, n, "node");
  });
  it("ack follows the transition rules", async () => {
    const tasks = (await (await fetch(base + "/v2/tasks?state=open")).json()).items;
    const key = tasks[0].key;
    const post = (status: string) => fetch(`${base}/v2/tasks/${key}/ack`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ status, eta: 10, team: "Trupp 9" }) });
    expect((await post("done")).status).toBe(409);
    const ok = await post("accepted");
    expect(ok.status).toBe(202);
    const j = await ok.json();
    expect(j.task.state).toBe("accepted");
    expect(j.record.direction).toBe("out");
    expect((await post("done")).status).toBe(202);
  });
  it("stream says hello and replays", async () => {
    const events: any[] = [];
    await new Promise<void>((resolveP) => {
      const ws = new WebSocket(`ws://127.0.0.1:${PORT}/v2/stream?since_seq=0`);
      ws.onmessage = (e) => { events.push(JSON.parse(String(e.data))); if (events.length >= 5) { ws.close(); resolveP(); } };
    });
    expect(events[0].event).toBe("hello");
    expect(events[1].event).toBe("message");
  });
});
