// A bundled team vocabulary whose SHA-256 differs from the spec is never used: a domain frame with it decodes as
// `unknown {vocab: n}` (shown as "unsupported vocabulary"), never as words.
import { describe, expect, it, vi } from "vitest";

vi.mock("../src/domain-vocab-data.js", async (importOriginal) => {
  const real = await importOriginal<typeof import("../src/domain-vocab-data.js")>();
  // The medical vocabulary with two entries swapped: the same size, other words.
  const lines = real.DOMAIN_VOCAB_TEXT["domain-medical-v1.txt"].split("\n");
  [lines[39], lines[40]] = [lines[40], lines[39]];
  return { DOMAIN_VOCAB_TEXT: { ...real.DOMAIN_VOCAB_TEXT, "domain-medical-v1.txt": lines.join("\n") } };
});

const { CodecError, decodeAny, domainVocabAvailable, encodeDomain, render } = await import("../src/index.js");

// encodeDomain("Apply a tourniquet.", "medical") with the real vocabulary (spec/vectors/domain.json).
const TOURNIQUET = new Uint8Array(Buffer.from("0305e300609c00", "hex"));

describe("damaged team vocabulary copy", () => {
  it("is not available, the others are", () => {
    expect(domainVocabAvailable(5)).toBe(false);
    expect(domainVocabAvailable(2)).toBe(true);
  });

  it("decodes a frame with it as unsupported vocabulary, never as words", () => {
    const msg = decodeAny(TOURNIQUET);
    expect(msg).toEqual({ v: 2, unknown: { vocab: 5 } });
    expect(render(msg, "en")).toContain("unsupported vocabulary 5");
    expect(render(msg, "en")).not.toContain("tourniquet");
  });

  it("refuses to encode with it", () => {
    let caught: unknown = null;
    try {
      encodeDomain("Apply a tourniquet.", "medical");
    } catch (e) {
      caught = e;
    }
    expect(caught).toBeInstanceOf(CodecError);
    expect((caught as InstanceType<typeof CodecError>).code).toBe("invalid_value");
  });

  it("still encodes with the other vocabularies", () => {
    expect(decodeAny(encodeDomain("Search the ridge.", "sar"))).toEqual({ v: 2, text: { text: "Search the ridge.", vocab: 2 } });
  });
});
