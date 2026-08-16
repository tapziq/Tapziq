import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex } from "@noble/hashes/utils.js";

import {
  LITERT_LM_VERSION,
  METADATA_FILENAME,
  MODEL_BYTES,
  MODEL_FILENAME,
  MODEL_LOCK,
  MODEL_REVISION,
  MODEL_SHA256,
  MODEL_URL,
  STORAGE_DIRECTORY,
  type ParsedContentRange,
  type VerifiedModelMetadata,
  isTrustedModelResponseUrl,
  isVerifiedMetadata,
  parseContentRange,
} from "./model-metadata";

export interface ModelProgress {
  readonly phase: "checking" | "downloading" | "verifying";
  readonly bytes: number;
  readonly total: number;
}

export interface ModelState {
  readonly ready: boolean;
  readonly storedBytes: number;
  readonly verifiedAt?: string;
}

interface StorageManagerWithDirectory extends StorageManager {
  getDirectory(): Promise<FileSystemDirectoryHandle>;
}

export interface ModelStoreDependencies {
  readonly storage: StorageManagerWithDirectory;
  readonly locks: LockManager;
  readonly fetch: typeof fetch;
  readonly now: () => Date;
}

export const MODEL_FLUSH_INTERVAL_BYTES = 16 * 1024 * 1024;

type ResumableWriteHandle = Pick<FileSystemSyncAccessHandle, "flush" | "write">;

export function writeResumableModelChunk(
  handle: ResumableWriteHandle,
  chunk: Uint8Array,
  offset: number,
  bytesSinceFlush: number,
  flushInterval = MODEL_FLUSH_INTERVAL_BYTES,
): number {
  if (!Number.isSafeInteger(offset) || offset < 0
      || !Number.isSafeInteger(bytesSinceFlush) || bytesSinceFlush < 0
      || !Number.isSafeInteger(flushInterval) || flushInterval <= 0) {
    throw new Error("The resumable model write position is invalid.");
  }

  let written = 0;
  while (written < chunk.byteLength) {
    const count = handle.write(chunk.subarray(written), { at: offset + written });
    if (!Number.isSafeInteger(count) || count <= 0 || count > chunk.byteLength - written) {
      throw new Error("The browser did not persist the expected model bytes.");
    }
    written += count;
  }

  const pendingBytes = bytesSinceFlush + written;
  if (pendingBytes >= flushInterval) {
    handle.flush();
    return 0;
  }
  return pendingBytes;
}

function abortError(): DOMException {
  return new DOMException("Operation cancelled.", "AbortError");
}

function throwIfAborted(signal: AbortSignal): void {
  if (signal.aborted) {
    throw abortError();
  }
}

function isNotFound(error: unknown): boolean {
  return error instanceof DOMException && error.name === "NotFoundError";
}

async function fileOrNull(
  directory: FileSystemDirectoryHandle,
  filename: string,
): Promise<File | null> {
  try {
    return await (await directory.getFileHandle(filename)).getFile();
  } catch (error) {
    if (isNotFound(error)) {
      return null;
    }
    throw error;
  }
}

async function removeIfPresent(
  directory: FileSystemDirectoryHandle,
  filename: string,
): Promise<void> {
  try {
    await directory.removeEntry(filename);
  } catch (error) {
    if (!isNotFound(error)) {
      throw error;
    }
  }
}

export function validateDownloadResponse(
  response: Response,
  offset: number,
): ParsedContentRange | null {
  if (response.type === "opaque" || response.body === null) {
    throw new Error("The model server returned an unreadable response.");
  }
  if (!isTrustedModelResponseUrl(response.url)) {
    throw new Error("The model download redirected to an untrusted host.");
  }
  const expectedBytes = MODEL_BYTES - offset;
  if (Number(response.headers.get("content-length")) !== expectedBytes) {
    throw new Error("The model server returned an unexpected byte count.");
  }

  const contentRange = parseContentRange(response.headers.get("content-range"));
  if (offset === 0 && response.status === 200) {
    if (contentRange !== null) {
      throw new Error("The model server returned an unexpected range.");
    }
    return null;
  }
  if (response.status !== 206 || contentRange === null
      || contentRange.start !== offset || contentRange.end !== MODEL_BYTES - 1
      || contentRange.total !== MODEL_BYTES) {
    throw new Error("The model server did not honor the requested resume range.");
  }
  return contentRange;
}

export class BrowserModelStore {
  readonly #dependencies: ModelStoreDependencies;

  constructor(dependencies: ModelStoreDependencies) {
    this.#dependencies = dependencies;
  }

  async inspect(): Promise<ModelState> {
    return this.#dependencies.locks.request(
      MODEL_LOCK,
      { mode: "shared" },
      async () => this.#inspectUnlocked(),
    );
  }

  async download(
    onProgress: (progress: ModelProgress) => void,
    signal: AbortSignal,
  ): Promise<ModelState> {
    return this.#dependencies.locks.request(
      MODEL_LOCK,
      { mode: "exclusive", signal },
      async () => this.#downloadUnlocked(onProgress, signal),
    );
  }

  async remove(): Promise<void> {
    await this.#dependencies.locks.request(
      MODEL_LOCK,
      { mode: "exclusive" },
      async () => {
        const directory = await this.#directory();
        await removeIfPresent(directory, METADATA_FILENAME);
        await removeIfPresent(directory, MODEL_FILENAME);
      },
    );
  }

  async withVerifiedFile<T>(callback: (file: File) => Promise<T>): Promise<T> {
    return this.#dependencies.locks.request(
      MODEL_LOCK,
      { mode: "shared" },
      async () => {
        const directory = await this.#directory();
        const file = await fileOrNull(directory, MODEL_FILENAME);
        const metadata = await this.#readMetadata(directory);
        if (file === null || metadata === null || file.size !== MODEL_BYTES
            || file.lastModified !== metadata.lastModified) {
          throw new Error("The verified browser model is missing or has changed.");
        }
        return callback(file);
      },
    );
  }

  async #directory(): Promise<FileSystemDirectoryHandle> {
    const root = await this.#dependencies.storage.getDirectory();
    return root.getDirectoryHandle(STORAGE_DIRECTORY, { create: true });
  }

  async #readMetadata(
    directory: FileSystemDirectoryHandle,
  ): Promise<VerifiedModelMetadata | null> {
    const file = await fileOrNull(directory, METADATA_FILENAME);
    if (file === null) {
      return null;
    }
    try {
      const parsed: unknown = JSON.parse(await file.text());
      return isVerifiedMetadata(parsed) ? parsed : null;
    } catch {
      return null;
    }
  }

  async #writeMetadata(
    directory: FileSystemDirectoryHandle,
    model: File,
  ): Promise<VerifiedModelMetadata> {
    const metadata: VerifiedModelMetadata = {
      schema: 1,
      filename: MODEL_FILENAME,
      revision: MODEL_REVISION,
      bytes: MODEL_BYTES,
      sha256: MODEL_SHA256,
      runtime: LITERT_LM_VERSION,
      lastModified: model.lastModified,
      verifiedAt: this.#dependencies.now().toISOString(),
    };
    const handle = await directory.getFileHandle(METADATA_FILENAME, { create: true });
    const writable = await handle.createWritable();
    await writable.write(`${JSON.stringify(metadata)}\n`);
    await writable.close();
    return metadata;
  }

  async #inspectUnlocked(): Promise<ModelState> {
    const directory = await this.#directory();
    const file = await fileOrNull(directory, MODEL_FILENAME);
    if (file === null) {
      return { ready: false, storedBytes: 0 };
    }
    const metadata = await this.#readMetadata(directory);
    const ready = metadata !== null && file.size === MODEL_BYTES
      && file.lastModified === metadata.lastModified;
    return {
      ready,
      storedBytes: file.size,
      ...(ready ? { verifiedAt: metadata.verifiedAt } : {}),
    };
  }

  async #downloadUnlocked(
    onProgress: (progress: ModelProgress) => void,
    signal: AbortSignal,
  ): Promise<ModelState> {
    throwIfAborted(signal);
    const ready = await this.#inspectUnlocked();
    if (ready.ready) {
      return ready;
    }

    const directory = await this.#directory();
    await removeIfPresent(directory, METADATA_FILENAME);
    let modelFile = await fileOrNull(directory, MODEL_FILENAME);
    if (modelFile !== null && modelFile.size > MODEL_BYTES) {
      await removeIfPresent(directory, MODEL_FILENAME);
      modelFile = null;
    }

    const handle = await directory.getFileHandle(MODEL_FILENAME, { create: true });
    modelFile = await handle.getFile();
    const hasher = sha256.create();
    let completedBytes = 0;

    if (modelFile.size > 0) {
      const reader = modelFile.stream().getReader();
      while (true) {
        throwIfAborted(signal);
        const { done, value } = await reader.read();
        if (done) {
          break;
        }
        hasher.update(value);
        completedBytes += value.byteLength;
        onProgress({ phase: "checking", bytes: completedBytes, total: MODEL_BYTES });
      }
    }

    if (completedBytes < MODEL_BYTES) {
      const headers = new Headers();
      if (completedBytes > 0) {
        headers.set("Range", `bytes=${completedBytes}-`);
      }
      const response = await this.#dependencies.fetch(MODEL_URL, {
        method: "GET",
        mode: "cors",
        credentials: "omit",
        cache: "no-store",
        redirect: "follow",
        referrerPolicy: "no-referrer",
        headers,
        signal,
      });
      if (!response.ok) {
        throw new Error(`Model download failed with HTTP ${response.status}.`);
      }
      validateDownloadResponse(response, completedBytes);

      if (typeof handle.createSyncAccessHandle !== "function") {
        throw new Error("This browser cannot safely resume the model download.");
      }
      const reader = response.body!.getReader();
      const accessHandle = await handle.createSyncAccessHandle();
      if (accessHandle.getSize() !== completedBytes) {
        accessHandle.close();
        throw new Error("The saved model bytes changed before the download resumed.");
      }
      let bytesSinceFlush = 0;
      try {
        while (true) {
          throwIfAborted(signal);
          const { done, value } = await reader.read();
          if (done) {
            break;
          }
          if (completedBytes + value.byteLength > MODEL_BYTES) {
            throw new Error("The model server sent more data than expected.");
          }
          bytesSinceFlush = writeResumableModelChunk(
            accessHandle,
            value,
            completedBytes,
            bytesSinceFlush,
          );
          hasher.update(value);
          completedBytes += value.byteLength;
          onProgress({ phase: "downloading", bytes: completedBytes, total: MODEL_BYTES });
        }
      } finally {
        try {
          accessHandle.flush();
        } finally {
          accessHandle.close();
        }
      }
    }

    throwIfAborted(signal);
    if (completedBytes !== MODEL_BYTES) {
      throw new Error("The model download ended before every byte arrived.");
    }
    onProgress({ phase: "verifying", bytes: completedBytes, total: MODEL_BYTES });
    const digest = bytesToHex(hasher.digest());
    if (digest !== MODEL_SHA256) {
      await removeIfPresent(directory, MODEL_FILENAME);
      throw new Error("The model SHA-256 did not match; the downloaded file was removed.");
    }

    const verifiedFile = await handle.getFile();
    if (verifiedFile.size !== MODEL_BYTES) {
      await removeIfPresent(directory, MODEL_FILENAME);
      throw new Error("The verified model changed before installation.");
    }
    const metadata = await this.#writeMetadata(directory, verifiedFile);
    return {
      ready: true,
      storedBytes: verifiedFile.size,
      verifiedAt: metadata.verifiedAt,
    };
  }
}
