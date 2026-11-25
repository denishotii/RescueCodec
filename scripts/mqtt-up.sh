#!/usr/bin/env bash
# Start a local Mosquitto broker on 127.0.0.1:1883 only (foreground-free, pid file in /tmp).
set -euo pipefail
PID=/tmp/rescuecodec-mosquitto.pid
CONF=/tmp/rescuecodec-mosquitto.conf
BIN=$(command -v mosquitto || echo /opt/homebrew/sbin/mosquitto)
if [ -f "$PID" ] && kill -0 "$(cat $PID)" 2>/dev/null; then echo "mosquitto already running (pid $(cat $PID))"; exit 0; fi
cat > "$CONF" <<CFG
listener 1883 127.0.0.1
allow_anonymous true
persistence false
pid_file $PID
CFG
nohup "$BIN" -c "$CONF" >/tmp/rescuecodec-mosquitto.log 2>&1 &
echo $! > "$PID"
sleep 1
kill -0 "$(cat $PID)" && echo "mosquitto up on 127.0.0.1:1883 (pid $(cat $PID))"
