#!/usr/bin/env bash
# Wednesday morning, before anything else. Read only: it opens no serial port, no Bluetooth, and transmits nothing.
# It checks that the Mac has everything: the gateway program, the dashboard, the map, the APK, adb, and which USB radios are plugged in.
# Usage: hil/preflight.sh
cd "$(dirname "$0")/.." || exit 1
bad=0
ok()   { printf 'PASS  %s\n' "$1"; }
warn() { printf 'WARN  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; bad=1; }

[ -x .venv/bin/rescuecodec-gateway ] && ok "gateway program: .venv/bin/rescuecodec-gateway" || fail "gateway program missing. Run: python3 -m venv .venv && .venv/bin/pip install -e '.[gateway]'"
.venv/bin/python -c "import meshtastic, serial" 2>/dev/null && ok "Python packages meshtastic and pyserial" || fail "meshtastic is missing. Run: .venv/bin/pip install -e '.[gateway]'"
[ -f rescuecodec/gateway/static/index.html ] && ok "dashboard build is in rescuecodec/gateway/static/" || fail "dashboard not built. Run: cd dashboard && npm install && npm run build"
ls rescuecodec/gateway/static/map/*.pmtiles >/dev/null 2>&1 && ok "offline map files are in place" || warn "no offline map. Run scripts/fetch-offline-assets.sh and npm run build in dashboard/ (the dashboard still works without a map)"

APK=dist/RescueCodec-debug.apk
if [ -f "$APK" ]; then ok "APK $APK ($(du -h "$APK" | cut -f1), sha256 $(shasum -a 256 "$APK" | cut -c1-12)...)"; else fail "APK missing: $APK. Build: . scripts/env.sh && (cd android && ./gradlew :app:assembleDebug) && mkdir -p dist && cp android/app/build/outputs/apk/debug/app-debug.apk $APK"; fi

. scripts/env.sh 2>/dev/null
if command -v adb >/dev/null 2>&1; then
  ok "adb found"
  phones=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  if [ -n "$phones" ]; then ok "phone on adb: $(echo $phones | tr '\n' ' ')"; else warn "no phone on adb. Plug it in, enable USB debugging, accept the prompt on the phone"; fi
else
  fail "adb not found. Run: . scripts/env.sh (or brew install android-platform-tools)"
fi

# Names only: ls /dev/cu.* does not open the ports.
radios=$(ls /dev/cu.usbserial-* /dev/cu.SLAB_USBtoUART* /dev/cu.wchusbserial* /dev/cu.usbmodem* 2>/dev/null)
n=$(echo "$radios" | grep -c . )
if [ "$n" = "1" ]; then ok "one USB radio: $radios  (use: rescuecodec-gateway --serial auto)"
elif [ "$n" = "0" ]; then warn "no USB radio found. Plug in the Heltec V3 with a data cable (not a charge-only cable)"
else warn "several USB devices:"; echo "$radios" | sed 's/^/        /'; echo "      pick one: rescuecodec-gateway --serial /dev/cu.usbserial-XXXX"; fi

for p in 8000; do
  lsof -nP -iTCP:$p -sTCP:LISTEN >/dev/null 2>&1 && warn "port $p is already in use (an old gateway?). Stop it, or use --port 8001" || ok "port $p is free"
done
exit $bad
