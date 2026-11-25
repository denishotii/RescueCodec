#!/usr/bin/env bash
# End-to-end test of two gateways on the simulator, with MQTT and TAK CoT observed. Starts and stops everything.
# Usage: scripts/e2e-gateway.sh [--runs 3]      (needs Docker; no real radio is touched)
set -uo pipefail
cd "$(dirname "$0")/.."
PY="${PYTHON:-.venv/bin/python}"
STARTED_SIM=0; STARTED_MQTT=0
if ! (docker ps --format '{{.Names}}' | grep -q '^rc-sim-field$'); then STARTED_SIM=1; fi
if ! (nc -z 127.0.0.1 1883 2>/dev/null); then STARTED_MQTT=1; fi
cleanup() {
  [ "$STARTED_SIM" = 1 ] && sim/down.sh >/dev/null 2>&1 && echo "simulator stopped"
  [ "$STARTED_MQTT" = 1 ] && scripts/mqtt-down.sh
}
trap cleanup EXIT
sim/up.sh >/dev/null || { echo "simulator did not start"; exit 2; }
scripts/mqtt-up.sh >/dev/null || { echo "mosquitto did not start"; exit 2; }
"$PY" scripts/e2e_gateway.py "$@"
