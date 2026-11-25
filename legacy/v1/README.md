# First prototype (v1)

This is the team's first prototype from 24 Nov 2026: `receiver.py` prints every packet from a USB radio, `transport.py` is a small command line for the same radio, `virtual_test.py` sends one packet between two simulated nodes, `results/` holds the v1 benchmark, and `PROGRESS.md` and `CLAUDE_HANDOFF.md` are the notes from that day.
We keep it for reference only. It is not maintained, and the radio scripts expect the radio on `/dev/cu.usbserial-0001`.
The product is in `rescuecodec/`, `android/` and `dashboard/`. Start with the main [README](../../README.md).
Some v1 code still lives in `rescuecodec/` (`protocol/`, `transport/`, `encoding/`, `api.py`), because v2 uses it to read old v1 packets, for the airtime formula and for the in-memory radio.
`rescuecodec-benchmark` (v1) still writes new results to `results/` in the repo root. Git ignores that folder.
