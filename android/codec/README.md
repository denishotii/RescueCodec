# rescuecodec :codec (Kotlin/JVM)

Pure JVM library for the RescueCodec v2 wire format. No Android code. Apache-2.0.

Passes RescueCodec 2.3.0 conformance Level 1 (codec, v1 reading) and Level 2 (readable text), including compact frames (0x02, `TextWords`) and domain frames with team vocabularies (0x03, `TextDomain`, spec section 3.8).

```kotlin
val bytes = RescueCodec.encode(mapOf(
    "id" to 7,
    "pos" to mapOf("lat" to 50.69212, "lon" to 7.21345),
    "found" to mapOf("age" to "child", "needs" to listOf("stretcher")),
))                                              // ByteArray, send on port 256
val msg = RescueCodec.decodeAny(bytes)          // v2 or old v1 packets
RescueCodec.reconstruct(msg, "de")              // "Person gefunden: Kind, braucht Trage. Position 50.69212, 7.21345."
```

- A message is a plain `Map<String, Any?>` in the canonical JSON shape of `spec/SPEC.md` section 4. `Json` converts it to text and back.
- Bad input throws `CodecException` with a stable `code` (`malformed`, `invalid_value`, ...) and a `field` path.
- The protobuf classes are generated from `spec/rescuecodec/v2/rescuecodec.proto` (lite runtime). The word files for readable text come from `spec/i18n`.
- Word dictionaries and the five team vocabularies are copied from `spec/vocab` at build time and checked by SHA-256 when loaded. `RescueCodec.encodeDomain(text, "medical")` builds a domain frame; `decodeAny` reads it. A vocabulary the library does not have decodes to `{"unknown": {"vocab": n}}` ("unsupported vocabulary"), never to words.
- Test: `./gradlew :codec:test` runs every case in `spec/vectors/codec.json` and `spec/vectors/domain.json` (the same bytes as Python).
