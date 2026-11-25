#!/usr/bin/env bash
# Reproduce every offline download. Idempotent: existing files are skipped.
# Needs internet once. Tools: curl, unzip, npm, pmtiles (brew install pmtiles).
set -euo pipefail
cd "$(dirname "$0")/.."
A=assets-offline
mkdir -p $A/vosk $A/web $A/map

# 1. Vosk small models
for m in vosk-model-small-de-0.15 vosk-model-small-en-us-0.15; do
  if [ ! -d $A/vosk/$m ]; then
    curl -fsSL -o $A/vosk/$m.zip https://alphacephei.com/vosk/models/$m.zip
    unzip -q -o $A/vosk/$m.zip -d $A/vosk
  fi
done

# 2. MapLibre GL JS and PMTiles JS (pinned, from npm)
ML=6.19.0; PM=4.5.0
T=$(mktemp -d)
if [ ! -f $A/web/maplibre-gl-$ML/maplibre-gl.mjs ]; then
  (cd $T && npm pack maplibre-gl@$ML --silent >/dev/null && tar xzf maplibre-gl-$ML.tgz)
  mkdir -p $A/web/maplibre-gl-$ML
  cp $T/package/dist/{maplibre-gl.mjs,maplibre-gl-shared.mjs,maplibre-gl-worker.mjs,maplibre-gl.css} $T/package/LICENSE.txt $A/web/maplibre-gl-$ML/
  rm -rf $T/package
fi
if [ ! -f $A/web/pmtiles-$PM/pmtiles.js ]; then
  (cd $T && npm pack pmtiles@$PM --silent >/dev/null && tar xzf pmtiles-$PM.tgz)
  mkdir -p $A/web/pmtiles-$PM
  cp $T/package/dist/pmtiles.js $A/web/pmtiles-$PM/
  rm -rf $T/package
fi
rm -rf "$T"

# 3. Map extracts (Siebengebirge / Bonn / Koeln)
BBOX=6.85,50.60,7.35,50.80
if [ ! -f $A/map/basemap.pmtiles ]; then
  # Protomaps daily build; walk back until one exists
  for d in 2 3 4 5 6 7; do
    D=$(date -u -v-${d}d +%Y%m%d 2>/dev/null || date -u -d "-$d days" +%Y%m%d)
    if curl -fsI "https://build.protomaps.com/$D.pmtiles" >/dev/null; then
      pmtiles extract "https://build.protomaps.com/$D.pmtiles" $A/map/basemap.pmtiles --bbox=$BBOX --maxzoom=14
      echo "$D" > $A/map/basemap.build-date; break
    fi
  done
fi
[ -f $A/map/terrain.pmtiles ] || pmtiles extract https://download.mapterhorn.com/planet.pmtiles $A/map/terrain.pmtiles --bbox=$BBOX --maxzoom=12
[ -f $A/map/terrain-siebengebirge-z14.pmtiles ] || pmtiles extract https://download.mapterhorn.com/planet.pmtiles $A/map/terrain-siebengebirge-z14.pmtiles --bbox=7.15,50.635,7.292,50.725 --maxzoom=14
echo "done"; du -sh $A/*
