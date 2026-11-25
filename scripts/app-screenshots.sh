#!/usr/bin/env bash
# Takes screenshots of every screen of the Android app on the emulator, light and dark, English and German.
# Output: .overnight/gallery/app/<screen>-<light|dark>.png
#
#   scripts/app-screenshots.sh            builds nothing: install the debug APK first, or pass --build
#   scripts/app-screenshots.sh --build    runs gradle assembleDebug first
#   scripts/app-screenshots.sh --keep     leaves the emulator and the simulator running
#
# It starts the Meshtastic simulator and the emulator, connects the app to SIM-FIELD, lets SIM-BASE send
# demo reports (sim/demo_feed.py) and drives the app through uiautomator. Simulator only: no real radio.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
. "$ROOT/scripts/env.sh"
OUT="$ROOT/.overnight/gallery/app"
TMP="${TMPDIR:-/tmp}/rc-shots"
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
KEEP=0; BUILD=0
for a in "$@"; do [ "$a" = "--keep" ] && KEEP=1; [ "$a" = "--build" ] && BUILD=1; done
mkdir -p "$OUT" "$TMP"
PKG=org.rescuecodec.app
MODE=light

cleanup() {
  if [ "$KEEP" = "0" ]; then "$ROOT/scripts/emulator-down.sh" || true; "$ROOT/sim/down.sh" || true; fi
}
trap cleanup EXIT

[ "$BUILD" = "1" ] && (cd "$ROOT/android" && ./gradlew :app:assembleDebug -q)
"$ROOT/sim/up.sh" >/dev/null
"$ROOT/scripts/emulator-up.sh" >/dev/null
adb install -r "$APK" >/dev/null
for p in POST_NOTIFICATIONS RECORD_AUDIO ACCESS_FINE_LOCATION CAMERA; do adb shell pm grant $PKG android.permission.$p 2>/dev/null || true; done
"$ROOT/scripts/install-vosk-models.sh" >/dev/null 2>&1 || echo "(speech models not installed, mic shows as missing)"
adb shell cmd uimode night no >/dev/null
adb shell pm clear $PKG >/dev/null 2>&1 || true
for p in POST_NOTIFICATIONS RECORD_AUDIO ACCESS_FINE_LOCATION CAMERA; do adb shell pm grant $PKG android.permission.$p 2>/dev/null || true; done
"$ROOT/scripts/install-vosk-models.sh" >/dev/null 2>&1 || true
# A fake GPS fix so the position shows up in reports (Siebengebirge near Koeln).
adb emu geo fix 7.21345 50.69212 >/dev/null 2>&1 || true

ui() { rm -f "$TMP/ui.xml"; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true; adb pull /sdcard/ui.xml "$TMP/ui.xml" >/dev/null 2>&1 || true; }
tap_id() {
  local xy="" try
  for try in 1 2 3; do
    ui
    xy="$(python3 "$ROOT/scripts/ui_tap.py" "$TMP/ui.xml" "$@" 2>/dev/null)" && break
    xy=""; sleep 1
  done
  [ -n "$xy" ] || { echo "not found: $*" >&2; return 1; }
  adb shell input tap $xy
}
shot() { sleep "${2:-1}"; adb exec-out screencap -p > "$OUT/$1-$MODE.png"; echo "  $1-$MODE.png"; }
open_screen() { adb shell am start -n $PKG/.MainActivity --es screen "$1" >/dev/null; sleep 2; }

echo "Connecting the app to SIM-FIELD..."
adb shell am start -n $PKG/.MainActivity --es screen REPORT --es connect 10.0.2.2:4403 >/dev/null
sleep 14
echo "Sending demo reports from SIM-BASE..."
"$ROOT/.venv/bin/python" "$ROOT/sim/demo_feed.py" --mayday --gap 2.5 >/dev/null
# SIM-BASE listens first, then the app sends one report through the public Intent API. SIM-BASE answers
# like a team would: Accept from "Trupp 3", ETA 15 min.
"$ROOT/.venv/bin/python" "$ROOT/sim/demo_feed.py" --answer --wait 40 &
ANSWER_PID=$!
sleep 4
adb shell "am start -a org.rescuecodec.action.SEND_REPORT -p $PKG --es org.rescuecodec.extra.TEXT 'Child found, conscious, hypothermia, needs stretcher'" >/dev/null || true
wait $ANSWER_PID || echo "(no answer seen)"
sleep 3

for MODE in light dark; do
  echo "Mode: $MODE"
  [ "$MODE" = dark ] && adb shell cmd uimode night yes >/dev/null || adb shell cmd uimode night no >/dev/null
  sleep 2
  # Report: type a sentence, show the chips and the counter.
  open_screen REPORT
  tap_id report_clear 2>/dev/null || true; sleep 1
  tap_id report_text || true; sleep 1
  adb shell input text 'Child%sfound,%sconscious,%shypothermia,%sneed%sstretcher'
  sleep 2
  shot report-keyboard 1
  adb shell input keyevent 4; sleep 1
  shot report 1
  tap_id chip_type || true; shot report-type-sheet 1.5
  adb shell input keyevent 4; sleep 1
  tap_id chip_age || tap_id chip_priority || true; shot report-field-sheet 1.5
  adb shell input keyevent 4; sleep 1
  # Send it: the snackbar says how many bytes were queued, and the stats get a real saving.
  tap_id send_button || true; shot report-sent 0.8
  # The SOS strip half-way through a hold.
  ui; xy="$(python3 "$ROOT/scripts/ui_tap.py" "$TMP/ui.xml" sos_bar)"
  (adb shell input swipe ${xy% *} ${xy#* } ${xy% *} ${xy#* } 1700 &) ; sleep 1.1
  adb exec-out screencap -p > "$OUT/sos-hold-$MODE.png"; echo "  sos-hold-$MODE.png"; sleep 2.5
  open_screen ACTIONS
  shot actions 1
  adb shell input swipe 540 1500 540 500 300; shot actions-more 1
  open_screen FEED;  shot feed 1
  open_screen STATS; shot stats 1
  open_screen RADIO; shot connect 1
  adb shell input swipe 540 1800 540 600 300; shot connect-more 1
  open_screen SETTINGS; shot settings 1
  adb shell input swipe 540 1800 540 600 300; shot settings-more 1

done

# German pass (light): switch language in Settings (the header toggle is gone).
MODE=light; adb shell cmd uimode night no >/dev/null; sleep 1
open_screen REPORT
open_screen SETTINGS
tap_id lang_de || true; sleep 1
open_screen REPORT
shot report-de 1
open_screen ACTIONS; shot actions-de 1
open_screen FEED; shot feed-de 1
open_screen STATS; shot stats-de 1
open_screen REPORT
open_screen SETTINGS
tap_id lang_en || true

echo "Screenshots in $OUT"
ls "$OUT" | wc -l
