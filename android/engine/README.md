# rescuecodec :engine (Kotlin/JVM)

Pure JVM library on top of `:codec`. Apache-2.0. No network, no Android code.

| Class | What it does |
|---|---|
| `Extractor` | Spoken or typed German/English report to one message. Uses `spec/vocab/sar.yaml` (bundled as JSON at build time). Handles negation, "unklar", number words, typos and spoken coordinates. Offers the text fallback when a sentence cannot be coded safely. |
| `SendQueue` + `AirtimeBudget` | Sends Mayday first, then Found/Request/Ack, Hazard, Sector, Track, Text. Keeps the EU_868 10% duty cycle (360 s per hour) using real LoRa airtime for LONG_FAST (or another preset) plus 23 bytes of Meshtastic overhead. Each type has its own limit as a share of the budget (Mayday and Ack never held, Found/Request/Hazard 100%, Sector 90%, Track/Text 70%), the same as the Python engine. |
| `RequestTracker` | Task states `OPEN`, `ACCEPTED`, `DECLINED`, `DONE`, `DELIVERY_UNKNOWN`. Dedupes by (sender node, id). Builds Ack messages. |

```kotlin
val result = Extractor().extract("Kind gefunden, ansprechbar, stark unterkühlt. Wir brauchen eine Trage.")
// result.message = {found: {age: child, consciousness: conscious, injuries: [hypothermia], needs: [stretcher]}}
// The app adds id and device GPS, the person checks the fields, then:
val queue = SendQueue()
queue.enqueue(result.message + ("id" to 7))
queue.poll()?.bytes   // next packet that fits the airtime budget, or null
```

Extraction score on `spec/vectors/extraction.json`: 70 of 70 (42 German, 28 English, 10 text fallbacks). The Kotlin extractor is a port of the Python one (`rescuecodec/engine/extract.py`): both give the same fields on the shared sentences, and they agreed on 900 random sentences when it was ported. Also see the held-out sentences in `ExtractorTest`.

Test: `./gradlew :engine:test`.
