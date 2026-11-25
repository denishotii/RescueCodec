// Compact texts: 2-byte word tokens (spec section 3.6). Port of tests/test_v2_textwords.py.
import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import { CodecError, decode, encode, render } from "../src/index.js";
import { sha256Hex } from "../src/sha256.js";
import {
  CAP, GLUE, LITERAL, UPPER, WordsError, decodeWords, encodeWords, wordDictAvailable, wordDictionary,
} from "../src/textwords.js";
import { EnvelopeSchema } from "../src/gen/rescuecodec/v2/rescuecodec_pb.js";
import { create, toBinary } from "@bufbuild/protobuf";

const D = wordDictionary(1);
const POS = { lat: 50.69212, lon: 7.21345 };

const cat = (...parts: Uint8Array[]) => {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
};
const u16 = (v: number) => new Uint8Array([v >> 8, v & 0xff]);
const tok = (entry: string) => u16(D.index.get(entry)!);
const ctl = (code: number) => u16(code);
const lit = (text: string) => {
  const data = new TextEncoder().encode(text);
  return cat(u16(LITERAL + data.length), data);
};

/** Random generator with a fixed seed (mulberry32), so a failure can be repeated. */
function rng(seed: number) {
  let a = seed >>> 0;
  const next = () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  const int = (lo: number, hi: number) => lo + Math.floor(next() * (hi - lo + 1));
  const pick = <T>(xs: readonly T[]) => xs[Math.floor(next() * xs.length)];
  return { next, int, pick };
}

describe("word dictionary", () => {
  it("is available for versions 1 and 2 only", () => {
    expect(wordDictAvailable(1)).toBe(true);
    expect(wordDictAvailable(2)).toBe(true);
    expect(wordDictAvailable(9)).toBe(false);
    expect(wordDictAvailable(0)).toBe(false);
  });

  it("holds no phone numbers or addresses", () => {
    for (const entry of D.entries) expect(entry).not.toMatch(/\d{5}|@|http|www/);
  });

  it("sha256Hex agrees with node:crypto", () => {
    const r = rng(1);
    for (const size of [0, 1, 55, 56, 63, 64, 65, 119, 120, 1000, 66607]) {
      const data = Uint8Array.from({ length: size }, () => r.int(0, 255));
      expect(sha256Hex(data)).toBe(createHash("sha256").update(data).digest("hex"));
    }
  });
});

describe("spacing rules (spec section 3.6.3)", () => {
  it("a literal owns its leading space", () => {
    const data = encodeWords("We found Aylin Demir", 1);
    expect(data).toEqual(cat(tok("We"), tok("found"), lit(" Aylin Demir")));
    expect(decodeWords(data, 1)).toBe("We found Aylin Demir");
  });

  it("a token after a literal gets no implied space", () => {
    const data = encodeWords("Aylin Demir is safe", 1);
    expect(data).toEqual(cat(lit("Aylin Demir "), tok("is"), tok("safe")));
    expect(decodeWords(data, 1)).toBe("Aylin Demir is safe");
    expect(decodeWords(cat(lit("Aylin Demir"), tok("is"), tok("safe")), 1)).toBe("Aylin Demiris safe");
  });

  it("puts a space only between adjacent tokens", () => {
    expect(decodeWords(cat(tok("is"), tok("safe")), 1)).toBe("is safe");
    expect(decodeWords(cat(tok("is"), ctl(GLUE), tok("safe")), 1)).toBe("issafe");
    expect(decodeWords(cat(tok("safe"), tok(".")), 1)).toBe("safe.");
    expect(decodeWords(cat(tok("("), tok("safe"), tok(")")), 1)).toBe("(safe)");
  });

  it("case controls", () => {
    expect(decodeWords(cat(ctl(CAP), tok("safe")), 1)).toBe("Safe");
    expect(decodeWords(cat(ctl(UPPER), tok("safe")), 1)).toBe("SAFE");
    expect(encodeWords("SAFE", 1)).toEqual(cat(ctl(UPPER), tok("safe")));
  });

  it("coordinates typed as prose keep every character", () => {
    const text = "Meet at 50.6900, -1.2500";
    const data = encodeWords(text, 1);
    expect(decodeWords(data, 1)).toBe(text);
    expect(data).toEqual(cat(ctl(CAP), tok("meet"), tok("at"), tok("50"), lit(".6900, -1.2500")));
  });

  it("never a near match", () => {
    for (const text of ["recieve the hepl", "Teh flod", "Aylin", "Müller", "DL1ABC", "Apt 4B"]) {
      expect(decodeWords(encodeWords(text, 1), 1)).toBe(text);
    }
    expect(decodeWords(encodeWords("hepl", 1), 1)).not.toContain("help");
  });

  it("takes the shortest stream", () => {
    expect(encodeWords("x and y", 1)).toEqual(lit("x and y"));
  });
});

describe("round trip", () => {
  const CASES = [
    "Hi, are you still at the station? I will be there in ten minutes.",
    "Team 3 reached Löwenburg hut. Missing hiker found at 50.6921, 7.2134, conscious, needs a stretcher.",
    "The river runs\n  under the bridge,\n\nand we wait   for morning.\n",
    "Zyxomorph flibberjab quantimeter",
    "Wir sind am Parkplatz, the road is blocked, bitte langsam fahren.",
    'Wait... what?! (Really) [yes] {ok}; done: "fine" - right?',
    "All safe 🙂👍 see you soon ❤️",
    "NEED WATER AND FOOD NOW",
    "line one\r\nline two\tend",
    "  hello there  ",
    " ", "\n", ".", "…", "a", "I", "x".repeat(300),
    "﻿starts with a BOM", "﻿",
  ];

  it.each(CASES)("%j", (text) => {
    expect(decodeWords(encodeWords(text, 1), 1)).toBe(text);
  });

  it("random UTF-8 strings (astral emoji, CRLF, repeated spaces)", () => {
    const r = rng(20261125);
    const ranges: Array<[number, number]> = [
      [0x20, 0x7e], [0x09, 0x0d], [0xa0, 0x7ff], [0x800, 0xd7ff], [0xe000, 0xfffd], [0x10000, 0x10ffff],
    ];
    const extras = ["\r\n", "  ", "   ", "\t", "🙂", "👍🏽", "❤️", "…", ". ", ", ", "(", ")", "﻿"];
    for (let n = 0; n < 3000; n++) {
      let text = "";
      for (let i = r.int(1, 60); i > 0; i--) {
        if (r.next() < 0.2) text += r.pick(extras);
        else {
          const [lo, hi] = r.pick(ranges);
          text += String.fromCodePoint(r.int(lo, hi));
        }
      }
      if (new TextEncoder().encode(text).length > 1024) continue;
      expect(decodeWords(encodeWords(text, 1), 1)).toBe(text);
    }
  });

  it("random mixes of dictionary entries, spaces and case", () => {
    const r = rng(7);
    const parts = [...D.entries.slice(0, 400), " ", "  ", "\n", "", ",", ".", "Ä", "🙂", "X", "42"];
    for (let n = 0; n < 3000; n++) {
      let text = "";
      for (let i = r.int(1, 20); i > 0; i--) text += r.pick(parts) + r.pick(["", " ", " ", " "]);
      if (r.next() < 0.3) text = r.next() < 0.5 ? text.toUpperCase() : text.replace(/\b\w/g, (c) => c.toUpperCase());
      if (text) expect(decodeWords(encodeWords(text, 1), 1)).toBe(text);
    }
  });
});

describe("errors", () => {
  const check = (data: Uint8Array, code = "malformed") => {
    let caught: unknown = null;
    try {
      decodeWords(data, 1);
    } catch (e) {
      caught = e;
    }
    expect(caught).toBeInstanceOf(WordsError);
    expect((caught as WordsError).code).toBe(code);
  };

  it("rejects malformed streams", () => {
    check(new Uint8Array(0));
    check(tok("safe").subarray(0, 1));
    check(lit("Aylin").subarray(0, 6));
    check(ctl(LITERAL));
    check(cat(ctl(0xf004), tok("safe")));
    check(ctl(0xf000));
    check(u16(D.entries.length));
    check(cat(tok("safe"), ctl(CAP)));
    check(cat(ctl(CAP), lit("x")));
    check(cat(ctl(CAP), ctl(UPPER), tok("safe")));
    check(cat(ctl(GLUE), ctl(GLUE), tok("safe")));
    check(cat(ctl(LITERAL + 2), new Uint8Array([0xc3, 0x28])));
  });

  it("too large", () => {
    check(cat(lit("x".repeat(1000)), lit("x".repeat(25))), "too_large");
    expect(() => encodeWords("x".repeat(1025), 1)).toThrow(WordsError);
  });
});

describe("codec", () => {
  const wire = (text: Record<string, unknown>) =>
    toBinary(EnvelopeSchema, create(EnvelopeSchema, { id: 7, body: { case: "text", value: text } } as never));

  const rejected = (fn: () => unknown, code: string, field: string) => {
    let caught: unknown = null;
    try {
      fn();
    } catch (e) {
      caught = e;
    }
    expect(caught).toBeInstanceOf(CodecError);
    expect([(caught as CodecError).code, (caught as CodecError).field]).toEqual([code, field]);
  };

  it("encodes, decodes and renders", () => {
    const msg = { id: 3, text: { text: "We found Aylin Demir", words: 1 } };
    const data = encode(msg);
    expect(decode(data)).toEqual(msg);
    expect(render(decode(data), "en")).toBe("Message: We found Aylin Demir.");
  });

  it("keeps GPS in the position field", () => {
    const msg = { id: 9, priority: "urgent", pos: POS, text: { text: "Found him, conscious, needs a stretcher", words: 1 } };
    const out = decode(encode(msg));
    expect(out.pos).toEqual(POS);
    expect(out.text!.text).not.toContain("50.69");
  });

  it("encode rules", () => {
    rejected(() => encode({ text: { text: "", words: 1 } }), "missing_field", "text.text");
    rejected(() => encode({ text: { text: "x", words: 1, dict: 1 } }), "invalid_value", "text.words");
    rejected(() => encode({ text: { text: "x", words: 9 } }), "invalid_value", "text.words");
    rejected(() => encode({ text: { text: "x".repeat(1025), words: 1 } }), "invalid_value", "text.text");
    rejected(() => encode({ text: { text: "x", words: "1" } } as never), "invalid_value", "text.words");
  });

  it("wire rules", () => {
    const words = encodeWords("is safe", 1);
    rejected(() => decode(wire({ words })), "missing_field", "text.words_dict");
    rejected(() => decode(wire({ wordsDict: 1, text: "x" })), "invalid_value", "text.words_dict");
    rejected(() => decode(wire({ words, wordsDict: 1, text: "x" })), "invalid_value", "text.words");
    rejected(() => decode(wire({ words, wordsDict: 1, dict: 1 })), "invalid_value", "text.dict");
    rejected(() => decode(wire({ words: words.subarray(0, words.length - 1), wordsDict: 1 })), "malformed", "text.words");
  });

  it("shows an unknown word dictionary, never guessed words", () => {
    const msg = decode(wire({ words: encodeWords("is safe", 1), wordsDict: 9 }));
    expect(msg).toEqual({ id: 7, unknown: { words: 9 } });
    expect(render(msg, "en")).toContain("dictionary this app does not have");
    expect(render(msg, "de")).toContain("Wörterbuch");
    expect(render(msg, "en")).not.toContain("safe");
  });

  it("existing packets still decode", () => {
    const hut = "We are at the hut, two people with us, waiting for the vehicle on the forest road";
    expect(decode(encode({ id: 3, text: { text: hut, dict: 1 } })).text).toEqual({ text: hut, dict: 1 });
    expect(decode(encode({ id: 3, text: { text: "Ok" } })).text).toEqual({ text: "Ok" });
  });
});
