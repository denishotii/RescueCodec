# Product

<!-- impeccable:product-schema 1 -->

<!-- Inferred from the workflow brief and docs/PLAN.md, not from an interview (subagent, no answer mechanism). Treat every line as an assumption until the team confirms it. -->

## Platform

web

## Stack
Vite + React + TypeScript + MapLibre GL, fixed by the brief. Built files are served by the gateway from rescuecodec/gateway/static/.

## Users
The command-post operator of a rescue operation (Bergwacht, DLRG, THW/DRK, dog teams, volunteer search parties). They sit at a laptop in a vehicle, tent or hut, often outdoors in daylight or at night, with a second person reading the screen over their shoulder. Their job: see what the field teams report, decide who takes each task, and make sure nothing urgent is missed. A projector view must read from 10 m.

## Product Purpose
RescueCodec turns a spoken or typed report into a tiny message that travels over Meshtastic LoRa radio, with no internet. The gateway decodes it into readable German or English. The dashboard is the command-post view of that stream: live reports, tasks with Accept and Done, a map of finds, tracks, sectors and hazards, honest stats, and nodes. Success: the operator never misses a Mayday, and every found person has a team accepted.

## Positioning
An open, offline command view built only on the public gateway API (REST + WebSocket). Anything the dashboard does, any other software can do too.

## Operating Context
Fully offline: no CDN, no remote fonts, offline vector base map and terrain from local PMTiles. Radio links are slow and lossy, so delivery state (queued, sent, delivered, failed) and airtime limits are real, visible facts. Messages in German or English.

## Capabilities and Constraints
- Uses only spec/api/openapi.yaml: /v2/health, /v2/messages, /v2/tasks, /v2/tasks/{key}/ack, /v2/stats, /v2/nodes, /v2/export.geojson, /v2/stream.
- Every number is tagged measured or estimated (the API says which).
- Mayday overrides everything with a red banner.
- Terminology: found, track, sector, request, hazard, mayday, ack; task states open, accepted, declined, done.
- Team labels, not personal names, on nodes.

## Brand Commitments
Name: RescueCodec. Plain English, short sentences, no em-dashes. English UI with a German toggle for decoded text.

## Evidence on Hand
Contract in spec/api/openapi.yaml. Offline map data in assets-offline/map (basemap.pmtiles, terrain.pmtiles, terrain-siebengebirge-z14.pmtiles; Siebengebirge near Koeln). No real field data yet: demo data comes from the mock server and the simulator and must stay labelled as such.

## Product Principles
1. Urgent first: Mayday and open critical tasks beat everything else on screen.
2. Honest numbers: measured vs estimated, always visible.
3. Works with the network cut and with a dead radio: say what is true (connected, last heard, queued, failed).
4. One glance from 10 m: the state of the operation reads without clicking.

## Accessibility & Inclusion
Projector readability (large type, high contrast), colour never the only signal (icons and words with state), keyboard operable, reduced-motion respected.
