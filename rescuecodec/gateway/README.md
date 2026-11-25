# RescueCodec gateway

The gateway sits next to one Meshtastic radio. It reads every RescueCodec packet the radio hears, stores it, and shows it through a REST and WebSocket API, a dashboard, MQTT and TAK. It also sends reports, Accept and Done back over the mesh.

Everything runs offline. The API is described in `spec/api/openapi.yaml`. The gateway serves that page at `/docs` (Swagger UI is bundled, no internet needed).

## Start it

```bash
# Simulator (sim/up.sh first)
rescuecodec-gateway --tcp 127.0.0.1:4404

# A USB radio, for example a Heltec V3. "auto" finds /dev/cu.usbserial-* or /dev/cu.SLAB_USBtoUART*
rescuecodec-gateway --serial auto
rescuecodec-gateway --serial /dev/cu.usbserial-0001      # if you have more than one

# No radio at all: an in-memory mesh, with a pretend team that reports and accepts
rescuecodec-gateway --emulator --emulator-demo
```

Then open http://127.0.0.1:8000/ (dashboard) or http://127.0.0.1:8000/docs (API).

Without installing: `.venv/bin/python -m rescuecodec.gateway --emulator-demo`.

Every flag also exists as an environment variable: `--tcp` is `RESCUECODEC_TCP`, `--mqtt` is `RESCUECODEC_MQTT`, and so on. Run `rescuecodec-gateway --help` for the list.

If the radio is missing or unplugged, the gateway still starts. `/v2/health` shows `degraded`. It reconnects by itself, also after a USB or Wi-Fi drop.

## Try the API

```bash
# Send a report. The gateway adds the id.
curl -s -X POST localhost:8000/v2/messages -H 'content-type: application/json' \
  -d '{"message":{"pos":{"lat":50.69212,"lon":7.21345},"found":{"age":"child","consciousness":"conscious","needs":["stretcher"]}}}'

# What did we hear?
curl -s localhost:8000/v2/messages
curl -s localhost:8000/v2/tasks

# Accept a task from the command post. The Ack goes out over the radio.
curl -s -X POST localhost:8000/v2/tasks/a1b2c3d4-7/ack -H 'content-type: application/json' \
  -d '{"status":"accepted","eta":15,"team":"Trupp 3"}'

# Live events
websocat ws://127.0.0.1:8000/v2/stream
```

Each received record carries the radio facts: `radio.rssi`, `radio.snr` and `radio.hops` (hop start minus hop limit). Simulated and emulated radios have no signal, so those stay `null`.

Sending is asynchronous. A record goes `queued`, then `sent`, then `delivered` or `failed`. Mayday goes first. The airtime budget is 10 percent per hour. When it is used up, you get `429` with `retry_after_s`. A Mayday is never held back.

About `failed` with `max_retransmit`: for a broadcast, Meshtastic counts a packet as delivered when it hears a neighbour repeat it. If no radio in range repeats packets, the status ends as `failed` even though the other side got it. The Accept that comes back is the real proof.

## Connectors

| Flag | What you get |
|---|---|
| `--mqtt HOST[:PORT]` | JSON on `rescuecodec/v2/messages`, `rescuecodec/v2/<type>` (found, track, sector, request, hazard, mayday, ack, text), `rescuecodec/v2/tasks`, `rescuecodec/v2/readable` (one plain English line) and `rescuecodec/v2/status` (online or offline). Payloads are the same JSON as the API. |
| `--cot` | TAK events as UDP multicast to `239.2.3.1:6969` (change with `--cot-udp`, pick the network card with `--cot-iface`). |
| `--cot-tcp HOST:PORT` | The same events to a TAK server over TCP. Implies `--cot`. |
| `--text-over-air out\|in\|both` | Also post the readable line on the normal Meshtastic text port, so stock apps show it. Off by default because it doubles airtime. `out` is what this gateway sends, `in` is what it hears. |
| `GET /v2/export.geojson` | GeoJSON of finds, hazards, maydays and requests (points) and tracks (lines). |

MQTT needs `paho-mqtt` (`pip install 'rescuecodec[gateway]'`). Start a local broker with `scripts/mqtt-up.sh`.

### What TAK shows

| RescueCodec | CoT | On the map |
|---|---|---|
| Found | `b-m-p-s-m` spot marker | red |
| Request | `b-m-p-s-m` | blue |
| Hazard | `b-m-p-s-m` | orange |
| Sector | `b-m-p-s-m` | green cleared, yellow partial, red blocked |
| Mayday | `b-a-o-tbl` 911 alert, `b-a-o-can` when done | ATAK alert |
| Track | `a-f-G-U-C` unit at the last point | friendly unit |

The uid is `RescueCodec.<task key>`, so Accept and Done update the same marker. A done task expires its marker. Messages without GPS use the sender's last node position, if the radio knows one. Events follow ATAK-CIV's own examples (`takcot/examples`, `EmergencyManager.java`). The unit tests check this.

## Files

| File | What |
|---|---|
| `app.py` | FastAPI app: the API of `openapi.yaml`, API docs, dashboard |
| `service.py` | the core: receive, store, tasks, send queue, delivery status, stats |
| `store.py` | SQLite (default `./rescuecodec-gateway.db`) |
| `radio/` | adapters: `meshtastic_radio.py` (TCP and USB serial), `emulator.py` |
| `connectors/` | `mqtt.py`, `cot.py`, `geojson.py` |
| `hub.py` | hands events to connectors on a worker thread |
| `docs/` | Swagger UI 5.32.1 (Apache-2.0), served at `/docs-assets` |
| `static/` | dashboard build (see `dashboard/`). A placeholder page shows while it is empty. |

## Tests

```bash
.venv/bin/python -m pytest tests/test_gateway_*.py       # unit and API tests, no hardware
scripts/e2e-gateway.sh                                   # two gateways on the simulator, MQTT and CoT observed, 3 runs
```

The API listens on localhost and has no password. If you open it to a network with `--host 0.0.0.0`, put it behind a reverse proxy with a password.
