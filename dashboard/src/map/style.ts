import { DARK, LIGHT, layers as pmLayers, type Flavor } from "@protomaps/basemaps";
import type { StyleSpecification, LayerSpecification } from "maplibre-gl";
import { config } from "../api/client";
import type { Lang } from "../lib/i18n";
import type { Theme } from "../state/ui";

/** Base map colours in the topographic-sheet palette (forest greens, water blue, paper). */
const dayFlavor: Flavor = {
  ...LIGHT,
  background: "#e3e8da", earth: "#e9ede0",
  park_a: "#d4e3c4", park_b: "#d4e3c4", wood_a: "#c3d8b3", wood_b: "#c3d8b3", scrub_a: "#dbe5c5", scrub_b: "#dbe5c5",
  water: "#a9cde3", pedestrian: "#e3e6d8", buildings: "#cfd2c4",
  minor_a: "#ffffff", minor_b: "#ffffff", major: "#fff3c9", highway: "#f6d98b", other: "#fbfbf5", link: "#ffffff",
  minor_casing: "#c4c8b6", major_casing_early: "#c1b88d", major_casing_late: "#c1b88d", highway_casing_early: "#b59f55", highway_casing_late: "#b59f55",
  boundaries: "#8d6a8c",
  roads_label_minor: "#4b5a4f", roads_label_minor_halo: "#f2f4ea", roads_label_major: "#2f3d33", roads_label_major_halo: "#f2f4ea",
  subplace_label: "#3a4a3f", subplace_label_halo: "#eef1e4", city_label: "#17231c", city_label_halo: "#eef1e4",
  state_label: "#566659", state_label_halo: "#eef1e4", country_label: "#566659",
  landcover: { barren: "#e6e3cf", farmland: "#e4ebcf", forest: "#bcd3ad", glacier: "#ffffff", grassland: "#d7e5c1", scrub: "#d9e2bd", urban_area: "#dcded3" },
};
const nightFlavor: Flavor = {
  ...DARK,
  background: "#0c1511", earth: "#101a15",
  park_a: "#14231b", park_b: "#14231b", wood_a: "#14281c", wood_b: "#14281c", scrub_a: "#172519", scrub_b: "#172519",
  water: "#12303f", pedestrian: "#16221b", buildings: "#1b2a22",
  minor_a: "#2a3b32", minor_b: "#2a3b32", major: "#3a4a3b", highway: "#5a5a36", other: "#243229", link: "#2a3b32",
  minor_casing: "#0c1511", major_casing_early: "#0c1511", major_casing_late: "#0c1511", highway_casing_early: "#0c1511", highway_casing_late: "#0c1511",
  boundaries: "#7a5a7a",
  roads_label_minor: "#93a69a", roads_label_minor_halo: "#0c1511", roads_label_major: "#c4d2c8", roads_label_major_halo: "#0c1511",
  subplace_label: "#aebdb3", subplace_label_halo: "#0c1511", city_label: "#e5ede6", city_label_halo: "#0c1511",
  state_label: "#93a69a", state_label_halo: "#0c1511", country_label: "#93a69a",
  landcover: { barren: "#1a211a", farmland: "#15221a", forest: "#112419", glacier: "#2a3a36", grassland: "#14221a", scrub: "#16241a", urban_area: "#18221d" },
};

export const absUrl = (rel: string) => new URL(rel, location.href).href;

/** Names of the sources and layers the app adds on top of the base map. */
export const SRC = { reports: "rc-reports" } as const;

/** Multiplies every size in a MapLibre text-size value (a number, or interpolate/step stops). */
function scaleSize(v: unknown, k: number): unknown {
  if (typeof v === "number") return v * k;
  if (!Array.isArray(v)) return v;
  if (v[0] === "interpolate" || v[0] === "interpolate-hcl" || v[0] === "interpolate-lab") {
    return v.map((x, i) => (i >= 4 && i % 2 === 0 ? scaleSize(x, k) : x));
  }
  if (v[0] === "step") return v.map((x, i) => (i === 2 || (i >= 4 && i % 2 === 0) ? scaleSize(x, k) : x));
  return v;
}

export function buildStyle(theme: Theme, lang: Lang, room = false): StyleSpecification {
  const flavor = theme === "dark" ? nightFlavor : dayFlavor;
  const base = absUrl(config.mapBase + "/");
  // pois, road shields and one-way arrows need a sprite sheet, which we do not ship
  const all = (pmLayers("protomaps", flavor, { lang }) as LayerSpecification[]).filter((l) => !["pois", "roads_shields", "roads_oneway"].includes(l.id));

  const hill: LayerSpecification = {
    id: "hillshade", type: "hillshade", source: "terrain",
    paint: {
      "hillshade-exaggeration": theme === "dark" ? 0.55 : 0.45,
      "hillshade-shadow-color": theme === "dark" ? "#000000" : "#3a4b3a",
      "hillshade-highlight-color": theme === "dark" ? "#3d5a4a" : "#ffffff",
      "hillshade-accent-color": theme === "dark" ? "#000000" : "#4d5d46",
    },
  };
  if (room) {
    // Room view: bigger map labels with a thicker halo, so place names read from the back of the room.
    for (const l of all) {
      if (l.type !== "symbol") continue;
      const lay = (l.layout ?? {}) as Record<string, unknown>;
      lay["text-size"] = scaleSize(lay["text-size"] ?? 12, 1.4);
      l.layout = lay as typeof l.layout;
      if (l.paint) (l.paint as Record<string, unknown>)["text-halo-width"] = 2;
    }
  }
  const at = all.findIndex((l) => l.id === "water");
  const layers = [...all.slice(0, at), hill, ...all.slice(at)];

  return {
    version: 8,
    glyphs: `${base}glyphs/{fontstack}/{range}.pbf`,
    sources: {
      protomaps: { type: "vector", url: `pmtiles://${base}basemap.pmtiles`, attribution: "© OpenStreetMap contributors, Protomaps" },
      terrain: {
        type: "raster-dem", url: `pmtiles://${base}terrain.pmtiles`, tileSize: 512, encoding: "terrarium",
        attribution: '<a href="https://mapterhorn.com/attribution" target="_blank" rel="noreferrer">© Mapterhorn</a>',
      },
      [SRC.reports]: { type: "geojson", data: { type: "FeatureCollection", features: [] } },
    },
    layers: [
      ...layers,
      // hazards: radius area
      { id: "rc-hazard-fill", type: "fill", source: SRC.reports, filter: ["==", ["get", "kind"], "hazard-area"],
        paint: { "fill-color": theme === "dark" ? "#ff6b5e" : "#c4301f", "fill-opacity": 0.16 } },
      { id: "rc-hazard-line", type: "line", source: SRC.reports, filter: ["==", ["get", "kind"], "hazard-area"],
        paint: { "line-color": theme === "dark" ? "#ff8a7e" : "#a82414", "line-width": 2, "line-dasharray": [3, 2] } },
      // sectors
      { id: "rc-sector-fill", type: "fill", source: SRC.reports, filter: ["==", ["get", "kind"], "sector"],
        paint: {
          "fill-color": ["match", ["get", "status"], "cleared", theme === "dark" ? "#77cf90" : "#2f8a4c", "blocked", theme === "dark" ? "#ff6b5e" : "#c4301f", theme === "dark" ? "#f2ad4e" : "#d98a12"],
          "fill-opacity": ["interpolate", ["linear"], ["coalesce", ["get", "coverage"], 50], 0, 0.1, 100, 0.38],
        } },
      { id: "rc-sector-line", type: "line", source: SRC.reports, filter: ["==", ["get", "kind"], "sector"],
        paint: { "line-color": theme === "dark" ? "#c4d2c8" : "#2f3d33", "line-width": 1.5 } },
      // tracks: white/dark casing under the coloured line so it reads on any ground
      { id: "rc-track-casing", type: "line", source: SRC.reports, filter: ["==", ["get", "kind"], "track"],
        layout: { "line-cap": "round", "line-join": "round" },
        paint: { "line-color": theme === "dark" ? "#0c1511" : "#ffffff", "line-width": 7, "line-opacity": 0.85 } },
      { id: "rc-track", type: "line", source: SRC.reports, filter: ["==", ["get", "kind"], "track"],
        layout: { "line-cap": "round", "line-join": "round" },
        paint: { "line-color": ["get", "color"], "line-width": 4 } },
    ],
    sky: undefined,
  } as StyleSpecification;
}

export const TRACK_COLORS_DAY = ["#8a4f12", "#00778a", "#5d7a14", "#3f55a6", "#a1306a"];
export const TRACK_COLORS_NIGHT = ["#e0a35f", "#4dc3d6", "#a8cf4a", "#8fa3f0", "#ef7fb0"];
