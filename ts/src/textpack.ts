// Packed texts: raw DEFLATE with a shared preset dictionary (spec section 3.5).
// Port of rescuecodec/v2/textpack.py. Any DEFLATE stream unpacks to the same text everywhere, so only the
// dictionary bytes must be identical in every library; the packed bytes of two compressors may differ.
import { Deflate, Inflate } from "pako";
import { TEXT_DICTS } from "./text-dict-data.js";

/** Largest text a receiver unpacks (bytes of UTF-8). Anything longer is rejected (a "zip bomb" guard). */
export const MAX_UNPACKED_BYTES = 1024;
/** The dictionary new texts are packed with. */
export const CURRENT_DICT = 1;

/** Why unpacking failed: `malformed` or `too_large` (codec error codes). */
export class PackError extends Error {
  readonly code: "malformed" | "too_large";
  constructor(code: "malformed" | "too_large", message: string) {
    super(message);
    this.name = "PackError";
    this.code = code;
  }
}

const cache = new Map<number, Uint8Array>();

/** True when this library has dictionary `version`. */
export function knownDict(version: number): boolean {
  return Object.hasOwn(TEXT_DICTS, version);
}

/** The bytes of a dictionary version. Throws for an unknown version. */
export function textDictionary(version: number): Uint8Array {
  let data = cache.get(version);
  if (!data) {
    if (!knownDict(version)) throw new Error(`unknown text dictionary ${version}`);
    data = new TextEncoder().encode(TEXT_DICTS[version]);
    cache.set(version, data);
  }
  return data;
}

/** UTF-8 text to raw DEFLATE bytes (level 9, window 15, memLevel 8, with the dictionary). */
export function packText(text: string, version: number = CURRENT_DICT): Uint8Array {
  const deflater = new Deflate({ raw: true, level: 9, windowBits: 15, memLevel: 8, dictionary: textDictionary(version) });
  deflater.push(new TextEncoder().encode(text), true);
  if (deflater.err) throw new Error(`could not pack the text: ${deflater.msg}`);
  return deflater.result;
}

class TooLarge extends Error {}

/** Raw DEFLATE bytes to text. Stops after MAX_UNPACKED_BYTES. Throws PackError (or Error for an unknown dictionary). */
export function unpackText(data: Uint8Array, version: number): string {
  const dictionary = textDictionary(version);
  const chunks: Uint8Array[] = [];
  let total = 0;
  // Output chunks of MAX + 1 bytes: a text over the limit fills the first chunk, and onData stops the inflater
  // there, so a bomb is never unpacked further.
  const inflater = new Inflate({ raw: true, windowBits: 15, dictionary, chunkSize: MAX_UNPACKED_BYTES + 1 });
  inflater.onData = (chunk) => {
    const bytes = chunk as Uint8Array;
    total += bytes.length;
    if (total > MAX_UNPACKED_BYTES) throw new TooLarge();
    chunks.push(bytes.slice());
  };
  const state = inflater as unknown as { ended: boolean; err: number; msg: string; strm: { avail_in: number; msg: string } };
  inflater.onEnd = (status) => {
    state.err = status;
    state.msg = state.strm.msg;
  };
  try {
    inflater.push(data, true);
  } catch (e) {
    if (e instanceof TooLarge) throw new PackError("too_large", `unpacks to more than ${MAX_UNPACKED_BYTES} bytes`);
    throw new PackError("malformed", `not a valid DEFLATE stream: ${e instanceof Error ? e.message : String(e)}`);
  }
  if (state.err) throw new PackError("malformed", `not a valid DEFLATE stream: ${state.msg || state.err}`);
  if (!state.ended) throw new PackError("malformed", "the DEFLATE stream is cut off");
  if (state.strm.avail_in > 0) throw new PackError("malformed", "bytes after the end of the DEFLATE stream");
  const out = new Uint8Array(total);
  let at = 0;
  for (const c of chunks) {
    out.set(c, at);
    at += c.length;
  }
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(out);
  } catch {
    throw new PackError("malformed", "the unpacked text is not valid UTF-8");
  }
}
