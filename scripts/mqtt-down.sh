#!/usr/bin/env bash
# Stop the local Mosquitto broker started by mqtt-up.sh.
PID=/tmp/rescuecodec-mosquitto.pid
if [ -f "$PID" ] && kill -0 "$(cat $PID)" 2>/dev/null; then kill "$(cat $PID)"; echo "mosquitto stopped"; else echo "mosquitto not running"; fi
rm -f "$PID"
