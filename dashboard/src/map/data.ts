import type { Feature, FeatureCollection, LineString, Polygon } from "geojson";
import type { MessageRecord, Position, Task, TaskState } from "../api/types";
import { circlePolygon, squarePolygon } from "../lib/geo";

export interface PointMarker {
  id: string; // stable key
  seq: number;
  kind: "found" | "request" | "hazard" | "mayday";
  taskKey: string | null;
  state: TaskState | null;
  priority: string;
  lat: number; lon: number;
  label: string;
}
export interface TrackEnd { id: string; from: string; lat: number; lon: number; color: string; label: string }
export interface SectorLabel { id: string; seq: number; lat: number; lon: number; n: number; status: string }

export interface MapModel {
  collection: FeatureCollection;
  markers: PointMarker[];
  trackEnds: TrackEnd[];
  sectors: SectorLabel[];
  bounds: [number, number][];
}

const posOf = (r: MessageRecord): Position | null => r.message.pos ?? null;

/** Builds everything the map draws from public message records and tasks. */
export function buildMapModel(
  messages: MessageRecord[], tasks: Record<string, Task>, colors: string[], nameOf: (id: string) => string,
): MapModel {
  const features: Feature[] = [];
  const markers: PointMarker[] = [];
  const sectors: SectorLabel[] = [];
  const bounds: [number, number][] = [];
  const bySender = new Map<string, [number, number][]>();

  for (const r of messages) {
    if (r.status === "dry_run") continue;
    const p = posOf(r);
    switch (r.type) {
      case "found": case "request": case "mayday": case "hazard": {
        if (!p) break;
        const task = r.task_key ? tasks[r.task_key] : undefined;
        markers.push({
          id: `m${r.seq}`, seq: r.seq, kind: r.type, taskKey: r.task_key, state: task?.state ?? null,
          priority: task?.priority ?? r.priority, lat: p.lat, lon: p.lon, label: r.text.en,
        });
        bounds.push([p.lon, p.lat]);
        if (r.type === "hazard" && r.message.hazard?.radius) {
          features.push({
            type: "Feature", properties: { kind: "hazard-area", seq: r.seq },
            geometry: { type: "Polygon", coordinates: [circlePolygon(p.lat, p.lon, r.message.hazard.radius)] } as Polygon,
          });
        }
        break;
      }
      case "sector": {
        if (!p || !r.message.sector) break;
        const s = r.message.sector;
        features.push({
          type: "Feature", properties: { kind: "sector", seq: r.seq, status: s.status, coverage: s.coverage ?? (s.status === "cleared" ? 100 : 50) },
          geometry: { type: "Polygon", coordinates: [squarePolygon(p.lat, p.lon, 250)] } as Polygon,
        });
        sectors.push({ id: `s${r.seq}`, seq: r.seq, lat: p.lat, lon: p.lon, n: s.sector, status: s.status });
        bounds.push([p.lon, p.lat]);
        break;
      }
      case "track": {
        const pts = r.message.track?.points ?? [];
        if (!pts.length) break;
        const line = bySender.get(r.from) ?? [];
        for (const q of pts) { line.push([q.lon, q.lat]); bounds.push([q.lon, q.lat]); }
        bySender.set(r.from, line);
        break;
      }
      default: break;
    }
  }

  const trackEnds: TrackEnd[] = [];
  let i = 0;
  for (const [from, coords] of bySender) {
    const color = colors[i++ % colors.length];
    if (coords.length > 1) {
      features.push({ type: "Feature", properties: { kind: "track", from, color }, geometry: { type: "LineString", coordinates: coords } as LineString });
    }
    const last = coords[coords.length - 1];
    trackEnds.push({ id: `t${from}`, from, lat: last[1], lon: last[0], color, label: nameOf(from) });
  }

  return { collection: { type: "FeatureCollection", features }, markers, trackEnds, sectors, bounds };
}
