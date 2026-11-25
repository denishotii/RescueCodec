import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import {
  CodecError, WordsError, canonical, decode, decodeAny, decodeCompact, decodeWords, encode, encodeCompact, encodeWords,
  packText, render, textDictionary, unpackText, wordDictionary,
} from "../src/index.js";
import { WORD_DICT_TEXT } from "../src/words-dict-data.js";

const here = dirname(fileURLToPath(import.meta.url));
const vectors = JSON.parse(readFileSync(join(here, "..", "..", "spec", "vectors", "codec.json"), "utf8"));

const hex = (b: Uint8Array) => Buffer.from(b).toString("hex");
const bytes = (h: string) => new Uint8Array(Buffer.from(h, "hex"));

function rejected(fn: () => unknown, error: string, field: string | null) {
  try {
    fn();
  } catch (e) {
    expect(e).toBeInstanceOf(CodecError);
    const ce = e as CodecError;
    expect(ce.code).toBe(error);
    expect(ce.field).toBe(field);
    return;
  }
  throw new Error("expected a CodecError, got a result");
}

describe(`conformance Level 1, spec ${vectors.spec_version}`, () => {
  it.each<any>(vectors.valid)("valid: $name", (c: any) => {
    expect(hex(encode(c.input))).toBe(c.hex);
    expect(c.hex.length / 2).toBe(c.size);
    expect(decode(bytes(c.hex))).toEqual(c.decoded);
    expect(decodeAny(bytes(c.hex))).toEqual({ v: 2, ...c.decoded });
  });

  it.each<any>(vectors.decode_only)("decode_only: $name", (c: any) => {
    expect(decode(bytes(c.hex))).toEqual(c.decoded);
  });

  it.each<any>(vectors.invalid_encode)("invalid_encode: $name", (c: any) => {
    rejected(() => encode(c.input), c.error, c.field);
  });

  it.each<any>(vectors.invalid_decode)("invalid_decode: $name", (c: any) => {
    rejected(() => decode(bytes(c.hex)), c.error, c.field);
  });

  it.each<any>(vectors.v1)("v1: $name", (c: any) => {
    expect(decodeAny(bytes(c.hex))).toEqual(c.decoded);
  });
});

describe("packed texts (spec section 3.5)", () => {
  it.each<any>(vectors.packed_text)("packed_text: $name", (c: any) => {
    expect(unpackText(bytes(c.deflate), c.dict)).toBe(c.text);
    expect(decode(bytes(c.hex))).toEqual(c.decoded);
    expect(decode(encode(c.decoded))).toEqual(c.decoded);
    expect(encode(c.decoded).length).toBeLessThanOrEqual(c.size + 2);
  });

  it("has the dictionaries of text_dicts, byte for byte", () => {
    for (const [n, d] of Object.entries<any>(vectors.text_dicts)) {
      const data = textDictionary(Number(n));
      expect(data.length).toBe(d.size);
      expect(createHash("sha256").update(data).digest("hex")).toBe(d.sha256);
      expect(Buffer.compare(Buffer.from(data), readFileSync(join(here, "..", "..", "spec", d.file)))).toBe(0);
    }
  });

  it("packs the example sentence to 34 bytes", () => {
    const text = "We are at the hut, two people with us, waiting for the vehicle on the forest road";
    expect(packText(text).length).toBe(34);
    expect(unpackText(packText(text), 1)).toBe(text);
  });
});

describe("compact texts (spec section 3.6)", () => {
  it.each<any>(vectors.compact_text)("compact_text: $name", (c: any) => {
    expect(hex(encode(c.input))).toBe(c.hex);
    expect(c.hex.length / 2).toBe(c.size);
    expect(decode(bytes(c.hex))).toEqual(c.decoded);
    expect(hex(encodeWords(c.text, 1))).toBe(c.words);
    expect(decodeWords(bytes(c.words), 1)).toBe(c.text);
    expect(render(c.decoded, "en")).toBe(c.render.en);
    expect(render(c.decoded, "de")).toBe(c.render.de);
  });

  it.each<any>(vectors.word_streams)("word_streams: $name", (c: any) => {
    expect(hex(encodeWords(c.text, 1))).toBe(c.words);
    expect(decodeWords(bytes(c.words), 1)).toBe(c.text);
  });

  it.each<any>(vectors.word_streams_invalid)("word_streams_invalid: $name", (c: any) => {
    let caught: unknown = null;
    try {
      decodeWords(bytes(c.words), 1);
    } catch (e) {
      caught = e;
    }
    expect(caught).toBeInstanceOf(WordsError);
    expect((caught as WordsError).code).toBe(c.error);
  });

  it("has the word dictionaries of word_dicts, byte for byte", () => {
    for (const [n, d] of Object.entries<any>(vectors.word_dicts)) {
      const data = new TextEncoder().encode(WORD_DICT_TEXT[Number(n)]);
      expect(data.length).toBe(d.size);
      expect(createHash("sha256").update(data).digest("hex")).toBe(d.sha256);
      expect(Buffer.compare(Buffer.from(data), readFileSync(join(here, "..", "..", "spec", d.file)))).toBe(0);
      expect(wordDictionary(Number(n)).entries.length).toBe(d.entries);
    }
  });

  it("bundles word dictionary 1 with the SHA-256 of the spec and 8192 entries", () => {
    const data = new TextEncoder().encode(WORD_DICT_TEXT[1]);
    expect(createHash("sha256").update(data).digest("hex")).toBe("ffe93afb4623fcd364479169281ca745cc263317a4f83ce27f77d8d37b26a0d8");
    expect(wordDictionary(1).entries.length).toBe(8192);
  });

  it("renders a compact text with an unknown word dictionary as such", () => {
    const msg = decode(bytes(vectors.decode_only.find((c: any) => c.name === "words-unknown-dict").hex));
    expect(msg).toEqual({ id: 7, unknown: { words: 9 } });
    expect(render(msg, "en")).toBe(
      "Message encoded with a dictionary this app does not have (word dictionary 9), please update the app.",
    );
    expect(render(msg, "de")).toContain("Wörterbuch 9");
    expect(render({ unknown: {} }, "en")).toBe("Message of a newer type, please update the app.");
  });
});

describe("conformance Level 2: readable text", () => {
  for (const c of vectors.valid.filter((c: any) => c.text)) {
    for (const lang of Object.keys(c.text)) {
      it(`${c.name} (${lang})`, () => {
        expect(render(c.decoded, lang)).toBe(c.text[lang]);
      });
    }
  }
});

describe("robustness", () => {
  it("never throws anything but CodecError for 1- and 2-byte payloads", () => {
    for (let a = 0; a < 256; a++) {
      for (const b of [undefined, ...Array.from({ length: 256 }, (_, i) => i)]) {
        const data = b === undefined ? new Uint8Array([a]) : new Uint8Array([a, b]);
        try {
          decodeAny(data);
        } catch (e) {
          expect(e).toBeInstanceOf(CodecError);
        }
      }
    }
  });

  it("canonical() re-orders flags and rounds", () => {
    expect(canonical({ id: 1, found: { needs: ["water", "stretcher"], injuries: ["head", "head"] } })).toEqual({
      id: 1,
      found: { injuries: ["head"], needs: ["stretcher", "water"] },
    });
  });

  it("rounds half away from zero on the decimal text", () => {
    expect(canonical({ id: 1, pos: { lat: 50.123455, lon: -7.000005 }, found: {} }).pos).toEqual({ lat: 50.12346, lon: -7.00001 });
  });
});

describe("compact frames (spec section 3.7)", () => {
  it.each<any>(vectors.compact_frames)("compact_frames: $name", (c: any) => {
    if (c.text !== undefined) expect(hex(encodeCompact(c.text, c.dict))).toBe(c.hex);
    expect(c.hex.length / 2).toBe(c.size);
    expect(decodeCompact(bytes(c.hex))).toEqual(c.decoded);
    expect(decodeAny(bytes(c.hex))).toEqual({ v: 2, ...c.decoded });
    expect(render(c.decoded, "en")).toBe(c.render.en);
    expect(render(c.decoded, "de")).toBe(c.render.de);
  });

  it.each<any>(vectors.compact_frames_invalid)("compact_frames_invalid: $name", (c: any) => {
    const code = (fn: () => unknown) => {
      try {
        fn();
      } catch (e) {
        expect(e).toBeInstanceOf(CodecError);
        return (e as CodecError).code;
      }
      throw new Error("expected a CodecError, got a result");
    };
    if (c.text !== undefined) expect(code(() => encodeCompact(c.text))).toBe(c.error);
    if (c.hex !== undefined) expect(code(() => decodeAny(bytes(c.hex)))).toBe(c.error);
  });

  it("bundles word dictionary 2 with the SHA-256 of the spec and 61440 entries", () => {
    const info = vectors.word_dicts["2"];
    const data = new TextEncoder().encode(WORD_DICT_TEXT[2]);
    expect(createHash("sha256").update(data).digest("hex")).toBe(info.sha256);
    expect(wordDictionary(2).entries.length).toBe(info.entries);
    expect(data.length).toBe(info.size);
  });

  it("hello world is 6 bytes", () => {
    const data = encodeCompact("hello world");
    expect(data.length).toBe(6);
    expect(decodeAny(data)).toEqual({ v: 2, text: { text: "hello world", words: 2 } });
  });

  it("other reserved first bytes stay unsupported", () => {
    for (const first of [0x00, 0x04, 0x05, 0x06, 0x07]) { // 0x03 is the domain frame (spec section 3.8)
      let caught: unknown = null;
      try {
        decodeAny(new Uint8Array([first, 0x02, 0x00, 0x10]));
      } catch (e) {
        caught = e;
      }
      expect((caught as CodecError).code).toBe("unsupported_version");
    }
  });

  it("round-trips random texts exactly", () => {
    let s = 20261125 >>> 0;
    const next = () => {
      s = (s + 0x6d2b79f5) >>> 0;
      let t = s;
      t = Math.imul(t ^ (t >>> 15), t | 1);
      t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
    const pick = <T>(xs: readonly T[]) => xs[Math.floor(next() * xs.length)];
    const D2 = wordDictionary(2);
    const parts = [...D2.entries.slice(0, 3000), " ", "  ", "\n", ",", ".", "Ä", "🙂", "X", "42", "Aylin", "-1.2500"];
    const ranges: [number, number][] = [[0x20, 0x7e], [0x09, 0x0d], [0xa0, 0x7ff], [0x10000, 0x1f64f]];
    for (let i = 0; i < 1000; i++) {
      let text = "";
      if (i % 3 === 0) {
        const n = 1 + Math.floor(next() * 50);
        for (let k = 0; k < n; k++) {
          const [lo, hi] = pick(ranges);
          text += String.fromCodePoint(lo + Math.floor(next() * (hi - lo + 1)));
        }
      } else {
        const n = 1 + Math.floor(next() * 25);
        for (let k = 0; k < n; k++) text += pick(parts) + pick(["", " ", " ", " "]);
        if (next() < 0.3) text = next() < 0.5 ? text.toUpperCase() : text.replace(/\b[a-z]/g, (c) => c.toUpperCase());
      }
      if (!text) continue;
      let data: Uint8Array;
      try {
        data = encodeCompact(text);
      } catch (e) {
        expect((e as CodecError).code).toBe("too_large");
        continue;
      }
      expect(data.length).toBeLessThanOrEqual(200);
      expect(decodeCompact(data)).toEqual({ text: { text, words: 2 } });
    }
  });
});
