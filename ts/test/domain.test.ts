// Domain frames 0x03 with team vocabularies (spec section 3.8). Runs spec/vectors/domain.json, the same file Python
// and Kotlin run, plus random round trips. Port of tests/test_v2_domain_frame.py.
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import {
  CodecError, CURRENT_VOCAB, VOCABS, VOCAB_SETTINGS, decodeAny, decodeDomain, decodeDomainStream, domainVocabAvailable,
  domainVocabulary, encode, encodeCompact, encodeDomain, encodeDomainStream, isCompactFrame, isDomainFrame, render,
  vocabCandidates, vocabId, wordDictionary,
} from "../src/index.js";
import { DOMAIN_VOCAB_TEXT } from "../src/domain-vocab-data.js";

const here = dirname(fileURLToPath(import.meta.url));
const spec = join(here, "..", "..", "spec");
const vectors = JSON.parse(readFileSync(join(spec, "vectors", "domain.json"), "utf8"));

const hex = (b: Uint8Array) => Buffer.from(b).toString("hex");
const bytes = (h: string) => new Uint8Array(Buffer.from(h, "hex"));
const len8 = (s: string) => Buffer.byteLength(s, "utf8");

function codeOf(fn: () => unknown): string {
  try {
    fn();
  } catch (e) {
    expect(e).toBeInstanceOf(CodecError);
    return (e as CodecError).code;
  }
  throw new Error("expected a CodecError, got a result");
}

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

/** Random UTF-8: vocabulary entries, words of word dictionary 2, any code point, odd spaces and case. */
function randomText(r: ReturnType<typeof rng>, vocab: string): string {
  const dd = domainVocabulary(vocab).entries;
  const d2 = wordDictionary(2).entries;
  const gaps = [...Array(10).fill(" "), "", "  ", "\n", "\t", "\r\n", "  "];
  let out = "";
  for (let i = r.int(0, 30); i > 0; i--) {
    const x = r.next();
    let w: string;
    if (x < 0.35) w = r.pick(dd);
    else if (x < 0.55) w = r.pick(d2);
    else if (x < 0.7) w = r.pick([...'.,;:!?()[]{}"…-\'/#']);
    else if (x < 0.85) {
      w = "";
      for (let k = r.int(1, 6); k > 0; k--) {
        w += String.fromCodePoint(r.pick([r.int(0x20, 0x7e), r.int(0xa0, 0x2fff), r.int(0x1f300, 0x1f6ff), r.int(0xe000, 0xfffd)]));
      }
    } else w = String(r.int(0, 10 ** r.int(1, 8)));
    if (r.next() < 0.2) w = w.charAt(0).toUpperCase() + w.slice(1);
    else if (r.next() < 0.1) w = w.toUpperCase();
    out += w + r.pick(gaps);
  }
  return out;
}

/** The first `n` code points of a text (never half a surrogate pair). */
const head = (s: string, n: number) => [...s].slice(0, n).join("");

describe("team vocabularies", () => {
  it("are the ones of the vectors, bundled byte for byte with their SHA-256", () => {
    expect(Object.keys(VOCABS).map(Number)).toEqual(vectors.vocabularies.map((v: any) => v.id));
    for (const row of vectors.vocabularies) {
      const v = VOCABS[row.id];
      expect(v).toEqual({
        id: row.id, domain: row.domain, label: row.label, short: row.short, version: row.version, bits: row.bits,
        file: row.file, sha256: row.sha256, entries: row.entries,
      });
      const file = readFileSync(join(spec, "vocab", row.file));
      expect(createHash("sha256").update(file).digest("hex")).toBe(row.sha256);
      expect(Buffer.compare(Buffer.from(DOMAIN_VOCAB_TEXT[row.file], "utf8"), file)).toBe(0);
      expect(domainVocabulary(row.id).entries.length).toBe((1 << row.bits) - 8);
      expect(domainVocabAvailable(row.id)).toBe(true);
    }
  });

  it("agree with spec/vocab/domains.yaml", () => {
    const yaml = readFileSync(join(spec, "vocab", "domains.yaml"), "utf8");
    const rows = [...yaml.matchAll(/- id: (\d+)\n\s+domain: (\w+)[\s\S]*?bits: (\d+)[\s\S]*?sha256: ([0-9a-f]{64})/g)];
    expect(rows.map((m) => [Number(m[1]), m[2], Number(m[3]), m[4]])).toEqual(
      Object.values(VOCABS).map((v) => [v.id, v.domain, v.bits, v.sha256]),
    );
  });

  it("are picked by id or domain name", () => {
    expect(vocabId("sar")).toBe(2);
    expect(vocabId(5)).toBe(5);
    expect(CURRENT_VOCAB).toEqual({ emergency: 1, sar: 2, fire: 3, hiking: 4, medical: 5 });
    expect(VOCAB_SETTINGS).toEqual(["auto", "general", "emergency", "sar", "fire", "hiking", "medical"]);
    expect(vocabCandidates("auto")).toEqual([1, 2, 3, 4, 5]);
    expect(vocabCandidates("general")).toEqual([]);
    expect(vocabCandidates("fire")).toEqual([3]);
    for (const bad of [0, 6, 255, "sars", "", 2.5]) expect(() => vocabId(bad as any)).toThrow();
    expect(() => vocabCandidates("sars")).toThrow();
    expect(domainVocabAvailable(0)).toBe(false);
    expect(domainVocabAvailable(6)).toBe(false);
  });
});

describe(`domain frame vectors, spec ${vectors.spec_version}`, () => {
  it.each<any>(vectors.frames)("frames: $name", (c: any) => {
    const data = encodeDomain(c.text, c.vocab);
    expect(hex(data)).toBe(c.hex);
    expect(data.length).toBe(c.size);
    expect(len8(c.text)).toBe(c.plain_size);
    expect(encodeDomain(c.text, c.vocab_id)).toEqual(data);
    expect(decodeDomain(data)).toEqual(c.decoded);
    expect(decodeAny(data)).toEqual({ v: 2, ...c.decoded });
    const trace: string[] = [];
    expect(decodeDomainStream(data.subarray(2), c.vocab, trace)).toBe(c.text);
    expect(trace).toEqual(c.trace);
  });

  it.each<any>(vectors.streams)("streams: $name", (c: any) => {
    expect(hex(encodeDomainStream(c.text, c.vocab))).toBe(c.stream);
    const trace: string[] = [];
    expect(decodeDomainStream(bytes(c.stream), c.vocab, trace)).toBe(c.text);
    expect(trace).toEqual(c.trace);
  });

  it.each<any>(vectors.random)("random: $name", (c: any) => {
    expect(hex(encodeDomainStream(c.text, c.vocab))).toBe(c.stream);
    expect(decodeDomainStream(bytes(c.stream), c.vocab)).toBe(c.text);
  });

  it.each<any>(vectors.invalid_decode)("invalid_decode: $name", (c: any) => {
    expect(codeOf(() => decodeDomain(bytes(c.hex)))).toBe(c.error);
    expect(codeOf(() => decodeAny(bytes(c.hex)))).toBe(c.error);
  });

  it.each<any>(vectors.invalid_encode)("invalid_encode: $name", (c: any) => {
    expect(codeOf(() => encodeDomain(c.text, c.vocab))).toBe(c.error);
  });

  it.each<any>(vectors.unknown)("unknown: $name", (c: any) => {
    const msg = decodeDomain(bytes(c.hex));
    expect(msg).toEqual(c.decoded);
    expect(decodeAny(bytes(c.hex))).toEqual({ v: 2, ...c.decoded });
    expect(render(msg, "en")).toBe(c.render.en);
    expect(render(msg, "de")).toBe(c.render.de);
  });

  // The send rule (SPEC 2.3 rule 6): the strictly smallest complete payload, plain wins ties; between packed forms
  // the 0x02 frame, then words in the Envelope, then the domain frame with the lowest id. DEFLATE is left out (the
  // vectors say why). This library has no sender, so the test builds the candidates and checks every size.
  it.each<any>(vectors.choice)("choice: $name", (c: any) => {
    const plain = new TextEncoder().encode(c.text);
    const lean = encodeCompact(c.text, 2);
    const words = encode({ id: 3, text: { text: c.text, words: 2 } });
    const domains: Record<string, Uint8Array> = {};
    for (const id of vocabCandidates(c.setting)) domains[VOCABS[id].domain] = encodeDomain(c.text, id);
    expect({
      plain: plain.length, lean: lean.length, words: words.length,
      domain: Object.fromEntries(Object.entries(domains).map(([k, v]) => [k, v.length])),
    }).toEqual(c.sizes);

    const packed: [string, string | null, Uint8Array][] = [
      ["lean", null, lean], ["words", null, words], ...Object.entries(domains).map(([k, v]) => ["domain", k, v] as [string, string, Uint8Array]),
    ];
    let best = packed[0];
    for (const p of packed) if (p[2].length < best[2].length) best = p;
    const [form, vocab, payload] = best[2].length < plain.length ? best : ["plain", null, plain];
    expect([form, vocab, payload.length, hex(payload)]).toEqual([c.form, c.vocab, c.size, c.hex]);
  });
});

describe("domain frame", () => {
  it("is one byte per word or less for vocabulary words", () => {
    expect(hex(encodeDomain("Apply a tourniquet.", "medical"))).toBe("0305e300609c00");
    expect(encodeDomain("tourniquet", "medical").length).toBe(4); // 10 bits -> 2 bytes + 2
    expect(encodeDomain("ridge", "fire").length).toBe(6); // ESC16: 26 bits -> 4 bytes + 2
    expect(encodeDomain("tourniquet", "fire").length).toBe(15); // not in dictionary 2: LIT, 98 bits
  });

  it("keeps names, numbers, coordinates and emoji exact", () => {
    for (const text of ["Aylin Demir at 50.69212, 7.21345 🙂", "  two  spaces\tand a tab\n", "ÄÖÜ ß über 3 km", "﻿bom"]) {
      expect(decodeDomain(encodeDomain(text, "medical"))).toEqual({ text: { text, vocab: 5 } });
    }
  });

  it("is told apart from a compact frame", () => {
    const data = encodeDomain("Apply a tourniquet.", "medical");
    expect(isDomainFrame(data)).toBe(true);
    expect(isCompactFrame(data)).toBe(false);
    expect(isDomainFrame(encodeCompact("hello"))).toBe(false);
    expect(isDomainFrame(new Uint8Array(0))).toBe(false);
  });

  it("renders as a normal text message", () => {
    expect(render(decodeAny(encodeDomain("Apply a tourniquet.", "medical")), "en")).toBe("Message: Apply a tourniquet.");
  });

  it("has no Envelope form: vocab in a text body is invalid", () => {
    const e = (() => {
      try {
        encode({ id: 1, text: { text: "fire", vocab: 3 } });
      } catch (x) {
        return x as CodecError;
      }
      return null;
    })();
    expect(e).toBeInstanceOf(CodecError);
    expect([e!.code, e!.field]).toEqual(["invalid_value", "text.vocab"]);
  });

  it("refuses text that is not valid Unicode", () => {
    expect(codeOf(() => encodeDomain("bad \uD800 surrogate", "sar"))).toBe("invalid_value");
  });
});

describe("domain frame round trips", () => {
  it("random UTF-8 round-trips exactly for every vocabulary", () => {
    const r = rng(7);
    let frames = 0;
    for (const vocab of Object.keys(CURRENT_VOCAB)) {
      for (let i = 0; i < 300; i++) {
        const text = head(randomText(r, vocab), r.int(0, 600));
        const stream = encodeDomainStream(text, vocab);
        if (!text) {
          expect(stream.length).toBe(0);
          continue;
        }
        expect(decodeDomainStream(stream, vocab)).toBe(text);
        const raw = len8(text);
        if (raw <= 1024) expect(stream.length).toBeLessThanOrEqual(raw + 3 * (Math.floor(raw / 255) + 1)); // never worse than LIT
        let data: Uint8Array;
        try {
          data = encodeDomain(text, vocab);
        } catch (e) {
          expect((e as CodecError).code).toBe("too_large");
          continue;
        }
        expect(decodeAny(data)).toEqual({ v: 2, text: { text, vocab: CURRENT_VOCAB[vocab] } });
        frames++;
      }
    }
    expect(frames).toBeGreaterThan(500); // the texts are real, not all empty or too large
  });

  it("random bytes never crash", () => {
    const r = rng(11);
    for (let i = 0; i < 3000; i++) {
      const tail = Uint8Array.from({ length: r.int(0, 60) }, () => r.int(0, 255));
      const data = Uint8Array.from([3, r.pick([1, 2, 3, 4, 5, r.int(0, 255)]), ...tail]);
      try {
        const msg = decodeAny(data);
        expect(Object.keys(msg).filter((k) => k !== "v")).toEqual(["text" in msg ? "text" : "unknown"]);
      } catch (e) {
        expect(e).toBeInstanceOf(CodecError);
        expect(["malformed", "too_large"]).toContain((e as CodecError).code);
      }
    }
  });

  it("every short payload is a result or a malformed error", () => {
    for (let a = 0; a < 256; a++) {
      for (const data of [[3, a], ...Array.from({ length: 16 }, (_, b) => [3, 2, a, b * 17])]) {
        try {
          decodeAny(Uint8Array.from(data));
        } catch (e) {
          expect(e).toBeInstanceOf(CodecError);
          expect(["malformed", "too_large"]).toContain((e as CodecError).code);
        }
      }
    }
  });

  it("a cut frame is an error or a shorter prefix, never other words", () => {
    const r = rng(5);
    for (const vocab of Object.keys(CURRENT_VOCAB)) {
      for (let i = 0; i < 40; i++) {
        const text = head(randomText(r, vocab), 150) || "x";
        let data: Uint8Array;
        try {
          data = encodeDomain(text, vocab);
        } catch {
          continue;
        }
        for (let cut = 2; cut < data.length; cut++) {
          let got: string;
          try {
            got = decodeDomain(data.subarray(0, cut)).text!.text;
          } catch (e) {
            expect((e as CodecError).code).toBe("malformed");
            continue;
          }
          expect(text.startsWith(got) && got !== text).toBe(true);
        }
      }
    }
  });
});
