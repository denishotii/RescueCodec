#!/usr/bin/env bash
# Stop the emulator started by emulator-up.sh. Safe if nothing is running.
. "$(dirname "$0")/env.sh"
if adb devices 2>/dev/null | grep -q '^emulator-'; then
  adb emu kill >/dev/null 2>&1 || true
  for _ in $(seq 1 20); do
    pgrep -f "qemu-system.*-avd rescue" >/dev/null 2>&1 || break
    sleep 1
  done
fi
pkill -f "qemu-system.*-avd rescue" >/dev/null 2>&1 || true
adb kill-server >/dev/null 2>&1 || true
echo "Emulator stopped."
