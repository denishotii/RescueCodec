// Domain frames: chat texts with a small team vocabulary of N-bit codes (spec section 3.8).
// Port of rescuecodec/v2/textdomain.py. The encoder is exact: Python, Kotlin and TypeScript write the same bytes.
//
//   encodeDomainStream("Apply a tourniquet.", "medical")   // 5 bytes of 10-bit codes
//   decodeDomainStream(stream, "medical")                   // "Apply a tourniquet.", byte for byte
//
// A domain vocabulary has 2^N - 8 entries (N = 10 for every vocabulary of version 1: 1016 entries). The stream is a
// bit stream of N-bit codes, most significant bit first, the last byte padded with zero bits:
//   0 .. 2^N-9      entry: line number - 1 of spec/vocab/domain-<domain>-v1.txt
//   2^N-8  ESC16    the next 16 bits are a token of word dictionary 2 (0x0000..0xEFFF)
//   2^N-7  CAP      the next entry (or escaped token) with its first letter a-z written A-Z
//   2^N-6  UPPER    the next entry (or escaped token) with every letter a-z written A-Z
//   2^N-5  GLUE     no space before the next entry
//   2^N-4  LIT      the next 8 bits are a length L (1..255), then L bytes of UTF-8, copied exactly
//   2^N-3 .. 2^N-1  reserved: the stream is malformed
// Spaces, punctuation and literals follow section 3.6.3 exactly (an escaped token counts as an entry). Never a near
// match: a word that is in neither the vocabulary nor word dictionary 2 goes as a literal.
import { DOMAIN_VOCAB_TEXT } from "./domain-vocab-data.js";
import { sha256Hex } from "./sha256.js";
import {
  CAP as WORDS_CAP, FIRST_CONTROL, LEFT_ATTACH, MAX_WORDS_TEXT_BYTES, RIGHT_ATTACH, WordDict, WordsError, asciiUpper,
  hasLoneSurrogate, matchWord, splitPieces, wordDictAvailable, wordDictionary,
} from "./textwords.js";

/** Version of the frame rules (spec/vocab/domains.yaml `format`). */
export const DOMAIN_FORMAT = 1;
/** The top 8 codes of every code space are controls. */
export const RESERVED_CODES = 8;
/** Control offsets from 2^N - 8. */
export const ESC16 = 0, CAP = 1, UPPER = 2, GLUE = 3, LIT = 4;
/** Word dictionary that ESC16 tokens come from. */
export const GENERAL_DICT = 2;
/** Longest literal in one LIT code (the length is 8 bits). */
export const MAX_DOMAIN_LITERAL = 255;

/** A team vocabulary (spec/vocab/domains.yaml). */
export interface Vocab {
  /** The byte after 0x03 on the air. */
  id: number;
  /** Stable name, e.g. "sar"; the same domain may get a new id for a new version. */
  domain: string;
  /** Long name for settings, e.g. "Search & Rescue". */
  label: string;
  /** Short name for message tags, e.g. "S&R". */
  short: string;
  version: number;
  /** N: width of a code. */
  bits: number;
  /** In spec/vocab. */
  file: string;
  sha256: string;
  /** 2^N - 8. */
  entries: number;
}

const V = (id: number, domain: string, label: string, short: string, file: string, sha256: string): Vocab =>
  ({ id, domain, label, short, version: 1, bits: 10, file, sha256, entries: 1016 });

/** Vocabulary id -> vocabulary. Ids 0 and 6..255 are not assigned. */
export const VOCABS: Readonly<Record<number, Vocab>> = {
  1: V(1, "emergency", "Emergency", "Emergency", "domain-emergency-v1.txt",
    "e98e8c9026b9f75b29a681dfaee2a49ebac39fbbea4d1471e60949ac71164664"),
  2: V(2, "sar", "Search & Rescue", "S&R", "domain-sar-v1.txt",
    "444798678a105827cf783b990c0ceda05b4cf927b4846c15e0345213f60092ee"),
  3: V(3, "fire", "Firefighting", "Fire", "domain-fire-v1.txt",
    "d37f489ed55a944d59fcc3b53c985da2facf3aefa81cf9053240d80cf62dc9a2"),
  4: V(4, "hiking", "Hiking / Mountain", "Hiking", "domain-hiking-v1.txt",
    "3e03a004a0696afc41738d71280e13125390eff85f3ab073d6b9c7b510e6464a"),
  5: V(5, "medical", "Medical / First aid", "Medical", "domain-medical-v1.txt",
    "21a76e1f01d3aac7291875f6b863244fc873d724cdae4afddd7a3cfcade6be3b"),
};
/** Domain name -> the id new texts are encoded with. */
export const CURRENT_VOCAB: Readonly<Record<string, number>> = Object.fromEntries(
  Object.values(VOCABS).sort((a, b) => a.version - b.version).map((v) => [v.domain, v.id]),
);
/** Choices of the "team vocabulary" setting: auto (every domain), general (no domain frame) or one domain. */
export const VOCAB_SETTINGS: readonly string[] = ["auto", "general", ...Object.keys(CURRENT_VOCAB)];

/** A vocabulary id from an id or a domain name ("sar"). Throws Error when neither is known. */
export function vocabId(vocab: number | string): number {
  if (typeof vocab === "number" && Number.isInteger(vocab) && Object.hasOwn(VOCABS, vocab)) return vocab;
  if (typeof vocab === "string" && Object.hasOwn(CURRENT_VOCAB, vocab)) return CURRENT_VOCAB[vocab];
  throw new Error(`unknown team vocabulary ${JSON.stringify(vocab)}`);
}

/** Vocabulary ids a sender tries for a setting: auto = all (by id), general = none, a domain = that one. */
export function vocabCandidates(setting: string): number[] {
  if (setting === "auto") return Object.values(CURRENT_VOCAB).sort((a, b) => a - b);
  if (setting === "general") return [];
  if (Object.hasOwn(CURRENT_VOCAB, setting)) return [CURRENT_VOCAB[setting]];
  throw new Error(`team vocabulary must be one of ${VOCAB_SETTINGS.join(", ")}`);
}

const cache = new Map<number, WordDict | Error>();

/**
 * The entries of a vocabulary. Throws for an unknown id, and for a bundled copy whose SHA-256 differs from the spec
 * (a damaged copy is never used).
 */
export function domainVocabulary(vocab: number | string): WordDict {
  const id = vocabId(vocab);
  let d = cache.get(id);
  if (d === undefined) {
    try {
      const v = VOCABS[id];
      const text = Object.hasOwn(DOMAIN_VOCAB_TEXT, v.file) ? DOMAIN_VOCAB_TEXT[v.file] : undefined;
      if (text === undefined) throw new Error(`spec/vocab/${v.file} is not bundled`);
      if (sha256Hex(new TextEncoder().encode(text)) !== v.sha256) {
        throw new Error(`spec/vocab/${v.file} is damaged (SHA-256 differs; check line endings)`);
      }
      d = WordDict.fromText(text);
      if (d.entries.length !== v.entries || v.entries !== (1 << v.bits) - RESERVED_CODES) {
        throw new Error(`spec/vocab/${v.file} must have ${(1 << v.bits) - RESERVED_CODES} entries`);
      }
    } catch (e) {
      d = e instanceof Error ? e : new Error(String(e));
    }
    cache.set(id, d);
  }
  if (d instanceof Error) throw d;
  return d;
}

/** True when this library has the vocabulary and word dictionary 2 (for ESC16), both matching their SHA-256. */
export function domainVocabAvailable(vocab: number | string): boolean {
  try {
    domainVocabulary(vocab);
  } catch {
    return false;
  }
  return wordDictAvailable(GENERAL_DICT);
}

// ------------------------------------------------------------------ bits

class BitWriter {
  private out: number[] = [];
  private acc = 0;
  private n = 0;

  put(value: number, width: number): void {
    this.acc = (this.acc << width) | value;
    this.n += width;
    while (this.n >= 8) {
      this.n -= 8;
      this.out.push((this.acc >>> this.n) & 0xff);
    }
    this.acc &= (1 << this.n) - 1;
  }

  done(): Uint8Array {
    if (this.n) this.out.push((this.acc << (8 - this.n)) & 0xff); // zero padding, fewer than 8 bits
    return Uint8Array.from(this.out);
  }
}

class BitReader {
  private pos = 0;
  private readonly total: number;
  constructor(private readonly data: Uint8Array) {
    this.total = 8 * data.length;
  }

  left(): number {
    return this.total - this.pos;
  }

  get(width: number): number {
    let value = 0;
    for (let i = 0; i < width; i++) {
      const byte = this.data[this.pos >> 3];
      value = value * 2 + ((byte >> (7 - (this.pos & 7))) & 1);
      this.pos++;
    }
    return value;
  }
}

const utf8 = new TextEncoder();
const len8 = (s: string) => utf8.encode(s).length;

/** A literal run as LIT pieces of at most 255 bytes: each the longest prefix that ends on a whole character. */
export function literalChunks(run: Uint8Array): Uint8Array[] {
  const out: Uint8Array[] = [];
  let at = 0;
  while (at < run.length) {
    let cut = Math.min(MAX_DOMAIN_LITERAL, run.length - at);
    while (at + cut < run.length && (run[at + cut] & 0xc0) === 0x80) cut--;
    out.push(run.subarray(at, at + cut));
    at += cut;
  }
  return out;
}

// ------------------------------------------------------------------ encode

const TOK = 0, LITST = 1, START = 2;
const INF = 2 ** 40;

type Step =
  | { k: number; st: number; kind: "lit" }
  | { k: number; st: number; kind: "tok"; idx: number; ctrl: number; escaped: boolean; mode: "none" | "glue" | "gap" };

/**
 * Text to the shortest domain bit stream (spec section 3.8.4), padded to whole bytes. Dynamic programming with costs
 * in bits and fixed tie rules, so every library writes the same bytes. Throws WordsError (too_large), or Error for an
 * unknown or damaged vocabulary or a text that is not valid Unicode.
 */
export function encodeDomainStream(text: string, vocab: number | string): Uint8Array {
  const v = VOCABS[vocabId(vocab)];
  const dd = domainVocabulary(v.id);
  const d2 = wordDictionary(GENERAL_DICT);
  const nb = v.bits;
  const base = (1 << nb) - RESERVED_CODES;
  const litHead = nb + 8;
  if (hasLoneSurrogate(text)) throw new Error("the text is not valid Unicode (lone surrogate)");
  if (len8(text) > MAX_WORDS_TEXT_BYTES) {
    throw new WordsError("too_large", `a compact text is at most ${MAX_WORDS_TEXT_BYTES} bytes`);
  }
  const { gaps, pieces } = splitPieces(text);
  const n = pieces.length;
  const spanMax = Math.max(dd.maxSpan, d2.maxSpan);
  const gapBits = gaps.map((g) => 8 * len8(g));
  const pieceBits = pieces.map((p) => 8 * len8(p));
  const cost: number[][] = Array.from({ length: n + 1 }, () => [INF, INF]);
  const back: (Step | null)[][] = Array.from({ length: n + 1 }, () => [null, null]);

  /** [index, case control or 0, escaped, bits]: the vocabulary first (always cheaper), else word dictionary 2. */
  const best = (span: string): [number, number, boolean, number] | null => {
    let m = matchWord(span, dd);
    if (m !== null) return [m[0], m[1], false, nb * (m[1] ? 2 : 1)];
    m = matchWord(span, d2);
    if (m !== null) return [m[0], m[1], true, nb * (m[1] ? 2 : 1) + 16];
    return null;
  };

  for (let k = 0; k < n; k++) {
    const g = gaps[k];
    for (const st of k === 0 ? [START] : [TOK, LITST]) {
      const c0 = st === START ? 0 : cost[k][st];
      if (c0 >= INF) continue;
      let span = "";
      for (let j = k; j < Math.min(n, k + spanMax); j++) {
        span = j === k ? pieces[k] : span + gaps[j] + pieces[j];
        const m = best(span);
        if (m === null) continue;
        const [idx, ctrl, escaped, ct] = m;
        const prev = k > 0 ? pieces[k - 1] : "";
        const implied = st === TOK && !LEFT_ATTACH.includes(span[0]) && !RIGHT_ATTACH.includes(prev[prev.length - 1]);
        let add: number;
        let mode: "none" | "glue" | "gap";
        if (implied && g === " ") [add, mode] = [ct, "none"];
        else if (implied && g === "") [add, mode] = [nb + ct, "glue"];
        else if (g === "") [add, mode] = [ct, "none"];
        else if (st === LITST) [add, mode] = [gapBits[k] + ct, "gap"];
        else [add, mode] = [litHead + gapBits[k] + ct, "gap"];
        if (c0 + add < cost[j + 1][TOK]) {
          cost[j + 1][TOK] = c0 + add;
          back[j + 1][TOK] = { k, st, kind: "tok", idx, ctrl, escaped, mode };
        }
      }
      const add = gapBits[k] + pieceBits[k] + (st === LITST ? 0 : litHead);
      if (c0 + add < cost[k + 1][LITST]) {
        cost[k + 1][LITST] = c0 + add;
        back[k + 1][LITST] = { k, st, kind: "lit" };
      }
    }
  }

  const last = gaps[n];
  const steps: Step[] = [];
  if (n > 0) {
    const endTok = cost[n][TOK] + (last === "" ? 0 : litHead + gapBits[n]);
    const endLit = cost[n][LITST] + gapBits[n];
    let pos = n;
    let st = endTok <= endLit ? TOK : LITST;
    while (pos > 0) {
      const step = back[pos][st]!;
      steps.push(step);
      pos = step.k;
      st = step.st;
    }
    steps.reverse();
  }

  const w = new BitWriter();
  let run: number[] = [];
  const addRun = (s: string) => {
    for (const b of utf8.encode(s)) run.push(b);
  };
  const flush = () => {
    for (const chunk of literalChunks(Uint8Array.from(run))) {
      w.put(base + LIT, nb);
      w.put(chunk.length, 8);
      for (const b of chunk) w.put(b, 8);
    }
    run = [];
  };

  for (const step of steps) {
    const k = step.k;
    if (step.kind === "lit") {
      addRun(gaps[k] + pieces[k]);
      continue;
    }
    if (step.mode === "gap") addRun(gaps[k]);
    flush();
    if (step.mode === "glue") w.put(base + GLUE, nb);
    if (step.ctrl) w.put(base + (step.ctrl === WORDS_CAP ? CAP : UPPER), nb);
    if (step.escaped) {
      w.put(base + ESC16, nb);
      w.put(step.idx, 16);
    } else w.put(step.idx, nb);
  }
  addRun(last);
  flush();
  return w.done();
}

// ------------------------------------------------------------------ decode

const isLowerAscii = (c: string) => c >= "a" && c <= "z";
const hex4 = (n: number) => n.toString(16).toUpperCase().padStart(4, "0");

/**
 * Domain bit stream to text. Throws WordsError (malformed, too_large), or Error for an unknown or damaged vocabulary.
 * `trace`, when given, gets one line per code for tools and test vectors, e.g. 'CAP', 'E 31 "search"',
 * 'ESC16 0x0D0A "pizza"', 'LIT 3 " 4."', 'GLUE'.
 */
export function decodeDomainStream(data: Uint8Array, vocab: number | string, trace?: string[]): string {
  const v = VOCABS[vocabId(vocab)];
  const dd = domainVocabulary(v.id);
  const d2 = wordDictionary(GENERAL_DICT);
  const nb = v.bits;
  const base = (1 << nb) - RESERVED_CODES;
  // ignoreBOM: a leading U+FEFF is part of the text (Python keeps it too), not a byte order mark to drop.
  const strict = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true });
  const r = new BitReader(data);
  const out: number[] = [];
  let prev = START; // last unit written: start, entry or literal
  let prevRight = false; // the last entry ends with ( [ {
  let glue = false;
  let kase = 0; // 0, CAP or UPPER
  while (r.left() >= nb) {
    const code = r.get(nb);
    if (code < base || code === base + ESC16) {
      let entry: string;
      if (code < base) {
        if (code >= dd.entries.length) {
          throw new WordsError("malformed", `code ${code} is not in the vocabulary (${dd.entries.length} entries)`);
        }
        entry = dd.entries[code];
        trace?.push(`E ${code} ${JSON.stringify(entry)}`);
      } else {
        if (r.left() < 16) throw new WordsError("malformed", "the escaped token is cut off");
        const token = r.get(16);
        if (token >= FIRST_CONTROL || token >= d2.entries.length) {
          throw new WordsError("malformed", `escaped token 0x${hex4(token)} is not in word dictionary ${GENERAL_DICT}`);
        }
        entry = d2.entries[token];
        trace?.push(`ESC16 0x${hex4(token)} ${JSON.stringify(entry)}`);
      }
      if (kase === CAP && isLowerAscii(entry[0])) entry = String.fromCharCode(entry.charCodeAt(0) - 32) + entry.slice(1);
      else if (kase === UPPER) entry = asciiUpper(entry);
      if (prev === TOK && !glue && !LEFT_ATTACH.includes(entry[0]) && !prevRight) out.push(0x20);
      for (const b of utf8.encode(entry)) out.push(b);
      prev = TOK;
      prevRight = RIGHT_ATTACH.includes(entry[entry.length - 1]);
      glue = false;
      kase = 0;
    } else if (code === base + LIT) {
      if (glue || kase) throw new WordsError("malformed", "a control must be followed by an entry, not a literal");
      if (r.left() < 8) throw new WordsError("malformed", "the literal length is cut off");
      const size = r.get(8);
      if (size === 0) throw new WordsError("malformed", "empty literal");
      if (r.left() < 8 * size) throw new WordsError("malformed", `the literal of ${size} bytes is cut off`);
      const chunk = Uint8Array.from({ length: size }, () => r.get(8));
      let s: string;
      try {
        s = strict.decode(chunk);
      } catch {
        throw new WordsError("malformed", "a literal is not valid UTF-8");
      }
      for (const b of chunk) out.push(b);
      prev = LITST;
      trace?.push(`LIT ${size} ${JSON.stringify(s)}`);
    } else if (code === base + CAP || code === base + UPPER) {
      if (kase) throw new WordsError("malformed", "two case controls before one entry");
      kase = code === base + CAP ? CAP : UPPER;
      trace?.push(kase === CAP ? "CAP" : "UPPER");
    } else if (code === base + GLUE) {
      if (glue) throw new WordsError("malformed", "two GLUE controls before one entry");
      glue = true;
      trace?.push("GLUE");
    } else {
      throw new WordsError("malformed", `reserved code ${code} (control ${code - base})`);
    }
    if (out.length > MAX_WORDS_TEXT_BYTES) {
      throw new WordsError("too_large", `decodes to more than ${MAX_WORDS_TEXT_BYTES} bytes`);
    }
  }
  const rest = r.left();
  if (rest >= 8 || (rest > 0 && r.get(rest) !== 0)) {
    throw new WordsError("malformed", "the padding must be fewer than 8 zero bits");
  }
  if (glue || kase) throw new WordsError("malformed", "the stream ends with a control");
  if (out.length === 0) throw new WordsError("malformed", "empty stream");
  return strict.decode(Uint8Array.from(out));
}
