#!/bin/sh
# Regenerate src/gen (protobuf code), src/i18n-data.ts (word files) and src/text-dict-data.ts (text dictionaries)
# from ../spec.
set -e
cd "$(dirname "$0")/.."
protoc -I ../spec --plugin=protoc-gen-es=node_modules/.bin/protoc-gen-es \
  --es_out=src/gen --es_opt=target=ts,import_extension=js \
  ../spec/rescuecodec/v2/rescuecodec.proto
node scripts/gen-i18n.mjs
node scripts/gen-text-dict.mjs
