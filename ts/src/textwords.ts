// Compact texts: 2-byte word tokens from a shared word dictionary (spec section 3.6).
// Port of rescuecodec/v2/textwords.py. The encoder is exact: Python, Kotlin and TypeScript write the same bytes.
//
//   encodeWords("We found Aylin Demir")   // [We] [found] [literal " Aylin Demir"]
//   decodeWords(data, 1)                  // "We found Aylin Demir", byte for byte
//
// The stream is a list of 16-bit big-endian units:
//   0x0000..0xEFFF  dictionary entry (line number - 1 of spec/vocab/words-dict-v<N>.txt)
//   0xF001          CAP    next entry with its first letter a-z written A-Z
//   0xF002          UPPER  next entry with every letter a-z written A-Z
//   0xF003          GLUE   no space before the next entry
//   0xF800 + N      literal: the next N bytes (1..2047) are UTF-8, copied exactly
//   anything else   reserved: the stream is malformed
// Spaces: one space between two adjacent entries, except before an entry that starts with . , ; : ! ? ) ] } or the
// ellipsis, and after an entry that ends with ( [ {. Never next to a literal: a literal carries its own spaces.
import { sha256Hex } from "./sha256.js";
import { WORD_DICT_TEXT } from "./words-dict-data.js";

/** Largest text a receiver decodes (bytes of UTF-8), same as for DEFLATE texts. */
export const MAX_WORDS_TEXT_BYTES = 1024;
/** Word dictionary versions: number -> file in spec/vocab and the SHA-256 of its bytes (from the spec). */
export const WORD_DICTS: Readonly<Record<number, { file: string; sha256: string }>> = {
  1: { file: "words-dict-v1.txt", sha256: "ffe93afb4623fcd364479169281ca745cc263317a4f83ce27f77d8d37b26a0d8" },
  2: { file: "words-dict-v2.txt", sha256: "c88c6b6bd08821b150a248c41f10b99c71ee5f34ab40d6840685738eb8f48dee" },
};
/** The word dictionary new texts are encoded with (2: 61,440 entries; 1 stays readable forever). */
export const CURRENT_WORD_DICT = 2;

export const CAP = 0xf001;
export const UPPER = 0xf002;
export const GLUE = 0xf003;
export const LITERAL = 0xf800;
/** 2047 bytes in one literal run. */
export const MAX_LITERAL = 0x07ff;
/** 0xF000..0xF7FF are controls (only F001..F003 defined), 0xF800.. literals. */
export const FIRST_CONTROL = 0xf000;

const WHITESPACE = " \t\n\r";
/** Characters that are a piece on their own. */
const SPLIT = '.,;:!?()[]{}"…';
/** An entry starting with one of these gets no space before it. */
const LEFT_ATTACH = ".,;:!?)]}…";
/** An entry ending with one of these gets no space after it. */
const RIGHT_ATTACH = "([{";

/** Why a token stream was rejected: `malformed` or `too_large` (codec error codes). */
export class WordsError extends Error {
  readonly code: "malformed" | "too_large";
  constructor(code: "malformed" | "too_large", message: string) {
    super(message);
    this.name = "WordsError";
    this.code = code;
  }
}

/** A word dictionary: entries by token number, the index back, and the most pieces in one entry. */
export class WordDict {
  private constructor(
    readonly entries: readonly string[],
    readonly index: ReadonlyMap<string, number>,
    readonly maxSpan: number,
  ) {}

  static fromEntries(entries: readonly string[]): WordDict {
    if (entries.length > FIRST_CONTROL) throw new Error(`at most ${FIRST_CONTROL} entries`);
    const index = new Map<string, number>();
    let span = 1;
    entries.forEach((entry, i) => {
      if (!entry || index.has(entry) || /[\t\n\r]/.test(entry) || entry !== stripSpaces(entry)) {
        throw new Error(`bad or repeated entry ${i}: ${JSON.stringify(entry)}`);
      }
      index.set(entry, i);
      span = Math.max(span, splitPieces(entry).pieces.length);
    });
    return new WordDict([...entries], index, span);
  }

  /** The file text: one entry per line, ending with a line break. */
  static fromText(text: string): WordDict {
    if (!text.endsWith("\n")) throw new Error("the dictionary must end with a line break");
    return WordDict.fromEntries(text.slice(0, -1).split("\n"));
  }
}

function stripSpaces(s: string): string {
  let a = 0, b = s.length;
  while (a < b && s[a] === " ") a++;
  while (b > a && s[b - 1] === " ") b--;
  return s.slice(a, b);
}

const cache = new Map<number, WordDict | Error>();

/**
 * The word dictionary of a version. Throws for an unknown version, and for a bundled copy whose SHA-256 differs
 * from the spec (a damaged copy is never used).
 */
export function wordDictionary(version: number): WordDict {
  let d = cache.get(version);
  if (d === undefined) {
    try {
      const info = Object.hasOwn(WORD_DICTS, version) ? WORD_DICTS[version] : undefined;
      const text = Object.hasOwn(WORD_DICT_TEXT, version) ? WORD_DICT_TEXT[version] : undefined;
      if (!info || text === undefined) throw new Error(`unknown word dictionary ${version}`);
      if (sha256Hex(new TextEncoder().encode(text)) !== info.sha256) {
        throw new Error(`spec/vocab/${info.file} is damaged (SHA-256 differs; check line endings)`);
      }
      d = WordDict.fromText(text);
    } catch (e) {
      d = e instanceof Error ? e : new Error(String(e));
    }
    cache.set(version, d);
  }
  if (d instanceof Error) throw d;
  return d;
}

/** True when this library has word dictionary `version` and its bytes match the SHA-256 of the spec. */
export function wordDictAvailable(version: number): boolean {
  try {
    wordDictionary(version);
    return true;
  } catch {
    return false;
  }
}

// ------------------------------------------------------------------ pieces

/**
 * Text to gaps and pieces: text == gaps[0] + pieces[0] + gaps[1] + ... + pieces[n-1] + gaps[n].
 * A gap is a run of space, tab, CR, LF (maybe empty). A piece is one SPLIT character, or a run of anything else.
 * All split and space characters are in the BMP, so working on UTF-16 units gives the same pieces as code points.
 */
export function splitPieces(text: string): { gaps: string[]; pieces: string[] } {
  const gaps: string[] = [];
  const pieces: string[] = [];
  const n = text.length;
  let i = 0;
  let j = i;
  while (j < n && WHITESPACE.includes(text[j])) j++;
  gaps.push(text.slice(i, j));
  i = j;
  while (i < n) {
    if (SPLIT.includes(text[i])) j = i + 1;
    else {
      j = i;
      while (j < n && !WHITESPACE.includes(text[j]) && !SPLIT.includes(text[j])) j++;
    }
    pieces.push(text.slice(i, j));
    i = j;
    while (j < n && WHITESPACE.includes(text[j])) j++;
    gaps.push(text.slice(i, j));
    i = j;
  }
  return { gaps, pieces };
}

function asciiLower(s: string): string {
  return s.replace(/[A-Z]/g, (c) => String.fromCharCode(c.charCodeAt(0) + 32));
}

function asciiUpper(s: string): string {
  return s.replace(/[a-z]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 32));
}

const isUpperAscii = (c: string) => c >= "A" && c <= "Z";
const isLowerAscii = (c: string) => c >= "a" && c <= "z";

/** [entry index, control or 0] for the text `s`: the exact entry, else CAP of an entry, else UPPER of an entry. */
export function matchWord(s: string, d: WordDict): [number, number] | null {
  let idx = d.index.get(s);
  if (idx !== undefined) return [idx, 0];
  if (isUpperAscii(s[0])) {
    idx = d.index.get(String.fromCharCode(s.charCodeAt(0) + 32) + s.slice(1));
    if (idx !== undefined) return [idx, CAP];
  }
  const low = asciiLower(s);
  if (low !== s && asciiUpper(low) === s) {
    idx = d.index.get(low);
    if (idx !== undefined) return [idx, UPPER];
  }
  return null;
}

const utf8 = new TextEncoder();
const len8 = (s: string) => utf8.encode(s).length;

function hasLoneSurrogate(s: string): boolean {
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    if (c >= 0xd800 && c <= 0xdbff) {
      const d = s.charCodeAt(i + 1);
      if (d >= 0xdc00 && d <= 0xdfff) i++;
      else return true;
    } else if (c >= 0xdc00 && c <= 0xdfff) return true;
  }
  return false;
}

// ------------------------------------------------------------------ encode

const TOK = 0, LIT = 1, START = 2;
const INF = 1 << 30;

type Step =
  | { k: number; st: number; kind: "lit" }
  | { k: number; st: number; kind: "tok"; idx: number; ctrl: number; mode: "none" | "glue" | "gap" };

function resolve(version: number | WordDict): WordDict {
  return version instanceof WordDict ? version : wordDictionary(version);
}

/**
 * Text to the shortest token stream (spec section 3.6, "Encoder"). Dynamic programming with fixed tie rules, so
 * every library writes the same bytes. Throws WordsError (too_large).
 */
export function encodeWords(text: string, version: number | WordDict = CURRENT_WORD_DICT): Uint8Array {
  const d = resolve(version);
  if (hasLoneSurrogate(text)) throw new Error("the text is not valid Unicode (lone surrogate)");
  if (len8(text) > MAX_WORDS_TEXT_BYTES) throw new WordsError("too_large", `a compact text is at most ${MAX_WORDS_TEXT_BYTES} bytes`);
  const { gaps, pieces } = splitPieces(text);
  const n = pieces.length;
  const gapLen = gaps.map(len8);
  const pieceLen = pieces.map(len8);
  const cost: number[][] = Array.from({ length: n + 1 }, () => [INF, INF]);
  const back: (Step | null)[][] = Array.from({ length: n + 1 }, () => [null, null]);

  const relax = (pos: number, state: number, value: number, step: Step) => {
    if (value < cost[pos][state]) {
      cost[pos][state] = value;
      back[pos][state] = step;
    }
  };

  for (let k = 0; k < n; k++) {
    const g = gaps[k];
    for (const st of k === 0 ? [START] : [TOK, LIT]) {
      const c0 = st === START ? 0 : cost[k][st];
      if (c0 >= INF) continue;
      let span = "";
      for (let j = k; j < Math.min(n, k + d.maxSpan); j++) {
        span = j === k ? pieces[k] : span + gaps[j] + pieces[j];
        const m = matchWord(span, d);
        if (m === null) continue;
        const [idx, ctrl] = m;
        const ct = ctrl ? 4 : 2;
        const prev = k > 0 ? pieces[k - 1] : "";
        const implied = st === TOK && !LEFT_ATTACH.includes(span[0]) && !RIGHT_ATTACH.includes(prev[prev.length - 1]);
        let add: number;
        let mode: "none" | "glue" | "gap";
        if (implied && g === " ") [add, mode] = [ct, "none"];
        else if (implied && g === "") [add, mode] = [2 + ct, "glue"];
        else if (g === "") [add, mode] = [ct, "none"];
        else if (st === LIT) [add, mode] = [gapLen[k] + ct, "gap"];
        else [add, mode] = [2 + gapLen[k] + ct, "gap"];
        relax(j + 1, TOK, c0 + add, { k, st, kind: "tok", idx, ctrl, mode });
      }
      const add = gapLen[k] + pieceLen[k] + (st === LIT ? 0 : 2);
      relax(k + 1, LIT, c0 + add, { k, st, kind: "lit" });
    }
  }

  const last = gaps[n];
  const steps: Step[] = [];
  if (n > 0) {
    const endTok = cost[n][TOK] + (last === "" ? 0 : 2 + gapLen[n]);
    const endLit = cost[n][LIT] + gapLen[n];
    let pos = n;
    let st = endTok <= endLit ? TOK : LIT;
    while (pos > 0) {
      const step = back[pos][st]!;
      steps.push(step);
      pos = step.k;
      st = step.st;
    }
    steps.reverse();
  }

  const out: number[] = [];
  let run: number[] = [];
  const push16 = (v: number) => out.push(v >> 8, v & 0xff);
  const flush = () => {
    if (run.length) {
      push16(LITERAL + run.length);
      for (const b of run) out.push(b);
      run = [];
    }
  };
  const addRun = (s: string) => {
    for (const b of utf8.encode(s)) run.push(b);
  };

  for (const step of steps) {
    const k = step.k;
    if (step.kind === "lit") {
      addRun(gaps[k] + pieces[k]);
      continue;
    }
    if (step.mode === "gap") addRun(gaps[k]);
    flush();
    if (step.mode === "glue") push16(GLUE);
    if (step.ctrl) push16(step.ctrl);
    push16(step.idx);
  }
  addRun(last);
  flush();
  return Uint8Array.from(out);
}

// ------------------------------------------------------------------ decode

/** Token stream to text. Throws WordsError (malformed, too_large), or Error for an unknown or damaged dictionary. */
export function decodeWords(data: Uint8Array, version: number | WordDict): string {
  const d = resolve(version);
  // ignoreBOM: a leading U+FEFF is part of the text (Python keeps it too), not a byte order mark to drop.
  const strict = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true });
  const out: number[] = [];
  let prev = START; // last unit written: start, token or literal
  let prevRight = false; // the last token ends with ( [ {
  let glue = false;
  let kase = 0;
  let i = 0;
  while (i < data.length) {
    if (i + 2 > data.length) throw new WordsError("malformed", "the token stream is cut off (half a token)");
    const code = (data[i] << 8) | data[i + 1];
    i += 2;
    if (code < FIRST_CONTROL) {
      if (code >= d.entries.length) {
        throw new WordsError("malformed", `token ${code} is not in the dictionary (${d.entries.length} entries)`);
      }
      let entry = d.entries[code];
      if (kase === CAP && isLowerAscii(entry[0])) entry = String.fromCharCode(entry.charCodeAt(0) - 32) + entry.slice(1);
      else if (kase === UPPER) entry = asciiUpper(entry);
      if (prev === TOK && !glue && !LEFT_ATTACH.includes(entry[0]) && !prevRight) out.push(0x20);
      for (const b of utf8.encode(entry)) out.push(b);
      prev = TOK;
      prevRight = RIGHT_ATTACH.includes(entry[entry.length - 1]);
      glue = false;
      kase = 0;
    } else if (code > LITERAL) {
      if (glue || kase) throw new WordsError("malformed", "a control must be followed by a token, not a literal");
      const size = code - LITERAL;
      if (i + size > data.length) throw new WordsError("malformed", `the literal of ${size} bytes is cut off`);
      const chunk = data.subarray(i, i + size);
      try {
        strict.decode(chunk);
      } catch {
        throw new WordsError("malformed", "a literal is not valid UTF-8");
      }
      for (const b of chunk) out.push(b);
      i += size;
      prev = LIT;
    } else if (code === CAP || code === UPPER) {
      if (kase) throw new WordsError("malformed", "two case controls before one token");
      kase = code;
    } else if (code === GLUE) {
      if (glue) throw new WordsError("malformed", "two GLUE controls before one token");
      glue = true;
    } else {
      throw new WordsError(
        "malformed",
        code !== LITERAL ? `unknown control 0x${code.toString(16).toUpperCase().padStart(4, "0")}` : "empty literal",
      );
    }
    if (out.length > MAX_WORDS_TEXT_BYTES) throw new WordsError("too_large", `decodes to more than ${MAX_WORDS_TEXT_BYTES} bytes`);
  }
  if (glue || kase) throw new WordsError("malformed", "the stream ends with a control");
  if (out.length === 0) throw new WordsError("malformed", "empty token stream");
  return strict.decode(Uint8Array.from(out));
}
