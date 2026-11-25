#!/usr/bin/env bash
# Stop and remove the simulator containers. Use --wipe to also delete node state.
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./channel.env; set +a
docker compose down --remove-orphans
if [ "${1:-}" = "--wipe" ]; then
  # node files are written by root inside the container
  docker run --rm -v "$PWD/data:/d" alpine sh -c 'rm -rf /d/*' 2>/dev/null || rm -rf data/*
  echo "state wiped"
fi
