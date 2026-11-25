// A bundled word dictionary whose SHA-256 differs from the spec is never used: a compact text decodes as
// `unknown {words: n}` (shown as "dictionary this app does not have"), never as words.
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it, vi } from "vitest";

vi.mock("../src/words-dict-data.js", async (importOriginal) => {
  const real = await importOriginal<typeof import("../src/words-dict-data.js")>();
  // The same entries with Windows line endings: the classic way a copy gets damaged.
  return { WORD_DICT_TEXT: { 1: real.WORD_DICT_TEXT[1].replace(/\n/g, "\r\n") } };
});

const { CodecError, decode, decodeAny, encode, render, wordDictAvailable } = await import("../src/index.js");

const here = dirname(fileURLToPath(import.meta.url));
const vectors = JSON.parse(readFileSync(join(here, "..", "..", "spec", "vectors", "codec.json"), "utf8"));
const bytes = (h: string) => new Uint8Array(Buffer.from(h, "hex"));

describe("damaged word dictionary copy", () => {
  it("is not available", () => {
    expect(wordDictAvailable(1)).toBe(false);
  });

  it("decodes every compact text as unknown with the dictionary number", () => {
    for (const c of vectors.compact_text) {
      const msg = decode(bytes(c.hex));
      const { text: _text, ...rest } = c.decoded;
      expect(msg).toEqual({ ...rest, unknown: { words: 1 } });
      expect(render(msg, "en")).toContain("word dictionary 1");
      expect(render(msg, "en")).not.toContain(c.text);
    }
  });

  it("decodes a compact frame with it as unknown", () => {
    const c = vectors.compact_frames.find((x: any) => x.name === "frame-dict-1");
    expect(decodeAny(bytes(c.hex))).toEqual({ v: 2, unknown: { words: 1 } });
  });

  it("refuses to encode with it", () => {
    let caught: unknown = null;
    try {
      encode({ text: { text: "is safe", words: 1 } });
    } catch (e) {
      caught = e;
    }
    expect(caught).toBeInstanceOf(CodecError);
    expect([(caught as InstanceType<typeof CodecError>).code, (caught as InstanceType<typeof CodecError>).field]).toEqual([
      "invalid_value",
      "text.words",
    ]);
  });

  it("still decodes plain and packed texts", () => {
    expect(decode(encode({ id: 3, text: { text: "Ok" } })).text).toEqual({ text: "Ok" });
    expect(decode(encode({ id: 3, text: { text: "Hallo", dict: 1 } })).text).toEqual({ text: "Hallo", dict: 1 });
  });
});
