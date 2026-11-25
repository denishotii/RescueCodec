import { useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import {
  addProtocol, AttributionControl, Map as MlMap, Marker, NavigationControl, ScaleControl, setWorkerUrl, type GeoJSONSource,
} from "maplibre-gl";
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?url";
import { Protocol } from "pmtiles";
import "maplibre-gl/dist/maplibre-gl.css";
import { Download, Layers, LocateFixed, Mountain, Square } from "lucide-react";
import { api } from "../api/client";
import { useData, useT } from "../state/hooks";
import { setUi, useUi, type Layers as LayerKeys } from "../state/ui";
import { isActiveMayday } from "../lib/format";
import { buildMapModel, type MapModel, type PointMarker } from "./data";
import { buildStyle, SRC, TRACK_COLORS_DAY, TRACK_COLORS_NIGHT } from "./style";
import { computeTicks, type Ticks } from "./ticks";
import { Panel, TYPE_ICON } from "../components/bits";
import { nodeLabel } from "../lib/format";
import type { Key } from "../lib/i18n";

let protocolReady = false;
function ensureProtocol() {
  if (protocolReady) return;
  setWorkerUrl(new URL(workerUrl, location.href).href); // the worker ships as a plain file next to the app
  const p = new Protocol();
  addProtocol("pmtiles", p.tile);
  protocolReady = true;
}

const HOME: [number, number] = [7.215, 50.69];

function useMarkerHost(map: MlMap | null, items: { id: string; lat: number; lon: number }[], className: string) {
  const store = useRef(new Map<string, { marker: Marker; el: HTMLElement }>());
  const [, bump] = useState(0);
  useEffect(() => {
    if (!map) return;
    const have = store.current;
    const wanted = new Set(items.map((i) => i.id));
    let changed = false;
    for (const [id, v] of have) if (!wanted.has(id)) { v.marker.remove(); have.delete(id); changed = true; }
    for (const it of items) {
      let v = have.get(it.id);
      if (!v) {
        const el = document.createElement("div");
        el.className = className;
        const marker = new Marker({ element: el, anchor: "center" }).setLngLat([it.lon, it.lat]).addTo(map);
        v = { marker, el }; have.set(it.id, v); changed = true;
      } else v.marker.setLngLat([it.lon, it.lat]);
    }
    if (changed) bump((n) => n + 1);
  }, [map, items, className]);
  useEffect(() => () => { for (const v of store.current.values()) v.marker.remove(); store.current.clear(); }, []);
  return store.current;
}

function Badge({ m, selected }: { m: PointMarker; selected: boolean }) {
  const Icon = TYPE_ICON[m.kind];
  const state = m.kind === "hazard" ? "hazard" : m.kind === "mayday" && m.state !== "done" ? "mayday" : m.state ?? "info";
  return (
    <button className={`pin pin-${state} pin-${m.kind} ${selected ? "selected" : ""}`} aria-label={m.label} title={m.label}
      onClick={() => setUi({ focus: { seq: m.seq, key: m.taskKey, at: Date.now() } })}>
      {m.kind === "mayday" && m.state !== "done" && <span className="pin-pulse" aria-hidden="true" />}
      <Icon size={m.kind === "mayday" ? 20 : 17} strokeWidth={2.2} aria-hidden="true" />
    </button>
  );
}

const LAYER_IDS: Record<keyof LayerKeys, string[]> = {
  finds: [], tracks: ["rc-track", "rc-track-casing"], sectors: ["rc-sector-fill", "rc-sector-line"],
  hazards: ["rc-hazard-fill", "rc-hazard-line"], nodes: [],
};

const LAYER_LABEL: Record<keyof LayerKeys, Key> = {
  finds: "layerFinds", tracks: "layerTracks", sectors: "layerSectors", hazards: "layerHazards", nodes: "layerNodes",
};

export function MapPanel() {
  const { messages, tasks, nodes } = useData();
  const ui = useUi();
  const t = useT();
  const host = useRef<HTMLDivElement>(null);
  const [map, setMap] = useState<MlMap | null>(null);
  const [ready, setReady] = useState(0); // bumps after every style load
  const [ticks, setTicks] = useState<Ticks | null>(null);
  const fitted = useRef(false);
  const seenMayday = useRef(new Set<string>());
  const [selected, setSelected] = useState<string | null>(null);
  const [layersOpen, setLayersOpen] = useState(false);

  const colors = ui.theme === "dark" ? TRACK_COLORS_NIGHT : TRACK_COLORS_DAY;
  const nameOf = (id: string) => nodes[id]?.short_name || nodeLabel(id, nodes);
  const model: MapModel = useMemo(
    () => buildMapModel(messages, tasks, colors, nameOf),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [messages, tasks, nodes, ui.theme],
  );

  // create the map once
  useEffect(() => {
    if (!host.current) return;
    ensureProtocol();
    const m = new MlMap({
      container: host.current, style: buildStyle(ui.theme, ui.lang, ui.room), center: HOME, zoom: 11.6, maxPitch: 75,
      attributionControl: false, canvasContextAttributes: { antialias: true },
    });
    m.addControl(new AttributionControl({ compact: true }), "bottom-right");
    m.addControl(new NavigationControl({ visualizePitch: true }), "top-right");
    m.addControl(new ScaleControl({ unit: "metric" }), "bottom-left");
    const refresh = () => { try { setTicks(computeTicks(m)); } catch { /* map not ready */ } };
    m.on("style.load", () => setReady((n) => n + 1));
    m.on("move", refresh);
    m.on("resize", refresh);
    m.on("load", refresh);
    const ro = new ResizeObserver(() => m.resize());
    ro.observe(host.current);
    setMap(m);
    (window as unknown as { __rcMap?: MlMap }).__rcMap = m; // handy for the screenshot script
    return () => { ro.disconnect(); m.remove(); setMap(null); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // theme or language changed: swap the style (our sources come with it)
  const styleKey = useRef(`${ui.theme}/${ui.lang}/${ui.room}`);
  useEffect(() => {
    if (!map) return;
    const k = `${ui.theme}/${ui.lang}/${ui.room}`;
    if (k === styleKey.current) return;
    styleKey.current = k;
    map.setStyle(buildStyle(ui.theme, ui.lang, ui.room), { diff: false });
  }, [map, ui.theme, ui.lang, ui.room]);

  // data into the GeoJSON source, layer visibility, terrain
  useEffect(() => {
    if (!map || !ready) return;
    const src = map.getSource(SRC.reports) as GeoJSONSource | undefined;
    src?.setData(model.collection);
    for (const key of Object.keys(LAYER_IDS) as (keyof LayerKeys)[])
      for (const id of LAYER_IDS[key]) if (map.getLayer(id)) map.setLayoutProperty(id, "visibility", ui.layers[key] ? "visible" : "none");
  }, [map, ready, model, ui.layers]);

  useEffect(() => {
    if (!map || !ready) return;
    if (ui.view3d) {
      map.setTerrain({ source: "terrain", exaggeration: 1.6 });
      map.easeTo({ pitch: 64, bearing: map.getBearing() || -25, duration: 900 });
    } else {
      map.setTerrain(null);
      map.easeTo({ pitch: 0, bearing: 0, duration: 700 });
    }
  }, [map, ready, ui.view3d]);

  // first fit, then a fly-to for every new Mayday
  useEffect(() => {
    if (!map) return;
    if (!fitted.current && model.bounds.length) {
      fitted.current = true;
      fit(map, model.bounds);
    }
    for (const task of Object.values(tasks)) {
      if (isActiveMayday(task) && task.pos && !seenMayday.current.has(task.key)) {
        seenMayday.current.add(task.key);
        if (fitted.current) map.flyTo({ center: [task.pos.lon, task.pos.lat], zoom: Math.max(map.getZoom(), 14), duration: 1200 });
      }
    }
  }, [map, model.bounds, tasks]);

  // operator picked something in a list
  useEffect(() => {
    const f = ui.focus;
    if (!map || !f) return;
    let target: [number, number] | null = null;
    if (f.key?.startsWith("node:")) {
      const n = nodes[f.key.slice(5)];
      if (n?.pos) target = [n.pos.lon, n.pos.lat];
      setSelected(f.key);
    } else {
      const m = model.markers.find((x) => x.seq === f.seq) ?? model.markers.find((x) => x.taskKey && x.taskKey === f.key);
      const rec = messages.find((x) => x.seq === f.seq);
      if (m) { target = [m.lon, m.lat]; setSelected(m.id); }
      else if (rec?.message.track) { const p = rec.message.track.points.at(-1)!; target = [p.lon, p.lat]; }
      else if (rec?.message.pos) target = [rec.message.pos.lon, rec.message.pos.lat];
    }
    if (target) map.flyTo({ center: target, zoom: Math.max(map.getZoom(), 14.5), duration: 900 });
    if (ui.tab !== "map" && window.matchMedia("(max-width: 1199px)").matches) setUi({ tab: "map" });
    const id = setTimeout(() => setSelected(null), 6000);
    return () => clearTimeout(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ui.focus]);

  // markers
  const pinItems = useMemo(
    () => model.markers.filter((m) => (m.kind === "hazard" ? ui.layers.hazards : ui.layers.finds)),
    [model.markers, ui.layers.finds, ui.layers.hazards],
  );
  const pins = useMarkerHost(map, pinItems, "pin-host");

  const endItems = useMemo(() => (ui.layers.tracks ? model.trackEnds : []), [model.trackEnds, ui.layers.tracks]);
  const ends = useMarkerHost(map, endItems, "end-host");

  const sectorItems = useMemo(() => (ui.layers.sectors ? model.sectors : []), [model.sectors, ui.layers.sectors]);
  const sectorPins = useMarkerHost(map, sectorItems, "sector-host");

  const nodeItems = useMemo(
    () => (ui.layers.nodes ? Object.values(nodes).filter((n) => n.pos && !(ui.layers.tracks && model.trackEnds.some((e) => e.from === n.node))).map((n) => ({ id: "n" + n.node, lat: n.pos!.lat, lon: n.pos!.lon, n })) : []),
    [nodes, ui.layers.nodes, ui.layers.tracks, model.trackEnds],
  );
  const nodePins = useMarkerHost(map, nodeItems, "node-host");

  const counts = useMemo(() => ({
    finds: model.markers.filter((m) => m.kind !== "hazard").length,
    tracks: model.trackEnds.length,
    sectors: model.sectors.length,
    hazards: model.markers.filter((m) => m.kind === "hazard").length,
    nodes: nodeItems.length,
  }), [model, nodeItems.length]);

  return (
    <Panel id="map" title={t("map")} className="panel-map" actions={
      <div className="map-tools">
        <div className="seg view-seg" role="group" aria-label="View">
          <button className={!ui.view3d ? "on" : ""} aria-pressed={!ui.view3d} onClick={() => setUi({ view3d: false })}><Square size={14} aria-hidden="true" /> {t("view2d")}</button>
          <button className={ui.view3d ? "on" : ""} aria-pressed={ui.view3d} onClick={() => setUi({ view3d: true })}><Mountain size={14} aria-hidden="true" /> {t("view3d")}</button>
        </div>
        <button className="btn btn-quiet btn-sm" onClick={() => map && fit(map, model.bounds)}><LocateFixed size={14} aria-hidden="true" /> {t("fit")}</button>
        <a className="btn btn-quiet btn-sm" href={api.geojsonUrl()} download="rescuecodec.geojson"><Download size={14} aria-hidden="true" /> {t("exportGeo")}</a>
      </div>
    }>
      <div className="map-wrap">
        <div ref={host} className="map" role="region" aria-label={t("map")} />
        <div className="sheet-edge" aria-hidden="true">
          {ticks && (
            <>
              <div className="ticks-x">{ticks.x.map((k, i) => <span key={i} className={`tick ${k.major ? "major" : ""}`} style={{ left: k.pos }}><i />{k.label}</span>)}</div>
              <div className="ticks-y">{ticks.y.map((k, i) => <span key={i} className={`tick ${k.major ? "major" : ""}`} style={{ top: k.pos }}><i />{k.label}</span>)}</div>
              <span className="grid-zone mono">{ticks.zone}</span>
            </>
          )}
        </div>
        <div className="layer-chips">
          <button className="layer-btn" aria-expanded={layersOpen} onClick={() => setLayersOpen((o) => !o)}>
            <Layers size={18} aria-hidden="true" /> {t("layers")}
          </button>
          {layersOpen && <div className="layer-pop" role="group" aria-label={t("layers")}>
          <div className="layer-row">
          {(Object.keys(LAYER_LABEL) as (keyof LayerKeys)[]).map((k) => (
            <button key={k} className={`chip-toggle layer-${k} ${ui.layers[k] ? "on" : ""}`} aria-pressed={ui.layers[k]}
              onClick={() => setUi({ layers: { ...ui.layers, [k]: !ui.layers[k] } })}>
              <i aria-hidden="true" />{t(LAYER_LABEL[k])} <span className="num">{counts[k]}</span>
            </button>
          ))}
          </div>
          {counts.sectors > 0 && ui.layers.sectors && <p className="chip-note">{t("sectorNote")}</p>}
          </div>}
        </div>
      </div>
      {map && pinItems.map((m) => { const v = pins.get(m.id); return v ? createPortal(<Badge m={m} selected={selected === m.id} />, v.el, m.id) : null; })}
      {map && endItems.map((e) => { const v = ends.get(e.id); return v ? createPortal(<span className="end-tag" style={{ ["--c" as string]: e.color }}>{e.label}</span>, v.el, e.id) : null; })}
      {map && sectorItems.map((s) => { const v = sectorPins.get(s.id); return v ? createPortal(<span className={`sector-tag s-${s.status}`}>{s.n}</span>, v.el, s.id) : null; })}
      {map && nodeItems.map((n) => {
        const v = nodePins.get(n.id);
        return v ? createPortal(
          <span className={`node-tag ${n.n.is_gateway ? "gw" : ""} ${selected === "node:" + n.n.node ? "selected" : ""}`}>{n.n.short_name || n.n.node.slice(-4)}</span>, v.el, n.id) : null;
      })}
    </Panel>
  );
}

function fit(map: MlMap, bounds: [number, number][]) {
  if (!bounds.length) { map.flyTo({ center: HOME, zoom: 12, duration: 600 }); return; }
  let [w, s, e, n] = [180, 90, -180, -90];
  for (const [lon, lat] of bounds) { w = Math.min(w, lon); e = Math.max(e, lon); s = Math.min(s, lat); n = Math.max(n, lat); }
  map.fitBounds([[w, s], [e, n]], { padding: { top: 70, bottom: 60, left: 60, right: 60 }, maxZoom: 15, duration: 800 });
}
