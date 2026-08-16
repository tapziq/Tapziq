export const MODEL_REVISION = "6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94";
export const MODEL_FILENAME = "gemma-4-E2B-it-web.litertlm";
export const MODEL_BYTES = 2_008_432_640;
export const MODEL_SHA256 = "3a08e8d94e23b814ae5414469c370c503813949acb8ceaa17e4ebf8a35af35b5";
export const MODEL_URL =
  "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/"
  + `${MODEL_REVISION}/${MODEL_FILENAME}`;
export const MODEL_HEADROOM_BYTES = 512 * 1024 * 1024;
export const LITERT_LM_VERSION = "0.15.0";

export const STORAGE_DIRECTORY = "tapziq-browser-models";
export const METADATA_FILENAME = "gemma-4-E2B-it-web.verified.json";
export const MODEL_LOCK = "tapziq-browser-model-write-v1";
export const INFERENCE_LOCK = "tapziq-browser-inference-v1";

export interface VerifiedModelMetadata {
  readonly schema: 1;
  readonly filename: typeof MODEL_FILENAME;
  readonly revision: typeof MODEL_REVISION;
  readonly bytes: typeof MODEL_BYTES;
  readonly sha256: typeof MODEL_SHA256;
  readonly runtime: typeof LITERT_LM_VERSION;
  readonly lastModified: number;
  readonly verifiedAt: string;
}

export interface ParsedContentRange {
  readonly start: number;
  readonly end: number;
  readonly total: number;
}

export function parseContentRange(value: string | null): ParsedContentRange | null {
  if (value === null) {
    return null;
  }
  const match = /^bytes (\d+)-(\d+)\/(\d+)$/.exec(value.trim());
  if (match === null) {
    return null;
  }
  const start = Number(match[1]);
  const end = Number(match[2]);
  const total = Number(match[3]);
  if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end)
      || !Number.isSafeInteger(total) || start < 0 || end < start || end >= total) {
    return null;
  }
  return { start, end, total };
}

export function isTrustedModelResponseUrl(value: string): boolean {
  try {
    const url = new URL(value);
    return url.protocol === "https:"
      && (url.hostname === "huggingface.co" || url.hostname.endsWith(".hf.co"));
  } catch {
    return false;
  }
}

export function isVerifiedMetadata(value: unknown): value is VerifiedModelMetadata {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const metadata = value as Partial<VerifiedModelMetadata>;
  return metadata.schema === 1
    && metadata.filename === MODEL_FILENAME
    && metadata.revision === MODEL_REVISION
    && metadata.bytes === MODEL_BYTES
    && metadata.sha256 === MODEL_SHA256
    && metadata.runtime === LITERT_LM_VERSION
    && typeof metadata.lastModified === "number"
    && Number.isFinite(metadata.lastModified)
    && typeof metadata.verifiedAt === "string";
}

export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) {
    return "0 B";
  }
  if (bytes >= 1_000_000_000) {
    return `${(bytes / 1_000_000_000).toFixed(2)} GB`;
  }
  if (bytes >= 1_000_000) {
    return `${(bytes / 1_000_000).toFixed(1)} MB`;
  }
  if (bytes >= 1_000) {
    return `${(bytes / 1_000).toFixed(1)} KB`;
  }
  return `${bytes} B`;
}
