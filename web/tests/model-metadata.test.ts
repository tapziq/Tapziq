import { describe, expect, it } from "vitest";

import {
  LITERT_LM_VERSION,
  MODEL_BYTES,
  MODEL_FILENAME,
  MODEL_REVISION,
  MODEL_SHA256,
  MODEL_URL,
  formatBytes,
  isTrustedModelResponseUrl,
  isVerifiedMetadata,
  parseContentRange,
  type VerifiedModelMetadata,
} from "../src/model-metadata";

function validMetadata(): VerifiedModelMetadata {
  return {
    schema: 1,
    filename: MODEL_FILENAME,
    revision: MODEL_REVISION,
    bytes: MODEL_BYTES,
    sha256: MODEL_SHA256,
    runtime: LITERT_LM_VERSION,
    lastModified: 1_723_456_789_000,
    verifiedAt: "2026-08-15T12:34:56.000Z",
  };
}

describe("pinned model metadata", () => {
  it("pins the exact immutable model artifact and runtime", () => {
    expect(MODEL_REVISION).toMatch(/^[0-9a-f]{40}$/);
    expect(MODEL_SHA256).toMatch(/^[0-9a-f]{64}$/);
    expect(MODEL_BYTES).toBe(2_008_432_640);
    expect(MODEL_FILENAME).toBe("gemma-4-E2B-it-web.litertlm");
    expect(LITERT_LM_VERSION).toBe("0.15.0");
    expect(MODEL_URL).toBe(
      `https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/${MODEL_REVISION}/${MODEL_FILENAME}`,
    );
  });

  it("accepts only metadata for the exact pinned artifact", () => {
    const metadata = validMetadata();
    expect(isVerifiedMetadata(metadata)).toBe(true);
    expect(isVerifiedMetadata({ ...metadata, revision: "different" })).toBe(false);
    expect(isVerifiedMetadata({ ...metadata, bytes: MODEL_BYTES - 1 })).toBe(false);
    expect(isVerifiedMetadata({ ...metadata, sha256: "0".repeat(64) })).toBe(false);
    expect(isVerifiedMetadata({ ...metadata, runtime: "0.14.0" })).toBe(false);
    expect(isVerifiedMetadata({ ...metadata, lastModified: Number.NaN })).toBe(false);
    expect(isVerifiedMetadata({ ...metadata, verifiedAt: 123 })).toBe(false);
    expect(isVerifiedMetadata(null)).toBe(false);
  });
});

describe("parseContentRange", () => {
  it("parses a complete byte range", () => {
    expect(parseContentRange("bytes 1024-2047/4096")).toEqual({
      start: 1024,
      end: 2047,
      total: 4096,
    });
  });

  it.each([
    null,
    "",
    "items 0-1/2",
    "bytes */4096",
    "bytes 2-1/4096",
    "bytes 0-4096/4096",
    "bytes 0-1/*",
    "bytes 9007199254740992-9007199254740993/9007199254740994",
  ])("rejects an invalid range: %s", (value) => {
    expect(parseContentRange(value)).toBeNull();
  });
});

describe("trusted model response URLs", () => {
  it.each([
    "https://huggingface.co/model/file",
    "https://cdn-lfs-us-1.hf.co/model/file",
    "https://us.aws.cdn.hf.co/model/file",
  ])("accepts the approved HTTPS host family: %s", (value) => {
    expect(isTrustedModelResponseUrl(value)).toBe(true);
  });

  it.each([
    "http://huggingface.co/model/file",
    "https://huggingface.co.example.com/model/file",
    "https://hf.co.example.com/model/file",
    "https://example.com/huggingface.co/model/file",
    "not a URL",
  ])("rejects an untrusted response URL: %s", (value) => {
    expect(isTrustedModelResponseUrl(value)).toBe(false);
  });
});

describe("formatBytes", () => {
  it.each([
    [Number.NaN, "0 B"],
    [-1, "0 B"],
    [0, "0 B"],
    [999, "999 B"],
    [1_500, "1.5 KB"],
    [1_500_000, "1.5 MB"],
    [2_008_432_640, "2.01 GB"],
  ])("formats %s bytes as %s", (bytes, expected) => {
    expect(formatBytes(bytes)).toBe(expected);
  });
});
