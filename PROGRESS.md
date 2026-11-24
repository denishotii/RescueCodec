# RescueCodec progress

Updated: 2026-11-24

## Completed

- Inspected the starter workspace, scripts, Python 3.13.2 environment, and Meshtasticator checkout without overwriting existing files.
- Confirmed the RescueCodec directory has no Git metadata. Meshtasticator's existing `lib/interactive.py` edit and two backups remain untouched.
- Added a modular package, schema 6 validation, versioned bit-packed binary codec, optional GPS/timestamp/found-person count/additional-responder minimum fields, strict parsing, deterministic English/German reconstruction, and application confirmation packets.
- Added local Ollama JSON-schema extraction with a deterministic unsupported-content gate and evidence grounding for critical fields, plus a conservative fallback and provenance/latency reporting.
- Added synthetic three-node multihop mesh transport, partition controls, loss/duplication knobs, raw-byte interface, optional Meshtastic adapter, fragmentation/reassembly, and LoRa airtime estimator.
- Added local two-responder browser demo and 42 fictional labeled reports with compression, fallback, and Ollama evaluation tooling.
- Added a dashboard-controlled, one-way physical-radio receive gateway. It accepts only valid schema 6 application packets and forwards them into the synthetic in-memory mesh; the gateway has no radio transmit path.
- Added installation, protocol, measurement caveats, and current limitations in README.
- Diagnosed a likely Meshtasticator forwarding bug: its configured node ports start at 4404, but `on_receive_all` gated node-0 forwarding on 4403. Updated the guard to use `self.nodes[0].TCPPort`, preserving all prior user edits and backups. Syntax and diff checks pass; runtime forwarding remains unverified because Docker access is denied.

## Environment findings / blockers

- Python 3.13.2 and the local `.venv` already include FastAPI, Pydantic 2, uvicorn, and Meshtastic 2.7.11.
- Meshtasticator's own environment is Python 3.10.6 with Meshtastic 2.6.4 and protobuf 4.25.9 (the previously reported versions).
- Ollama is installed. The service was started locally; installed `llama3:8b` (4.7 GB) was evaluated on synthetic reports. The service remains running; the app can use it with `RESCUECODEC_OLLAMA_MODEL=llama3:8b`.
- Docker context is `colima`, but access to its socket was denied. Simulator runtime, firmware logs, packet reception, and end-to-end simulator tests could not be checked.
- Local loopback port binding for the FastAPI server is denied, so browser/API-over-HTTP startup could not be exercised. The API functions and lifecycle were smoke-tested directly in-process.
- `pytest` is not installed in the app virtual environment, and package download failed because DNS/network access is blocked. Ran the equivalent standard-library `unittest` suite successfully instead.
- Physical radio configuration was not accessed or changed; no RF packets were transmitted.
- Read-only USB access confirmed node `!9e9d65b0` (`Meshtastic 65b0`). The receive-only gateway opened and closed successfully; zero RescueCodec application packets arrived during the brief check.
- Meshtasticator has the user's existing node-identity patch and backup files. Preserve them.

## Technical decisions

- Use 200 bytes as a configurable conservative application packet ceiling.
- Keep schema IDs separate from codec IDs. Schema 6 is the only implemented semantic wire schema; IDs 1/2/3 are reserved project-local registry entries.
- Encode categorical fields in 2-bit enums and an equipment bitmask; coordinates use signed degrees × 100,000; integers are network byte order.
- Keep the emulator explicitly synthetic. Airtime includes a documented configurable overhead estimate and is never labeled measured RF time.
- Avoid any automatic hardware transmission in app startup or tests.

## Current task / next steps

- Core verification is complete: 17 standard-library unit tests pass, including gateway, separate responder-count encoding, and evidence-grounding cases. Inline JavaScript syntax checks and direct API lifecycle checks pass.
- On 42 fictional reports, llama3:8b plus schema validation and deterministic evidence grounding scored 100% exact match on 31 schema-supported reports and 100% detection on 11 unsupported reports; median latency was 4.45 s (p95 4.89 s) for model calls. This small synthetic score is not a field accuracy claim. The deterministic fallback scored 98.1% field accuracy and 90.3% exact match on this corrected set.
- The example report with explicit coordinates and a minimum two-responder request encoded and decoded to a 21-byte packet. The model can be used in the app by setting `RESCUECODEC_OLLAMA_MODEL=llama3:8b` before launch.
- The Meshtasticator forwarding guard was patched, but packet reception remains unconfirmed because Docker access is denied.
- Obtain an authorized test channel and second radio before physical transmission; then validate the Meshtastic adapter with bounded, low-rate tests.
- Next features: explicit text-fallback control in UI, deliver/accept/complete state persistence, image encoders (dense and sparse quantized), Codec2/semantic voice, and live radio measurements after authorization.
