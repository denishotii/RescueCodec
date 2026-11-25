# RescueCodec engineering handoff

Extend the supplied Python project for the PMRExpo hackathon challenge “Connected. No Matter What.” Deliver a GitHub branch the team can pull and run offline on the presenter’s Apple Silicon Mac. The Mac already has Ollama and llama3:8b. The receiver needs only the shared binary protocol. Develop with synthetic reports and two emulator endpoints on your machine, then rehearse with the actual radios on the presenter’s Mac.

## The 90-second pitch

A responder finds a person and types “I found the person. He is unconscious and needs an oxygen mask. We need at least two people.” The sender sees one person found, breathing unknown, oxygen mask requested and at least two additional responders requested. The sender can attach coordinates from a phone or enter them manually. Local Llama proposes a report, a person confirms it, and Python encodes the fields and request ID into bytes. Encoding provides compression. Radio channel security is a separate concern.

Show the sender pressing Send on the Mac, the attached Heltec transmitting, a second responder receiving and decoding the same bytes, and that responder pressing Accept. The sender sees acceptance only after a matching confirmation comes back. Complete the task if time allows. Show the original UTF-8 byte count and the actual transmitted application byte count for this message. A previous example with explicit coordinates encoded to 21 bytes without a timestamp. Use the live value on stage.

## What exists

- FastAPI browser demo, editable schema 6 person-found report, English/German extraction, deterministic reconstruction, binary codec, IDs, text fallback, synthetic A–B–C routing, acceptance and completion. Start in rescuecodec/api.py, semantic.py, protocol/codec.py and transport/. The demo currently uses shared process state.
- Ollama ran locally on the Mac. The hybrid pipeline matched the seven evaluated fields on 31 supported synthetic reports and rejected 11 unsupported reports. Rules, prompts and annotations were revised using these cases. Evaluate fresh unseen paraphrases, typos, negation and quantities before making accuracy claims. Keep ordinary text available when a report loses meaning.
- A physical Heltec V3 opened over USB. Existing gateway handles inbound reports into the emulator. The adapter has a sendData method. The dashboard’s outbound send and confirmation flows still need integration with the radio. Physical delivery remains unverified. Meshtasticator forwarding also remains unverified.
- Fragment utilities, an airtime estimator, synthetic data and results exist. Start with the existing test suite and PROGRESS.md. Run it again on the delivered commit.

## Build in this order

**P0 • Two independent endpoints.** Configure emulator or serial transport at startup. Give each endpoint its own identity, inbox and request history. In serial mode send raw PRIVATE_APP bytes via the selected USB interface to an explicit peer on an authorized test channel. Filter receive callbacks by interface, port, peer and destination. Correlate each receipt with sender and request ID. Handle duplicate packets and late confirmations idempotently. On timeout show “delivery unknown.” Keep a radio routing ACK distinct from receiver decode and explicit human acceptance. Disable physical sending until the operator selects and authorizes the test setup. Preserve sponsor configuration, keys and duty-cycle settings.

**P0 • Stage UI.** Sender needs the active transport, USB connection, local node and destination, report input, editable preview, optional coordinates, requested responder count, byte count, packet count and Send. Receiver needs a separate view with sender, reconstructed actionable report, Accept and Complete. Show the sender’s confirmation history. Make emulator mode conspicuous and easy to select before the demo. Prioritize clarity at projector distance. A map and animated routing diagram can wait.

**P0 • Prove the route.** Rehearse once with two independent emulator endpoints. Then connect two authorized physical radios, one per endpoint. Two laptops are easiest to explain. Two serial ports on one Mac also work as a test design if endpoint processes and state are isolated. Log synthetic request ID, endpoint IDs, byte count, destination payload match and confirmation round-trip time. Disconnect the receiver and verify the sender cannot claim acceptance. One USB radio can demonstrate device detection and receive-gateway operation. A laptop virtual node cannot hear LoRa by itself. Only claim multihop RF after observing a physical relay.

**P1 • One optional image.** After the text flow works, add crop, preview and send of a 32×32 thumbnail. Black/white takes 128 packed pixel bytes. Four gray levels take 256 bytes dense. Sparse four-level encoding can use 10 bits for position and 2 bits for intensity per non-black pixel. Choose using actual serialized lengths, include headers, and fragment within the 200-byte application budget. Show the reconstructed thumbnail at the receiver. Test whether someone can identify a specific coarse feature, such as which side of a doorway is obstructed. Send the urgent report first. Full phone photos, facial identification and fine scene detail are poor targets for this demo.

**P2 • Voice if time permits.** Four seconds of mono 8 kHz 16-bit PCM is 64,000 bytes. Codec2 at nominal 700 bits/s suggests 350 bytes before frame padding and headers. Measure actual output, packet count and intelligibility on the Mac. An alternative is offline transcription followed by the same human-reviewed semantic report. Fourier filtering, Nyquist sampling and sinc reconstruction explain bandwidth and sampling. They alone do not achieve this speech bitrate. Voice, image codecs and offline transcription still need implementation.

## Decide what can be claimed

Measure application bytes from the encoded buffer. Label LoRa airtime as an estimate with its overhead and modulation assumptions. Measure end-to-end latency separately. Verify payload ceiling, channel selection, firmware behavior and applicable duty-cycle constraints on the authorized configuration. Use independent receiver logs for RF evidence. Demonstrate the emulator if the second radio or channel authorization is unavailable. Leave automatic dispatch, shared incident maps, adaptive optimization and simulator debugging outside the critical path.

## GitHub delivery and Mac acceptance

Use the team-designated GitHub repository. Import the supplied source if needed, create a feature branch, push the implementation and open a reviewable PR. Return the repository URL, branch, commit SHA, startup commands for each endpoint, test results and outstanding hardware checks. Include dependencies, protocol changes, synthetic fixtures and a two-radio runbook. Exclude virtual environments, model weights, .env files, channel exports, keys, real traffic, and patient data. Keep serial paths, peer IDs and model tag configurable.

On a fresh checkout install the Python package and radio extra, run the tests, and start the existing emulator UI:

    python3 -m venv .venv
    .venv/bin/python -m pip install -e '.[radio]'
    .venv/bin/python -m unittest discover -s tests
    RESCUECODEC_OLLAMA_MODEL=llama3:8b .venv/bin/uvicorn rescuecodec.api:app --host 127.0.0.1 --port 8000

Ollama must be serving locally for model inference. A teammate without the model can develop with manual entry and the fallback extractor. Document the actual serial-mode launch commands once integrated. The presenter should be able to pull the named commit, start both endpoints, and repeat the demo without editing source.
