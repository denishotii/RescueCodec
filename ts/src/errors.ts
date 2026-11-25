export type ErrorCode =
  | "empty" | "too_large" | "unsupported_version" | "malformed" | "no_body"
  | "multiple_bodies" | "unknown_key" | "invalid_value" | "missing_field";

/** Rejected input. `code` is a stable machine-readable string (spec section 5). */
export class CodecError extends Error {
  readonly code: ErrorCode;
  readonly field: string | null;
  constructor(code: ErrorCode, message: string, field: string | null = null) {
    super(`${code}: ${message}` + (field ? ` (${field})` : ""));
    this.name = "CodecError";
    this.code = code;
    this.field = field;
  }
}
