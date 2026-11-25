# @rescuecodec/core

TypeScript library for **RescueCodec v2**. It turns a rescue report into a tiny protobuf message (about 23 bytes) that fits on a Meshtastic LoRa radio. It turns the bytes back into a readable German or English line. It works fully offline.

Passes RescueCodec 2.0.0 conformance Level 1 and Level 2 (`spec/vectors/codec.json`, all cases, including `packed_text`). Old v1 packets can be read. Licence: Apache-2.0.

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
| `packText(text)`, `unpackText(bytes, 1)` | Raw DEFLATE with the shared text dictionary (see below). |
| `encodeCompact(text)`, `decodeCompact(bytes)` | Compact frame `0x02`: word tokens, no Envelope (spec section 3.7). |
| `encodeDomain(text, "sar")`, `decodeDomain(bytes)` | Domain frame `0x03` with a team vocabulary (see below). |

## Packed texts

A free text can travel packed: `encode({id: 3, text: {text: "We are at the hut, ...", dict: 1}})` packs the words with raw DEFLATE and the shared dictionary `spec/vocab/text-dict-v1.txt` (spec section 3.5). The example sentence goes from 81 to 34 bytes. `decode` always unpacks: you get `{text: {text: "...", dict: 1}}`, so `dict` tells you it came packed. A packed text with a dictionary this library does not know decodes as `unknown` (newer type). Unpacking stops at 1024 bytes (`too_large`); a broken, cut or trailing stream is `malformed`.

Packing and unpacking use [pako](https://github.com/nodeca/pako) 2.1.0 (MIT and Zlib licence), the only other runtime dependency. Its output is byte-identical to Python's zlib for the vectors, but the spec does not require that: any valid DEFLATE stream is fine. The dictionary is bundled in `src/text-dict-data.ts` (about 16 KB), and a test checks its SHA-256 against the vectors.

## Domain frames (team vocabularies)

A domain frame is `0x03`, one byte vocabulary id, then 10-bit codes (spec section 3.8). Five vocabularies are bundled: 1 Emergency, 2 Search & Rescue (`sar`), 3 Firefighting (`fire`), 4 Hiking / Mountain (`hiking`), 5 Medical / First aid (`medical`). Each has 1016 entries. A word in the vocabulary costs 10 bits. Other words escape to word dictionary 2 (26 bits). Names, numbers and emoji go as exact literals.

```ts
encodeDomain("Apply a tourniquet.", "medical");   // 03 05 e3 00 60 9c 00: 7 bytes (19 as plain text)
decodeAny(bytes);                                 // {v: 2, text: {text: "Apply a tourniquet.", vocab: 5}}
```

The text always comes back byte for byte. An id this library does not have (0, 6..255) or a vocabulary copy that fails its SHA-256 check decodes as `{unknown: {vocab: n}}`. `render` shows it as "unsupported vocabulary n", never as words. A cut or broken stream is `malformed`. This library has no sender: the gateway and the app pick the smallest form. The vocabularies are bundled in `src/domain-vocab-data.ts` (about 36 KB), and the tests check their SHA-256 and run `spec/vectors/domain.json`.

`CodecError` has a stable `code` (`empty`, `too_large`, `unsupported_version`, `malformed`, `no_body`, `multiple_bodies`, `unknown_key`, `invalid_value`, `missing_field`) and a `field` path such as `pos.lat`. The JSON shape and all rules are in `spec/SPEC.md`.

## Develop

```sh
npm install
npm run gen     # regenerate src/gen and the bundled data files (src/*-data.ts) from ../spec (needs protoc)
npm test        # conformance vectors with vitest
npm run build   # ESM and type definitions in dist/
```

The protobuf code is generated with `protoc-gen-es` and the official `@bufbuild/protobuf` runtime, so the bytes are canonical. Never edit the vectors to make a test pass.
