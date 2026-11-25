#!/usr/bin/env bash
# Full end-to-end scenario on the simulator: Android emulator app, 3 simulated radios, gateway, dashboard, MQTT, TAK CoT.
# Usage: scripts/e2e-full.sh [--runs 3] [--keep]
# Needs: Docker, Mosquitto (scripts/mqtt-up.sh), the debug APK (cd android && ./gradlew :app:assembleDebug),
# the dashboard build (cd dashboard && npm run build) and the playwright-cli command (npm i -g @playwright/cli,
# or set PLAYWRIGHT_CLI to its path). Simulator and emulator only. Starts and stops everything unless --keep.
set -uo pipefail
cd "$(dirname "$0")/.."
. scripts/env.sh
exec .venv/bin/python scripts/e2e_full.py "$@"
