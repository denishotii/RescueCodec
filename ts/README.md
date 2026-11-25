# @rescuecodec/core

TypeScript library for **RescueCodec v2**. It turns a rescue report into a tiny protobuf message (about 23 bytes) that fits on a Meshtastic LoRa radio. It turns the bytes back into a readable German or English line. It works fully offline.

Passes RescueCodec 2.0.0 conformance Level 1 and Level 2 (`spec/vectors/codec.json`, all cases). Old v1 packets can be read. Licence: Apache-2.0.

## Install

```sh
npm install @rescuecodec/core
```

Works in Node 18+ and in browsers (ESM). No network access, no files at runtime.

## Example

```ts
import { encode, decode, decodeAny, render } from "@rescuecodec/core";

const bytes = encode({
  id: 7,
  pos: { lat: 50.69212, lon: 7.21345 },
  found: { age: "child", consciousness: "conscious", injuries: ["hypothermia", "fracture"], needs: ["stretcher"], responders: 2 },
});
console.log(bytes.length);                  // 23 bytes. Send them on Meshtastic port 256 (PRIVATE_APP).
const msg = decode(bytes);                  // back to the same JSON
console.log(render(msg, "de"));             // "Person gefunden: Kind, ansprechbar, Unterkühlung, Knochenbruch, braucht Trage, 2 weitere Helfer benötigt. Position 50.69212, 7.21345."
console.log(render(decodeAny(bytes), "en")); // decodeAny also reads old v1 packets
```

## API

| Function | What it does |
|---|---|
| `encode(msg)` | JSON message to bytes (`Uint8Array`). Throws `CodecError`. |
| `decode(bytes)` | v2 bytes to JSON message. Throws `CodecError`. |
| `decodeAny(bytes)` | v1 or v2 bytes to JSON message. The result has `v: 1` or `v: 2`. |
| `render(msg, lang)` | Readable line. `lang` is `en`, `de` (reviewed), or `uk`, `tr`, `ar` (machine drafts). |
| `canonical(msg)` | Encode and decode, to normalise flag order and rounding. |
| `meshtasticPriority(msg)` | Suggested Meshtastic packet priority (critical 110, urgent 100, normal 70, routine 10). |
| `resolveTime("17:45", now)` | Full UTC date for a message time. |
| `PORTNUM` | 256, the Meshtastic port. |

`CodecError` has a stable `code` (`empty`, `too_large`, `unsupported_version`, `malformed`, `no_body`, `multiple_bodies`, `unknown_key`, `invalid_value`, `missing_field`) and a `field` path such as `pos.lat`. The JSON shape and all rules are in `spec/SPEC.md`.

## Develop

```sh
npm install
npm run gen     # regenerate src/gen and src/i18n-data.ts from ../spec (needs protoc)
npm test        # conformance vectors with vitest
npm run build   # ESM and type definitions in dist/
```

The protobuf code is generated with `protoc-gen-es` and the official `@bufbuild/protobuf` runtime, so the bytes are canonical. Never edit the vectors to make a test pass.
