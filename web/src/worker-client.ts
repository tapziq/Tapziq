import type { ModelProgress, ModelState } from "./model-store";
import type {
  CapabilityReport,
  ProofreadWorkerResult,
  WorkerRequest,
  WorkerResponse,
  WorkerResult,
} from "./protocol";

export interface BrowserModelClient {
  probe(): Promise<CapabilityReport>;
  inspect(): Promise<ModelState>;
  download(onProgress: (progress: ModelProgress) => void): Promise<ModelState>;
  cancelDownload(): Promise<void>;
  prepareRuntime(): Promise<void>;
  remove(): Promise<void>;
  proofread(text: string): Promise<ProofreadWorkerResult>;
  cancelProofread(): Promise<void>;
  terminate(): void;
}

interface PendingRequest {
  readonly resolve: (value: WorkerResult) => void;
  readonly reject: (error: Error) => void;
  readonly onProgress?: (progress: ModelProgress) => void;
}

function remoteError(response: Extract<WorkerResponse, { type: "error" }>): Error {
  const error = new Error(response.message);
  error.name = response.name;
  return error;
}

export class WorkerModelClient implements BrowserModelClient {
  readonly #worker: Worker;
  readonly #pending = new Map<string, PendingRequest>();
  #nextId = 1;

  constructor() {
    this.#worker = new Worker(new URL("./model-worker.ts", import.meta.url), {
      name: "tapziq-model-worker",
    });
    this.#worker.addEventListener("message", (event: MessageEvent<WorkerResponse>) => {
      const response = event.data;
      const pending = this.#pending.get(response.id);
      if (pending === undefined) {
        return;
      }
      if (response.type === "progress") {
        pending.onProgress?.(response.progress);
        return;
      }
      this.#pending.delete(response.id);
      if (response.type === "error") {
        pending.reject(remoteError(response));
      } else {
        pending.resolve(response.value);
      }
    });
    this.#worker.addEventListener("error", () => {
      const error = new Error("The local model worker stopped unexpectedly.");
      for (const pending of this.#pending.values()) {
        pending.reject(error);
      }
      this.#pending.clear();
    });
  }

  async probe(): Promise<CapabilityReport> {
    return this.#request("probe") as Promise<CapabilityReport>;
  }

  async inspect(): Promise<ModelState> {
    return this.#request("inspect") as Promise<ModelState>;
  }

  async download(onProgress: (progress: ModelProgress) => void): Promise<ModelState> {
    return this.#request("download", undefined, onProgress) as Promise<ModelState>;
  }

  async cancelDownload(): Promise<void> {
    await this.#request("cancel-download");
  }

  async prepareRuntime(): Promise<void> {
    await this.#request("prepare-runtime");
  }

  async remove(): Promise<void> {
    await this.#request("remove");
  }

  async proofread(text: string): Promise<ProofreadWorkerResult> {
    return this.#request("proofread", { text }) as Promise<ProofreadWorkerResult>;
  }

  async cancelProofread(): Promise<void> {
    await this.#request("cancel-proofread");
  }

  terminate(): void {
    this.#worker.terminate();
    const error = new Error("The local model worker was closed.");
    for (const pending of this.#pending.values()) {
      pending.reject(error);
    }
    this.#pending.clear();
  }

  #request(
    type: WorkerRequest["type"],
    payload?: { readonly text: string },
    onProgress?: (progress: ModelProgress) => void,
  ): Promise<WorkerResult> {
    const id = String(this.#nextId++);
    const request = {
      id,
      type,
      ...(payload ?? {}),
    } as WorkerRequest;
    return new Promise((resolve, reject) => {
      this.#pending.set(id, { resolve, reject, ...(onProgress ? { onProgress } : {}) });
      this.#worker.postMessage(request);
    });
  }
}

export class FakeModelClient implements BrowserModelClient {
  #ready = true;
  #cancelled = false;

  constructor(private readonly delayMs = 20) {}

  async probe(): Promise<CapabilityReport> {
    return {
      secureContext: true,
      webGpu: true,
      gpuAdapter: true,
      opfs: true,
      opfsSyncAccess: true,
      webLocks: true,
    };
  }

  async inspect(): Promise<ModelState> {
    return { ready: this.#ready, storedBytes: this.#ready ? 2_008_432_640 : 0 };
  }

  async download(onProgress: (progress: ModelProgress) => void): Promise<ModelState> {
    onProgress({ phase: "downloading", bytes: 2_008_432_640, total: 2_008_432_640 });
    this.#ready = true;
    return this.inspect();
  }

  async cancelDownload(): Promise<void> {}
  async prepareRuntime(): Promise<void> {}

  async remove(): Promise<void> {
    this.#ready = false;
  }

  async proofread(text: string): Promise<ProofreadWorkerResult> {
    this.#cancelled = false;
    await new Promise((resolve) => setTimeout(resolve, this.delayMs));
    if (this.#cancelled) {
      throw new DOMException("Operation cancelled.", "AbortError");
    }
    const suggestion = text
      .replace(/\bthiss\b/giu, "This")
      .replace(/\bgrammer\b/giu, "grammar")
      .replace(/\bcant\b/giu, "can't")
      .replace(/\bteh\b/giu, "the");
    return suggestion === text ? { kind: "no-change" } : { kind: "suggestion", suggestion };
  }

  async cancelProofread(): Promise<void> {
    this.#cancelled = true;
  }

  terminate(): void {}
}
