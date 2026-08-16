import { describe, expect, it, vi } from "vitest";

import { MODEL_BYTES } from "../src/model-metadata";
import {
  BrowserModelStore,
  validateDownloadResponse,
  writeResumableModelChunk,
  type ModelStoreDependencies,
} from "../src/model-store";

interface ResponseOptions {
  readonly body?: ReadableStream<Uint8Array> | null;
  readonly headers?: HeadersInit;
  readonly status?: number;
  readonly type?: ResponseType;
  readonly url?: string;
}

function response(options: ResponseOptions = {}): Response {
  return {
    body: options.body === undefined ? new ReadableStream<Uint8Array>() : options.body,
    headers: new Headers(options.headers),
    status: options.status ?? 200,
    type: options.type ?? "cors",
    url: options.url ?? "https://huggingface.co/model/file.litertlm",
  } as Response;
}

describe("validateDownloadResponse", () => {
  it("accepts a complete 200 response for a fresh download", () => {
    const result = validateDownloadResponse(response({
      headers: { "content-length": String(MODEL_BYTES) },
    }), 0);

    expect(result).toBeNull();
  });

  it("accepts only an exact 206 response for a resumed download", () => {
    const offset = 1_048_576;
    const result = validateDownloadResponse(response({
      status: 206,
      headers: {
        "content-length": String(MODEL_BYTES - offset),
        "content-range": `bytes ${offset}-${MODEL_BYTES - 1}/${MODEL_BYTES}`,
      },
      url: "https://us.aws.cdn.hf.co/model/file.litertlm",
    }), offset);

    expect(result).toEqual({
      start: offset,
      end: MODEL_BYTES - 1,
      total: MODEL_BYTES,
    });
  });

  it.each([
    {
      name: "opaque response",
      options: { type: "opaque", headers: { "content-length": String(MODEL_BYTES) } },
      offset: 0,
      message: "unreadable response",
    },
    {
      name: "missing body",
      options: { body: null, headers: { "content-length": String(MODEL_BYTES) } },
      offset: 0,
      message: "unreadable response",
    },
    {
      name: "untrusted redirect",
      options: {
        headers: { "content-length": String(MODEL_BYTES) },
        url: "https://example.com/model/file.litertlm",
      },
      offset: 0,
      message: "untrusted host",
    },
    {
      name: "wrong byte count",
      options: { headers: { "content-length": String(MODEL_BYTES - 1) } },
      offset: 0,
      message: "unexpected byte count",
    },
    {
      name: "range on a fresh response",
      options: {
        headers: {
          "content-length": String(MODEL_BYTES),
          "content-range": `bytes 0-${MODEL_BYTES - 1}/${MODEL_BYTES}`,
        },
      },
      offset: 0,
      message: "unexpected range",
    },
    {
      name: "200 response to a resume request",
      options: { headers: { "content-length": String(MODEL_BYTES - 1) } },
      offset: 1,
      message: "did not honor",
    },
    {
      name: "resume range with the wrong start",
      options: {
        status: 206,
        headers: {
          "content-length": String(MODEL_BYTES - 1),
          "content-range": `bytes 0-${MODEL_BYTES - 1}/${MODEL_BYTES}`,
        },
      },
      offset: 1,
      message: "did not honor",
    },
    {
      name: "resume range with the wrong total",
      options: {
        status: 206,
        headers: {
          "content-length": String(MODEL_BYTES - 1),
          "content-range": `bytes 1-${MODEL_BYTES - 1}/${MODEL_BYTES + 1}`,
        },
      },
      offset: 1,
      message: "did not honor",
    },
  ])("rejects a $name", ({ options, offset, message }) => {
    expect(() => validateDownloadResponse(response(options as ResponseOptions), offset))
      .toThrow(message);
  });
});

describe("BrowserModelStore download coordination", () => {
  it("makes a queued write-lock request abortable with the download signal", async () => {
    const controller = new AbortController();
    const request = vi.fn((
      _name: string,
      options: LockOptions,
      _callback: LockGrantedCallback<unknown>,
    ) => new Promise<never>((_resolve, reject) => {
      options.signal?.addEventListener("abort", () => reject(options.signal?.reason), {
        once: true,
      });
    }));
    const store = new BrowserModelStore({
      storage: {} as ModelStoreDependencies["storage"],
      locks: { request } as unknown as LockManager,
      fetch: vi.fn() as unknown as typeof fetch,
      now: () => new Date("2026-08-15T12:34:56.000Z"),
    });

    const pending = store.download(vi.fn(), controller.signal);
    controller.abort();

    await expect(pending).rejects.toMatchObject({ name: "AbortError" });
    expect(request).toHaveBeenCalledWith(
      expect.any(String),
      { mode: "exclusive", signal: controller.signal },
      expect.any(Function),
    );
  });
});

describe("writeResumableModelChunk", () => {
  it("handles partial writes and preserves the exact byte offsets", () => {
    const writes: Array<{ at: number; bytes: number[] }> = [];
    const handle = {
      flush: vi.fn(),
      write: vi.fn((value: AllowSharedBufferSource, options?: FileSystemReadWriteOptions) => {
        const bytes = new Uint8Array(
          value instanceof ArrayBuffer || value instanceof SharedArrayBuffer
            ? value
            : value.buffer,
          value instanceof ArrayBuffer || value instanceof SharedArrayBuffer
            ? 0
            : value.byteOffset,
          value instanceof ArrayBuffer || value instanceof SharedArrayBuffer
            ? value.byteLength
            : value.byteLength,
        );
        const count = Math.min(2, bytes.byteLength);
        writes.push({ at: options?.at ?? 0, bytes: [...bytes.subarray(0, count)] });
        return count;
      }),
    };

    const pending = writeResumableModelChunk(
      handle,
      new Uint8Array([10, 11, 12, 13, 14]),
      100,
      3,
      32,
    );

    expect(writes).toEqual([
      { at: 100, bytes: [10, 11] },
      { at: 102, bytes: [12, 13] },
      { at: 104, bytes: [14] },
    ]);
    expect(pending).toBe(8);
    expect(handle.flush).not.toHaveBeenCalled();
  });

  it("flushes after the configured persistence interval", () => {
    const handle = {
      flush: vi.fn(),
      write: vi.fn((value: AllowSharedBufferSource) => value.byteLength),
    };

    expect(writeResumableModelChunk(
      handle,
      new Uint8Array([1, 2, 3]),
      10,
      2,
      5,
    )).toBe(0);
    expect(handle.flush).toHaveBeenCalledOnce();
  });

  it("fails closed when the browser reports no write progress", () => {
    const handle = { flush: vi.fn(), write: vi.fn(() => 0) };

    expect(() => writeResumableModelChunk(
      handle,
      new Uint8Array([1]),
      0,
      0,
    )).toThrow("did not persist");
  });
});
