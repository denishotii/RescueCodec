#!/usr/bin/env bash
# Start the headless "rescue" emulator and wait until Android has booted.
# Safe to run twice: if an emulator is already booted, it just returns.
set -euo pipefail
. "$(dirname "$0")/env.sh"

AVD_NAME="${AVD_NAME:-rescue}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-300}"
LOG="${EMULATOR_LOG:-${TMPDIR:-/tmp}/rescue-emulator.log}"

booted() {
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]
}

adb start-server >/dev/null 2>&1 || true

if adb devices | grep -q '^emulator-.*device$' && booted; then
  echo "Emulator already running and booted."
  exit 0
fi

if ! pgrep -f "qemu-system.*-avd $AVD_NAME" >/dev/null 2>&1 && ! pgrep -f "emulator.*-avd $AVD_NAME" >/dev/null 2>&1; then
  echo "Starting emulator '$AVD_NAME' (log: $LOG)"
  nohup emulator -avd "$AVD_NAME" -no-window -no-audio -no-boot-anim -no-snapshot-save \
    -gpu swiftshader_indirect >"$LOG" 2>&1 &
else
  echo "Emulator process already exists, waiting for boot."
fi

adb wait-for-device
elapsed=0
until booted; do
  sleep 3
  elapsed=$((elapsed + 3))
  if [ "$elapsed" -ge "$BOOT_TIMEOUT" ]; then
    echo "Emulator did not boot within ${BOOT_TIMEOUT}s. See $LOG" >&2
    exit 1
  fi
done
echo "Emulator booted after about ${elapsed}s."
