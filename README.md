# RescueCodec

**Transmit meaning, not words.** RescueCodec is a local hackathon prototype for converting an emergency report into a compact, versioned binary packet, moving it over a transport abstraction, and tracking whether a responder accepted responsibility.

This is a synthetic demonstration, not a certified emergency or medical system. The default mesh is an in-memory emulator and does not transmit RF.

## Run locally

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -e '.[test]'
uvicorn rescuecodec.api:app --host 127.0.0.1 --port 8000
```

Open http://127.0.0.1:8000. Choose **Extract meaning**, check or correct fields, then send from A to C. Accept responsibility and mark the task completed in the inbox. The A–B–C path and link partition controls are synthetic.

For offline Ollama extraction, set `RESCUECODEC_OLLAMA_MODEL` to an already installed model name before starting the app. If the endpoint/model is unavailable, the API reports that it used a conservative deterministic fallback. A structured preview is editable before sending. Latitude and longitude can be entered separately; text such as “I am here” does not fabricate coordinates. Unsupported reports can use `/api/text` (single-packet text fallback).

## Protocol v1

All multi-byte integers use network byte order. The application packet is capped at 200 bytes by default. `codec_id` and `schema_id` are separate namespaces.

| Offset | Size | Meaning |
|---:|---:|---|
| 0 | 1 | Protocol version (`1`) |
| 1 | 1 | Codec (`0` UTF-8, `1` semantic report, `7` confirmation) |
| 2 | 1 | Schema (`6` person found; `0` for text/confirmation) |
| 3 | 4 | Request/message ID, unsigned |
| 7 | 1 | Four 2-bit enums: consciousness, breathing, urgency, location quality (high-to-low bits) |
| 8 | 1 | Equipment bit mask (bits 0–7 follow `Equipment` enum order) |
| 9 | 1 | Assistance request enum |
| 10 | 1 | Flags: bit 0 coordinates, bit 1 Unix timestamp, bit 2 found-person count, bit 3 minimum additional responders; upper bits reserved |
| 11 | 0/8 | Optional signed latitude and longitude integers, degrees × 100,000 |
| 11 or 19 | 0/4 | Optional Unix timestamp, unsigned 32-bit |
| After preceding optionals | 0/1 | Optional found-person count (1–15), then minimum additional responders (1–15), in that order |

The semantic packet is 11 bytes without optional fields, 19 with coordinates, 15 with timestamp, or 25 with all four optional fields (coordinates, timestamp, people found, and minimum additional responders). Each count is one byte with valid values 1–15. The counts have distinct meanings: `person_count` counts found people; `responders_needed_min` is the minimum additional team size requested. Enum value zero means unknown where defined. Coordinate quantization is about 1.11 m north/south per step; east/west spacing scales by cosine(latitude). This is encoding precision, not GPS measurement accuracy. `confirmation` is 8 bytes: 3-byte common header, 4-byte request ID, and 1-byte status (`1` accepted, `2` completed). Text fallback is common header + 4-byte message ID + UTF-8. Unsupported versions, codec/schema pairs, flags and malformed lengths are rejected.

Prototype schema IDs 1, 2, 3, and 6 are project-local identifiers, not official emergency standards. Current wire encoding implements schema 6.

## Measurements

Run the reproducible synthetic benchmark:

```bash
rescuecodec-benchmark
```

It writes `results/benchmark.json`, per-case `results/benchmark.csv`, a 42-case synthetic ground-truth set, and `results/compression.svg`. Binary length is measured on ground-truth structured data; semantic accuracy is reported separately for the deterministic offline fallback. For local model evaluation, start Ollama, set `RESCUECODEC_OLLAMA_MODEL=llama3:8b`, then run `python -m rescuecodec.benchmark.llm_eval`. Current results are saved in `results/llama3_8b_evaluation.json` and `.csv`. The reported 100% exact match is on 31 synthetic schema-supported reports and uses deterministic validation/evidence grounding; it is not a real-world accuracy claim. Airtime is estimated with the LoRa symbol-time equation (SF11, 250 kHz, CR 4/5 defaults) and an explicit 16-byte radio-overhead assumption. It is not measured packet airtime. Synthetic mesh timings/loss are not physical measurements.

## Transport and radio safety

`Transport` defines raw-byte send, subscription, lifecycle, and capability reporting. `MeshEmulator` supports configurable topology, latency, loss, duplicates, node availability, deterministic seed, and multihop routing. `MeshtasticTransport` is optional and imported lazily. The dashboard's **Start USB receive gateway** opens the connected radio and forwards only valid RescueCodec schema 6 packets into the laptop mesh; it does not send or change radio configuration. Stop any other process using the serial device (such as `receiver.py`) first. This is an application-layer bridge, not RF connectivity for virtual nodes. The virtual mesh has no radio, and the laptop does not retransmit virtual traffic over RF. A separate transmitting physical node and an authorized test channel are needed to verify actual over-the-air ingress. No physical RF transmission was performed.

The separate `Meshtasticator` checkout has existing uncommitted user changes. Its fixes were preserved. Docker access remains blocked in this environment, so simulator runtime paths remain unverified. The local Ollama service and llama3:8b were subsequently started and tested on synthetic reports.

## Current limits

- Fallback extraction is intentionally small and rule based; it is not a substitute for a configured local model or human review.
- The fixed semantic codec models one found person and a small enumerated field set. Unsupported or multi-person reports should fall back to text.
- The emulator routes packets over graph paths and applies synthetic packet loss, but is not a detailed LoRa propagation, collision, regulatory, or routing-stack simulation.
- Fragmentation is provided as a bounded utility but the browser demo currently sends single-packet semantic reports only.
- Audio and image codecs, physical gateway, real radio measurements, broad localization, and schema evolution tooling are not implemented yet.
