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

## Preview a typed sentence (`POST /v2/extract`)

The dashboard's Send panel calls this while you type. It reads one German or English sentence with the rule-based extractor and shows the report it would send. Nothing is sent.

```bash
curl -s -X POST localhost:8000/v2/extract -H 'content-type: application/json' \
  -d '{"text":"Kind gefunden, ansprechbar, unterkühlt. Wir brauchen eine Trage."}'
# -> {"type":"found","message":{"found":{"age":"child",...}},"fields":[{"field":"found.age","code":"child","text":{"en":"child","de":"Kind"}},...],
#     "coded_bytes":10,"text_bytes":65,...}
```

- `message` is ready for `POST /v2/messages` (the gateway adds the id). When no report fits, it is a `text` with your words.
- `coded_bytes` is the size on air of that message, `text_bytes` the size of the same words as a plain text.
- `fields` are the facts as short labels in English and German (the chips in the dashboard).
- Optional `lang` (`en` or `de`). At most 1000 characters. It needs the token like every `/v2` call, and it does not count toward `--tx-per-minute`.

## Channels and direct messages

The gateway listens on **every private channel** of its radio, on port 256 (reports) and port 1 (texts). It also keeps every direct message to its own node. Public channels (no key or the default key, like LongFast) are ignored unless you start it with `--show-public`.

Every record says where it came from or went to:

```json
"channel": {"index": 2, "name": "draeger-srs", "public": false}, "to": null
```

`to` is null for a broadcast. For a direct message it is the other node (sent) or our node (received). Tasks have a `channel` too, and an Accept always goes out on the task's channel.

| Flag | Default | What it does |
|---|---|---|
| `--channels all\|NAME,NAME` | `all` | Channels to listen on, by name. The default channel is always included. Env `RESCUECODEC_CHANNELS`. |
| `--show-public` | off | Also show broadcasts on public channels. Env `RESCUECODEC_SHOW_PUBLIC=1`. |
| `--channel-name NAME` | `RescueCodec` | The default channel for sending. |
| `--channel N` | | Force the default channel by index. |

```bash
# A text on the draeger-srs channel
curl -s -X POST localhost:8000/v2/text -H 'content-type: application/json' -d '{"text":"Trage kommt","channel":"draeger-srs"}'
# A direct message to one radio (packed if that radio runs RescueCodec, else a normal Meshtastic text)
curl -s -X POST localhost:8000/v2/messages -H 'content-type: application/json' -d '{"message":{"text":{"text":"Bitte melden"}},"to":"!a1b2c3d4"}'
# Only one channel
curl -s 'localhost:8000/v2/messages?channel=draeger-srs'
curl -s 'localhost:8000/v2/tasks?channel=RescueCodec'
# Which channels does the gateway hear?
curl -s localhost:8000/v2/health | jq .radio.channels
```

Reports never go out on a public channel (400). An unknown channel name gives 400. A database from an older gateway is migrated on start: old records get the default channel.

## Normal Meshtastic texts

The gateway also shows normal Meshtastic texts (port 1), for example from the official Meshtastic app or a radio with a keyboard:

- Only texts on the channels we listen on (every private channel) and direct messages to the gateway's radio. The public LongFast chat is not shown without `--show-public`.
- They are normal `text` records with `codec.version: 0`. `codec.hex` is the UTF-8 text. They go to the dashboard, the WebSocket, MQTT and TAK like every other record.
- The same packet twice is shown once (same sender and Meshtastic packet id). Readable copies that start with `[RC] ` (from `--text-over-air` or `also_plain`) are skipped: we already have the report from port 256.

## Packed texts (smaller, for RescueCodec only)

A text you send (`POST /v2/text`, or `POST /v2/messages` with a `text` body) goes out one of two ways:

- **Packed for RescueCodec** (port 256): the words are compressed with a shared dictionary, about 40 % smaller, with id and position. "We are at the hut, two people with us, waiting for the vehicle on the forest road" is 81 bytes as a normal text and 42 bytes packed. Stock Meshtastic apps cannot read it.
- **Normal Meshtastic text** (port 1): only the words, at most 200 bytes. The official app and radio screens show it.

The gateway picks per chat (a channel, or a direct chat with one node). It remembers for 24 hours who it heard: a node that sent any RescueCodec packet runs RescueCodec; a node that sent normal texts on a channel and no RescueCodec packet is a Meshtastic user there.

| Mode | A channel | A direct chat |
|---|---|---|
| `auto` (default) | Packed if no Meshtastic user was heard on it for 24 h, else normal | Packed if the peer runs RescueCodec, else normal |
| `compressed` | Packed | Packed |
| `normal` | Normal | Normal |

Public channels always get normal text. A text is packed only when the packet is at least 3 bytes smaller than the normal text, so "Ok" stays normal. Both ways use the same queue, priority, airtime budget and `want_ack`. A received packed text shows its words like any other text (dashboard, MQTT, TAK, GeoJSON), with `codec.packed: true`.

| Flag | Default | What it does |
|---|---|---|
| `--text-mode auto\|compressed\|normal` | `auto` | Mode of chats that have no mode of their own. Env `RESCUECODEC_TEXT_MODE`. |
| `--text-as-meshtastic on\|off` | `on` | Old setting. `off` now means: channels are `compressed`. |

```bash
# How does a text on the RescueCodec channel go out now, and why?
curl -s localhost:8000/v2/channels/RescueCodec/mode
# {"channel":"RescueCodec","mode":"auto","send_as":"normal","reason":"meshtastic_users","meshtastic_users":["!0000e003"],...}
# Always packed on this channel (stored, survives a restart); null = back to the default
curl -s -X PUT localhost:8000/v2/channels/RescueCodec/mode -H 'content-type: application/json' -d '{"mode":"compressed"}'
# Direct chat with one radio
curl -s -X PUT localhost:8000/v2/nodes/!a1b2c3d4/mode -H 'content-type: application/json' -d '{"mode":"normal"}'
# A text
curl -s -X POST localhost:8000/v2/text -H 'content-type: application/json' -d '{"text":"Treffpunkt jetzt am Gasthaus"}'
```

`GET /v2/health` shows `mode` and `send_as` for every channel, and `GET /v2/nodes` marks nodes that run RescueCodec (`rescuecodec: true`). Rules and numbers: SPEC sections 2.3 and 3.5.

Each received record carries the radio facts: `radio.rssi`, `radio.snr` and `radio.hops` (hop start minus hop limit). Simulated and emulated radios have no signal, so those stay `null`.

Sending is asynchronous. A record goes `queued`, then `sent`, then `delivered` or `failed`. Here `delivered` means "a neighbouring radio repeated it", not "a team received it". The dashboard says "Relayed by a neighbour". Only the Accept from a person proves that someone got it. Mayday goes first. The airtime budget is 10 percent per hour. When it is used up, you get `429` with `retry_after_s`. A Mayday is never held back.

About `failed` with `max_retransmit`: for a broadcast, Meshtastic counts a packet as delivered when it hears a neighbour repeat it. If no radio in range repeats packets, the status ends as `failed` even though the other side got it. The API keeps the status `failed` with error `max_retransmit` (that is the contract). The dashboard words it as "no relay confirmed" and does not count it as a failure. The Accept that comes back is the real proof.

## Connectors

| Flag | What you get |
|---|---|
| `--mqtt HOST[:PORT]` | JSON on `rescuecodec/v2/messages`, `rescuecodec/v2/<type>` (found, track, sector, request, hazard, mayday, ack, text), `rescuecodec/v2/tasks`, `rescuecodec/v2/readable` (one plain English line) and `rescuecodec/v2/status` (online or offline). Payloads are the same JSON as the API. |
| `--cot` | TAK events as UDP multicast to `239.2.3.1:6969` (change with `--cot-udp`, pick the network card with `--cot-iface`). |
| `--cot-tcp HOST:PORT` | The same events to a TAK server over TCP. Implies `--cot`. |
| `--text-over-air out\|in\|both` | Also post the readable line of each report on the normal Meshtastic text port, so stock apps show it. Off by default because it doubles airtime. `out` is what this gateway sends, `in` is what it hears. The line starts with `[RC] `, so RescueCodec apps and gateways skip the copy. Plain texts are never copied. |
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
| `extract_api.py` | `POST /v2/extract`: a typed sentence -> the report it would send (preview, sends nothing) |
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

## Who may talk to the gateway

- **Localhost only by default.** The gateway refuses to start with `--host 0.0.0.0` (or any non-loopback address) unless you also pass `--token SECRET` (or `RESCUECODEC_TOKEN`).
- **Token.** With a token, every `/v2` call needs `Authorization: Bearer SECRET`. The WebSocket and the dashboard take `?token=SECRET`, for example `http://192.168.1.20:8000/?token=SECRET`. The dashboard keeps it for the tab.
- **WebSocket origin.** Browsers do not apply CORS to WebSockets, so the gateway checks the `Origin` itself. A web page may connect only from localhost, from `--cors-origin`, or, with a valid token, from the gateway's own address. Programs that send no `Origin` are fine. Without this check any page you open could read your rescue messages.
- **Rate limit.** The API queues at most 30 messages per minute (`--tx-per-minute`, 0 = no limit). More gives `429 rate_limited`.
- **Channels.** The gateway listens on every private channel (`--channels`) and on direct messages to its node. Public channels stay out unless `--show-public`. The default channel for sending is the one named `RescueCodec`, else the only channel with its own key (`--channel-name`, or `--channel N` to force an index). If there is none, sending without a `channel` is refused with 503 and `/v2/health` shows `radio.channel_error`. Port 256 packets from channels we do not listen on are dropped and counted in `radio.other_channel`. Reports never go out on a public channel.
- Anyone who holds the channel key can send as a team member. The key is the trust boundary.
