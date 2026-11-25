#!/usr/bin/env bash
# Takes the dashboard screenshots for docs/screenshots/dashboard/.
# Needs: a running gateway or mock (default http://127.0.0.1:8787) and playwright-cli on PATH.
# Usage: scripts/gallery.sh [base-url]
set -euo pipefail
BASE="${1:-http://127.0.0.1:8787}"
OUT="$(cd "$(dirname "$0")/../.." && pwd)/docs/screenshots/dashboard"
mkdir -p "$OUT"
shot() { # name width height query
  playwright-cli resize "$2" "$3" >/dev/null
  playwright-cli goto "$BASE/?$4" >/dev/null
  sleep "${WAIT:-5}"
  playwright-cli screenshot --filename="$OUT/$1.png" >/dev/null
  echo "wrote $1.png"
}
for theme in dark light; do
  shot "${PREFIX:-}1920x1080-$theme" 1920 1080 "theme=$theme"
  shot "${PREFIX:-}1440x900-$theme" 1440 900 "theme=$theme"
done
