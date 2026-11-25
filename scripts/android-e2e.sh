#!/usr/bin/env bash
# Android end-to-end test against the Meshtastic simulator.
#
#   scripts/android-e2e.sh            run everything, then stop the emulator and the simulator
#   scripts/android-e2e.sh --keep     leave them running afterwards
#
# What it does: starts the 3 simulated radios (sim/up.sh) and the Android emulator, starts the Python
# peer on SIM-BASE (sim/android_e2e_peer.py), then runs all instrumented tests of android/app. The
# test SimulatorE2ETest sends a Found report from the app over TCP (10.0.2.2:4403 = SIM-FIELD); the
# peer must receive identical bytes, answers Accept and Done, sends its own report, and must receive
# the app's Accept back.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
. "$ROOT/scripts/env.sh"
KEEP=0
[ "${1:-}" = "--keep" ] && KEEP=1
LOGS="${E2E_LOG_DIR:-${TMPDIR:-/tmp}/rescuecodec-e2e}"
mkdir -p "$LOGS"
PEER_PID=""

cleanup() {
  status=$?
  [ -n "$PEER_PID" ] && kill "$PEER_PID" 2>/dev/null || true
  if [ "$KEEP" = "0" ]; then
    "$ROOT/scripts/emulator-down.sh" || true
    "$ROOT/sim/down.sh" || true
  fi
  exit $status
}
trap cleanup EXIT

"$ROOT/sim/up.sh"
"$ROOT/scripts/emulator-up.sh"

rm -f "$LOGS/peer.ready"
"$ROOT/.venv/bin/python" "$ROOT/sim/android_e2e_peer.py" --ready-file "$LOGS/peer.ready" >"$LOGS/peer.log" 2>&1 &
PEER_PID=$!
for _ in $(seq 1 60); do
  [ -f "$LOGS/peer.ready" ] && break
  kill -0 "$PEER_PID" 2>/dev/null || { echo "peer died"; cat "$LOGS/peer.log"; exit 1; }
  sleep 1
done
[ -f "$LOGS/peer.ready" ] || { echo "peer not ready"; cat "$LOGS/peer.log"; exit 1; }

set +e
(cd "$ROOT/android" && ./gradlew :app:connectedDebugAndroidTest)
TEST_STATUS=$?
set -e

# The peer finishes when it has seen the app's Accept. Give it a moment, then report.
for _ in $(seq 1 20); do kill -0 "$PEER_PID" 2>/dev/null || break; sleep 1; done
PEER_STATUS=1
if ! kill -0 "$PEER_PID" 2>/dev/null; then
  wait "$PEER_PID" && PEER_STATUS=0 || PEER_STATUS=$?
  PEER_PID=""
fi
echo "---- peer log ($LOGS/peer.log) ----"
cat "$LOGS/peer.log"
echo "---- tests: gradle exit $TEST_STATUS, peer exit $PEER_STATUS ----"
[ "$TEST_STATUS" = "0" ] && [ "$PEER_STATUS" = "0" ]
