#!/usr/bin/env bash
# Regenerate the Python protobuf classes from the spec.
# Usage: scripts/gen-proto.sh   (needs protoc, e.g. brew install protobuf)
set -euo pipefail
cd "$(dirname "$0")/.."
protoc -I spec --python_out=. --pyi_out=. spec/rescuecodec/v2/rescuecodec.proto
echo "wrote rescuecodec/v2/rescuecodec_pb2.py and .pyi"
