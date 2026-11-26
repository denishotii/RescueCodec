# Meshtastic simulator (sim/)

Three real Meshtastic firmware nodes (`meshtasticd`) run in Docker with a simulated radio. They form one mesh. Each node offers the normal Meshtastic TCP API, so the gateway and the Android app speak the same protocol as with a real radio.

| Node | Host port | Name |
|---|---|---|
| 1 | 4403 | SIM-FIELD |
| 2 | 4404 | SIM-BASE |
| 3 | 4405 | SIM-RELAY |

Android emulator: use host `10.0.2.2` and the same ports. Python: `TCPInterface("127.0.0.1", portNumber=4404)`.

## Use

```bash
sim/up.sh                    # start, configure, verify (safe to run again)
.venv/bin/python sim/smoke.py   # FIELD -> BASE and RELAY, PRIVATE_APP 256 + text
.venv/bin/python sim/demo_feed.py   # demo reports from BASE for the app on FIELD (--mayday, --answer)
sim/down.sh                  # stop; add --wipe to delete node state
```

## How the nodes talk without LoRa

Image: `meshtastic/meshtasticd:2.7.26.54e0d8d` (stable 2.7 line, arm64 works natively on the M1). The tag is pinned in `channel.env`.

Each node uses `Lora: Module: sim` (the firmware's SimRadio) plus `EnableUDP: true`. The firmware then sends every "radio" packet as a UDP multicast datagram on group `224.0.0.69` (2.7.x). All containers share one Docker bridge network, so every node hears every other node. This is the "mesh over LAN" feature of the firmware. It runs the real routing, encryption, channel and node database code. Only the antenna is replaced.

Why this and not Meshtasticator: Meshtasticator's SimRadio uses a Python bridge and a different build. The UDP way needs only the official image, no extra process, and about 5 MB of RAM per node (limit 256 MB each).

Things to know:
- Do not use `meshtasticd --sim`. It disables PKI encryption on sent packets. We use `Module: sim` in YAML instead.
- Each node has a fixed MAC (`02:52:43:00:00:0N`), so node IDs stay the same after `down.sh` / `up.sh`.
- The multicast group is joined once at start. Nodes only hear each other on the same firmware line (2.7.x uses 224.0.0.69, 2.8 uses 239.0.0.69). Keep the image tag the same for all nodes.
- All nodes hear each other directly (no radio range). It tests protocol and app code, not RF range or airtime. Real devices are checked in `hil/`.
- Ports bind to 127.0.0.1 only.

## Configuration (same as our real setup)

`configure.py` sets, over the TCP API: region `EU_868`, preset `LONG_FAST`, primary channel `RescueCodec` with the PSK from `channel.env`, and the owner names. The nodes restart once after the first run. Later runs change nothing.

`channel.env` holds a fixed PSK for simulation only. It is public. Never put it on a real radio. Real radios get their own key from the app (QR code or URL).

## Smoke test

`smoke.py` makes SIM-FIELD broadcast a PRIVATE_APP (port 256) packet with known bytes plus a text message on the channel. SIM-BASE and SIM-RELAY must receive the exact same bytes. The tail of the payload is random per run, so old packets cannot pass the test. It resends every 8 seconds for up to 90 seconds. Exit code 0 is a pass.

## Troubleshooting

- Port in use: stop other Meshtastic sessions or run `sim/down.sh`.
- Nodes do not hear each other: run `sim/down.sh --wipe`, then `sim/up.sh`.
- Logs: `docker logs rc-sim-base`.
