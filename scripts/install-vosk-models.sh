#!/usr/bin/env bash
# Copy the Vosk speech models to a phone or emulator over adb. No internet needed.
#
#   scripts/install-vosk-models.sh            both languages
#   scripts/install-vosk-models.sh de         one language (de or en)
#   ANDROID_SERIAL=<serial> scripts/...       pick a device when several are connected
#
# The models come from assets-offline/vosk (scripts/fetch-offline-assets.sh downloads them once).
# Where they go:
#   1. A debug build (adb install of the debug APK): into the app's private folder with run-as.
#      Always works, needs no storage access.
#   2. Otherwise: /sdcard/Android/data/org.rescuecodec.app/files/vosk/<lang>/ (some Android 11+
#      phones hide files that adb wrote there; if the app says "Missing", use Settings > Import).
# Install the app first. No phone and no computer? Copy the model zip to the phone and use
# Settings > Import model in the app. See docs/VOICE.md.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
. "$ROOT/scripts/env.sh"
SRC="$ROOT/assets-offline/vosk"
DEST="/sdcard/Android/data/org.rescuecodec.app/files/vosk"
LANGS=("${@:-de en}")
[ "$#" -eq 0 ] && LANGS=(de en)

adb get-state >/dev/null 2>&1 || { echo "No phone or emulator connected (adb devices)." >&2; exit 1; }
adb shell pm path org.rescuecodec.app >/dev/null 2>&1 || { echo "Install the app first (adb install ...)." >&2; exit 1; }

for lang in "${LANGS[@]}"; do
  case "$lang" in
    de) dir="$SRC/vosk-model-small-de-0.15" ;;
    en) dir="$SRC/vosk-model-small-en-us-0.15" ;;
    *) echo "Unknown language $lang (use de or en)" >&2; exit 1 ;;
  esac
  [ -f "$dir/am/final.mdl" ] || { echo "Model for $lang not found in $dir. Run scripts/fetch-offline-assets.sh first." >&2; exit 1; }
  echo "Copying $lang model ($(du -sh "$dir" | cut -f1))..."
  TMP="/data/local/tmp/rc-vosk-$lang"
  adb shell "rm -rf $TMP" && adb push "$dir" "$TMP" >/dev/null
  if adb shell "run-as org.rescuecodec.app sh -c 'mkdir -p files/vosk && rm -rf files/vosk/$lang && cp -r $TMP files/vosk/$lang'" >/dev/null 2>&1; then
    echo "  done: app private folder files/vosk/$lang"
  else
    adb shell "rm -rf $DEST/$lang && mkdir -p $DEST && cp -r $TMP $DEST/$lang"
    echo "  done: $DEST/$lang (release build: if the app still says Missing, use Settings > Import)"
  fi
  adb shell "rm -rf $TMP"
done
echo "Open the app. The mic button turns on once the model has loaded (a few seconds)."
