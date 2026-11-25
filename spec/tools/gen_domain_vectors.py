"""Generate spec/vectors/domain.json: conformance vectors for the 0x03 domain frame (spec/SPEC.md section 3.8).

Run:  PYTHONPATH=. .venv/bin/python spec/tools/gen_domain_vectors.py [--check]
--check compares with the committed file instead of writing it (tests/test_v2_domain_frame.py does the same).
The cases below are the source of truth; the bytes come from the Python reference (rescuecodec/v2/textdomain.py).
Only add cases, never change the bytes of a released one. Kotlin and TypeScript load the same file.
"""
from __future__ import annotations

import json
import random
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from rescuecodec.engine.textmode import choose_form  # noqa: E402
from rescuecodec.v2 import codes as C  # noqa: E402
from rescuecodec.v2 import textdomain, textwords  # noqa: E402
from rescuecodec.v2.codec import CodecError, decode_domain, encode_compact, encode_domain  # noqa: E402
from rescuecodec.v2.render import render  # noqa: E402

OUT = ROOT / "spec" / "vectors" / "domain.json"
LETTERS = "abcdefghijklmnopqrstuvwxyz"

#: (name, vocabulary, text, note). encode_domain(text, vocab) must give hex, decode_domain(hex) must give the text.
FRAMES = [
    ("emergency-evacuate", "emergency", "Evacuate now, the river is flooding.",
     "Emergency: CAP + entries, punctuation attaches left. 14 B (36 B plain, 18 B as a 0x02 frame)."),
    ("sar-missing-found", "sar", "Missing person found, alive.",
     "Search & Rescue: 11 B (28 B plain, 16 B as a 0x02 frame)."),
    ("fire-ridge", "fire", "Smoke on the ridge, wildfire.",
     "Firefighting: \"ridge\" is not in the vocabulary, so ESC16 + its word dictionary 2 token. 12 B."),
    ("hiking-trail", "hiking", "Trail closed, avalanche danger.",
     "Hiking / Mountain: five entries and a comma, 10 B (31 B plain)."),
    ("medical-tourniquet", "medical", "Apply a tourniquet.",
     "Medical: codes 908, 6, 39, 0 = 40 bits = 5 bytes, no padding. 7 B (19 B plain, 22 B as a 0x02 frame)."),
    ("medical-pitch", "medical", "Patient is conscious, bleeding from the leg, apply a tourniquet.",
     "The pitch example: 19 B (64 B plain, 38 B as a 0x02 frame)."),
    ("escape-cap", "fire", "Ridge closed.", "CAP applies to an escaped token too."),
    ("escape-upper", "fire", "RIDGE closed.", "UPPER applies to an escaped token too."),
    ("escape-phrase", "emergency", "Need help, two injured at the shelter.",
     "An escaped two-word phrase of word dictionary 2 (\"Need help\")."),
    ("escape-single-letter", "sar", "x", "A word of word dictionary 2 only: ESC16."),
    ("upper", "sar", "MISSING PERSON", "UPPER before each entry."),
    ("glue-quotes", "fire", "\"Fire\" on the ridge", "GLUE: no space between the quote and the word."),
    ("glue-dot", "sar", "found.found", "GLUE after a dot."),
    ("brackets", "fire", "(fire)", "( attaches right, ) attaches left: no GLUE needed."),
    ("literal-coordinates", "sar", "Missing person found at 50.69212, 7.21345.",
     "Numbers and the comma between them go in one literal that carries its own leading space."),
    ("literal-name", "medical", "Patient Aylin Demir is conscious.",
     "A name owns the spaces around it: the literal is \" Aylin Demir \"."),
    ("literal-emoji-newline", "medical", "Bleeding \U0001f642\nApply a tourniquet.",
     "Emoji and a line break are literal bytes, copied exactly."),
    ("literal-two-spaces", "hiking", "Trail  closed", "Two spaces are a literal."),
    ("literal-tab", "hiking", "Trail\tclosed", "A tab is a literal."),
    ("literal-only-space", "hiking", " ", "Only a space: one literal of 1 byte."),
    ("literal-spaces-around", "hiking", " snow ", "Leading and trailing spaces are literals."),
    ("literal-accent", "medical", "café", "Not ASCII: the whole word is a literal (never a near match)."),
    ("literal-190", "sar", "Q" * 190, "A literal of 190 bytes: 2 + ceil((10 + 8 + 190 * 8) / 8) = 195 B."),
    ("literal-1-and-word", "sar", "7 found", "A 1-byte literal, then a word."),
]

#: (name, vocabulary, text, note): streams only (encode_stream / decode_stream), may be larger than one packet.
STREAMS = [
    ("literal-254", "sar", "Q" * 254, "One LIT of 254 bytes."),
    ("literal-255", "sar", "Q" * 255, "One LIT of 255 bytes, the longest."),
    ("literal-256", "sar", "Q" * 256, "256 bytes: LIT 255 + LIT 1."),
    ("literal-split-on-character", "sar", "Q" * 254 + "é",
     "254 + 2 bytes: the first LIT stops before the 2-byte character (LIT 254 + LIT 2)."),
    ("literal-600", "medical", "Q" * 600, "LIT 255 + LIT 255 + LIT 90."),
]

def frame(vid: int, *units: tuple[int, int]) -> str:
    """A 0x03 frame as hex from (value, bits) units, MSB first, zero-padded to whole bytes."""
    acc = n = 0
    for value, width in units:
        acc, n = (acc << width) | value, n + width
    pad = -n % 8
    return bytes((3, vid)).hex() + ((acc << pad).to_bytes((n + pad) // 8, "big").hex() if n else "")


ESC16, CAP, UPPER, GLUE, LIT, RES5, RES7 = 1016, 1017, 1018, 1019, 1020, 1021, 1023     # N = 10

#: (name, hex, error, note): decode_domain(hex) must be rejected with this error code.
INVALID = [
    ("only-marker", "03", "malformed", "No vocabulary id."),
    ("no-stream", "0302", "malformed", "A vocabulary id but no codes."),
    ("only-padding", "030200", "malformed", "8 zero bits: no code (fewer than 10 bits), and padding must be < 8 bits."),
    ("padding-8-bits", "0305e300609c0000", "malformed",
     "medical-tourniquet plus one zero byte: 8 bits left over (padding must be fewer than 8 bits)."),
    ("padding-not-zero", "0302fe41f1f43900b79001", "malformed", "sar-missing-found with a 1 bit in the padding."),
    ("reserved-code-5", frame(5, (RES5, 10)), "malformed", "Code 1021 (base + 5) is reserved."),
    ("reserved-code-7", frame(5, (RES7, 10)), "malformed", "Code 1023 (base + 7) is reserved."),
    ("escape-cut", frame(5, (ESC16, 10), (0, 14)), "malformed", "ESC16 with only 14 bits after it."),
    ("escape-out-of-range", frame(5, (ESC16, 10), (0xF000, 16)), "malformed", "ESC16 0xF000: past word dictionary 2."),
    ("literal-length-0", frame(5, (LIT, 10), (0, 8)), "malformed", "LIT with length 0."),
    ("literal-length-cut", frame(5, (LIT, 10)), "malformed", "LIT with no length after it (6 bits of padding)."),
    ("literal-cut", frame(5, (LIT, 10), (3, 8), (0x61, 8), (0x62, 8)), "malformed",
     "LIT of 3 bytes with only 2 bytes after it."),
    ("literal-not-utf8", frame(5, (LIT, 10), (1, 8), (0xFF, 8)), "malformed", "LIT of 1 byte 0xFF: not UTF-8."),
    ("cap-at-end", frame(5, (CAP, 10)), "malformed", "The stream ends with CAP."),
    ("glue-at-end", frame(5, (0, 10), (GLUE, 10)), "malformed", "The stream ends with GLUE."),
    ("cap-before-literal", frame(5, (CAP, 10), (LIT, 10), (1, 8), (0x41, 8)), "malformed", "CAP before a literal."),
    ("glue-before-literal", frame(5, (0, 10), (GLUE, 10), (LIT, 10), (1, 8), (0x41, 8)), "malformed",
     "GLUE before a literal."),
    ("two-case-controls", frame(5, (CAP, 10), (UPPER, 10), (0, 10)), "malformed", "CAP then UPPER before one entry."),
    ("two-glue", frame(5, (0, 10), (GLUE, 10), (GLUE, 10), (0, 10)), "malformed", "GLUE twice before one entry."),
    ("too-large-frame", "0305" + "00" * 232, "too_large", "234 bytes: over the 233-byte decoder limit."),
    ("decodes-over-1024", frame(1, *[(36, 10)] * 184), "too_large",
     "184 times entry 36 (\"interoperability\", 16 letters) in 232 bytes: over 1024 bytes of text."),
]

#: (name, text, vocab, error, note): encode_domain(text, vocab) must be rejected.
INVALID_ENCODE = [
    ("empty-text", "", "sar", "missing_field", "An empty text has no domain frame."),
    ("unknown-vocab", "fire", 9, "invalid_value", "Vocabulary 9 is not assigned."),
    ("vocab-0", "fire", 0, "invalid_value", "Vocabulary 0 is not assigned."),
    ("frame-over-200", "Q" * 200, "sar", "too_large", "203 bytes: over the 200-byte encoder limit."),
    ("text-over-1024", "fire " * 205, "fire", "too_large", "1025 bytes of text: over the 1024-byte text limit."),
]

#: (name, hex, note): decode_domain(hex) must give {"unknown": {"vocab": id}} (shown as "unsupported vocabulary").
UNKNOWN = [
    ("vocab-0", "0300fe41f1f43900b79000", "Id 0 is never assigned."),
    ("vocab-6", "0306fe41f1f43900b79000", "Id 6 is not assigned in spec 2.3."),
    ("vocab-255", "03ff00", "Id 255, any stream: never decoded."),
]

#: (name, text, setting, note): choose_form({"id": 3, "text": {"text": text}}, packs=True, compact=True, vocab=setting).
CHOICE = [
    ("domain-wins", "Patient is conscious, bleeding from the leg, apply a tourniquet.", "auto",
     "The medical frame (19 B) beats the 0x02 frame (38 B) and the plain text (64 B)."),
    ("general-wins", "Fire is spreading up the ridge, need two more engines and water.", "auto",
     "Too many words outside every vocabulary: the 0x02 frame (24 B) beats the best domain frame (25 B)."),
    ("general-wins-short", "hello world", "auto", "0x02 frame 6 B, best domain frame 7 B."),
    ("plain-wins", "Ok", "auto", "2 B plain: smaller than every packed form."),
    ("plain-wins-tie", "(fire)", "auto", "The fire frame is 6 B like the plain text: plain wins ties."),
    ("other-domain-wins", "SAR team 2 at the trailhead", "auto",
     "Auto tries every vocabulary: the hiking frame (18 B) beats the S&R frame (20 B)."),
    ("pinned-domain", "SAR team 2 at the trailhead", "sar",
     "Pinned to S&R: only the S&R frame and the general forms are tried (20 B)."),
    ("pinned-other-domain", "Patient is conscious, bleeding from the leg, apply a tourniquet.", "sar",
     "Pinned to S&R: the medical frame is not tried; the 0x02 frame (38 B) wins over the S&R frame."),
    ("general-setting", "Patient is conscious, bleeding from the leg, apply a tourniquet.", "general",
     "General: no domain frame at all."),
    ("tie-lowest-id", "found.found", "auto", "Several vocabularies give 7 B: the lowest id wins (emergency)."),
]

#: Random round trips (bytes must match in every library): seeded, per vocabulary.
RANDOM_PER_VOCAB = 8


def trace(stream: bytes, vocab) -> list[str]:
    out: list[str] = []
    textdomain.decode_stream(stream, vocab, out)
    return out


def _random_text(rnd: random.Random, vocab: str) -> str:
    """Domain entries, words of word dictionary 2, odd pieces, case changes, odd spaces, non-ASCII."""
    dd = textdomain.vocabulary(vocab)
    d2 = textwords.dictionary(2)
    parts = []
    for _ in range(rnd.randint(1, 14)):
        r = rnd.random()
        if r < 0.45:
            w = rnd.choice(dd.entries)
        elif r < 0.7:
            w = rnd.choice(d2.entries)
        elif r < 0.8:
            w = rnd.choice([".", ",", "!", "?", "(", ")", "\"", "…", ":", "-"])
        elif r < 0.9:
            w = "".join(rnd.choice(LETTERS + "0123456789") for _ in range(rnd.randint(1, 9)))
        else:
            w = rnd.choice(["ü", "ß", "\U0001f642", "Ж", " ", "50.6921", "7,2", "#3", "été"])
        if rnd.random() < 0.15:
            w = w[:1].upper() + w[1:]
        elif rnd.random() < 0.07:
            w = w.upper()
        parts.append(w)
        parts.append(rnd.choice([" "] * 12 + ["", "  ", "\n", "\t", " \n"]))
    return "".join(parts)[:rnd.randint(1, 180)] or "x"


def build() -> dict:
    out: dict = {
        "spec_version": C.SPEC_VERSION,
        "about": ("RescueCodec domain frame vectors (SPEC section 3.8, first byte 0x03). "
                  "vocabularies: id, domain, code width and SHA-256 of each bundled vocabulary file; check them. "
                  "frames: encode_domain(text, vocab) must give hex exactly and decode_domain(hex) must give decoded "
                  "(decode_any adds \"v\": 2). trace is informative (one line per code). "
                  "streams: encode_stream(text, vocab) must give stream exactly and decode_stream(stream, vocab) must "
                  "give text (no frame, may be larger than one packet). "
                  "random: like streams, seeded random texts. "
                  "invalid_decode: decode_domain(hex) must be rejected with error. "
                  "invalid_encode: encode_domain(text, vocab) must be rejected with error. "
                  "unknown: decode_domain(hex) must give decoded ({\"unknown\": {\"vocab\": id}}), rendered as "
                  "render.en. "
                  "choice: the send rule (SPEC 2.3 rule 6, 3.8.6) for {\"id\": 3, \"text\": {\"text\": text}} in a chat "
                  "that packs, compact text on, team vocabulary setting: form, vocab, size and hex of what goes "
                  "out; sizes lists the candidates (DEFLATE is left out: compressors may differ, and in these cases "
                  "it is at least 3 bytes larger than what goes out)."),
        "vocabularies": [{"id": v.id, "domain": v.domain, "label": v.label, "short": v.short, "version": v.version,
                          "bits": v.bits, "entries": v.entries, "file": v.file, "sha256": v.sha256}
                         for v in textdomain.VOCABS.values()],
        "frames": [], "streams": [], "random": [], "invalid_decode": [], "invalid_encode": [], "unknown": [],
        "choice": [],
    }
    for name, vocab, text, note in FRAMES:
        data = encode_domain(text, vocab)
        decoded = decode_domain(data)
        assert decoded["text"]["text"] == text, name
        out["frames"].append({"name": name, "note": note, "vocab": vocab, "vocab_id": data[1], "text": text,
                              "hex": data.hex(), "size": len(data), "plain_size": len(text.encode("utf-8")),
                              "decoded": decoded, "trace": trace(data[2:], vocab)})
    for name, vocab, text, note in STREAMS:
        stream = textdomain.encode_stream(text, vocab)
        assert textdomain.decode_stream(stream, vocab) == text, name
        out["streams"].append({"name": name, "note": note, "vocab": vocab, "text": text, "stream": stream.hex(),
                               "trace": trace(stream, vocab)})
    rnd = random.Random(20261125)
    for vocab in textdomain.CURRENT:
        for i in range(RANDOM_PER_VOCAB):
            text = _random_text(rnd, vocab)
            stream = textdomain.encode_stream(text, vocab)
            assert textdomain.decode_stream(stream, vocab) == text, (vocab, text)
            out["random"].append({"name": f"random-{vocab}-{i + 1}", "vocab": vocab, "text": text,
                                  "stream": stream.hex()})
    for name, hexs, error, note in INVALID:
        try:
            decode_domain(bytes.fromhex(hexs))
        except CodecError as exc:
            assert exc.code == error, (name, exc.code, str(exc))
        else:
            raise AssertionError(f"{name} decodes")
        out["invalid_decode"].append({"name": name, "note": note, "hex": hexs, "error": error})
    for name, text, vocab, error, note in INVALID_ENCODE:
        try:
            encode_domain(text, vocab)
        except CodecError as exc:
            assert exc.code == error, (name, exc.code, str(exc))
        else:
            raise AssertionError(f"{name} encodes")
        out["invalid_encode"].append({"name": name, "note": note, "text": text, "vocab": vocab, "error": error})
    for name, hexs, note in UNKNOWN:
        decoded = decode_domain(bytes.fromhex(hexs))
        assert "unknown" in decoded, name
        out["unknown"].append({"name": name, "note": note, "hex": hexs, "decoded": decoded,
                               "render": {"en": render(decoded, "en"), "de": render(decoded, "de")}})
    for name, text, setting, note in CHOICE:
        form = choose_form({"id": 3, "text": {"text": text}}, True, "rescuecodec_only", compact=True, vocab=setting)
        sent = len(form.payload)
        assert form.packed_size is None or form.packed_size >= sent + 3 or form.form == "deflate", name
        assert form.form != "deflate", name
        sizes = {"plain": form.plain_size, "lean": form.lean_size, "words": form.words_size,
                 "domain": form.domain_sizes or {}}
        out["choice"].append({"name": name, "note": note, "text": text, "setting": setting, "form": form.form,
                              "vocab": form.vocab, "size": sent, "hex": form.payload.hex(), "sizes": sizes})
    return out


def dumps(data: dict) -> str:
    return json.dumps(data, ensure_ascii=False, indent=1) + "\n"


def main() -> int:
    text = dumps(build())
    if "--check" in sys.argv[1:]:
        same = OUT.exists() and OUT.read_text(encoding="utf-8") == text
        print("spec/vectors/domain.json is up to date" if same else "spec/vectors/domain.json differs: regenerate it")
        return 0 if same else 1
    OUT.write_text(text, encoding="utf-8")
    d = build()
    print(f"wrote {OUT.relative_to(ROOT)}: " + ", ".join(f"{len(v)} {k}" for k, v in d.items() if isinstance(v, list)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
