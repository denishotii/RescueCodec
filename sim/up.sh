#!/usr/bin/env bash
# Start the 3 simulated Meshtastic nodes and configure them (idempotent).
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"
PY="${PYTHON:-$ROOT/.venv/bin/python}"
set -a; . ./channel.env; set +a
mkdir -p data/field data/base data/relay
docker image inspect "$MESHTASTICD_IMAGE" >/dev/null 2>&1 || docker pull "$MESHTASTICD_IMAGE"
docker compose up -d
for p in 4403 4404 4405; do
  for _ in $(seq 1 60); do nc -z 127.0.0.1 "$p" 2>/dev/null && break; sleep 1; done
done
"$PY" configure.py
echo "Simulator ready: SIM-FIELD :4403, SIM-BASE :4404, SIM-RELAY :4405 (Android emulator: 10.0.2.2)"
