/// <reference lib="webworker" />

import {
  Backend,
  Engine,
  SamplerType,
  getOrLoadGlobalLiteRtLm,
  type Conversation,
  type Message,
} from "@litert-lm/core";

import { BrowserModelStore, type ModelStoreDependencies } from "./model-store";
import { INFERENCE_LOCK } from "./model-metadata";
import {
  PROOFREAD_SYSTEM_INSTRUCTION,
  buildProofreadPrompt,
  parseProofreadResponse,
} from "./proofread";
import type {
  CapabilityReport,
  ProofreadWorkerResult,
  WorkerRequest,
  WorkerResponse,
  WorkerResult,
} from "./protocol";

const scope = self as DedicatedWorkerGlobalScope;
const runtimeScope = scope as DedicatedWorkerGlobalScope & {
  Module?: {
    locateFile(path: string): string;
  };
};
let store: BrowserModelStore | null = null;
let engine: Engine | null = null;
let activeConversation: Conversation | null = null;
let activeProofreadId: string | null = null;
let pendingProofreadId: string | null = null;
let cancelledProofreadId: string | null = null;
let downloadController: AbortController | null = null;
let runtimePromise: Promise<unknown> | null = null;

function post(response: WorkerResponse): void {
  scope.postMessage(response);
}

function safeMessage(error: unknown): string {
  if (error instanceof DOMException && error.name === "AbortError") {
    return "Operation cancelled.";
  }
  if (error instanceof Error && error.message.trim().length > 0) {
    return error.message.slice(0, 300);
  }
  return "The browser model operation failed.";
}

function modelStore(): BrowserModelStore {
  if (store !== null) {
    return store;
  }
  if (!("getDirectory" in navigator.storage) || !("locks" in navigator)) {
    throw new Error("This browser does not provide origin-private file storage and Web Locks.");
  }
  store = new BrowserModelStore({
    storage: navigator.storage as ModelStoreDependencies["storage"],
    locks: navigator.locks,
    fetch: globalThis.fetch.bind(globalThis),
    now: () => new Date(),
  });
  return store;
}

async function capabilities(): Promise<CapabilityReport> {
  const webGpu = "gpu" in navigator;
  let gpuAdapter = false;
  if (webGpu) {
    try {
      gpuAdapter = await navigator.gpu.requestAdapter({ powerPreference: "high-performance" })
        !== null;
    } catch {
      gpuAdapter = false;
    }
  }
  return {
    secureContext: scope.isSecureContext,
    webGpu,
    gpuAdapter,
    opfs: "getDirectory" in navigator.storage,
    opfsSyncAccess: typeof FileSystemFileHandle !== "undefined"
      && typeof FileSystemFileHandle.prototype.createSyncAccessHandle === "function",
    webLocks: "locks" in navigator,
  };
}

async function ensureRuntime(): Promise<void> {
  if (runtimePromise === null) {
    const runtimeUrl = new URL("../wasm/", import.meta.url).href;
    // Emscripten otherwise resolves the imported loader's .wasm file relative
    // to this worker script (/assets/) instead of the self-hosted /wasm/ path.
    runtimeScope.Module = {
      locateFile: (filename) => new URL(filename, runtimeUrl).href,
    };
    runtimePromise = getOrLoadGlobalLiteRtLm(runtimeUrl).catch((error: unknown) => {
      runtimeScope.Module = undefined;
      runtimePromise = null;
      throw error;
    });
  }
  await runtimePromise;
}

async function ensureEngine(): Promise<Engine> {
  const modelState = await modelStore().inspect();
  if (!modelState.ready) {
    await disposeEngine();
    throw new Error("The verified browser model is missing or has changed.");
  }
  if (engine !== null) {
    return engine;
  }
  await ensureRuntime();
  engine = await modelStore().withVerifiedFile(async (model) => Engine.create({
    model,
    backend: Backend.GPU_ARTISAN,
    mainExecutorSettings: {
      maxNumTokens: 1_536,
    },
  }));
  return engine;
}

function messageText(message: Message): string {
  if (typeof message.content === "string") {
    return message.content;
  }
  if (!Array.isArray(message.content)) {
    return "";
  }
  return message.content
    .filter((part) => part.type === "text")
    .map((part) => part.text)
    .join("");
}

async function disposeEngine(): Promise<void> {
  activeConversation?.cancel();
  const conversation = activeConversation;
  activeConversation = null;
  if (conversation !== null) {
    await conversation.delete().catch(() => undefined);
  }
  const currentEngine = engine;
  engine = null;
  if (currentEngine !== null) {
    await currentEngine.delete().catch(() => undefined);
  }
}

function cancelCurrentProofread(): void {
  if (activeProofreadId !== null || pendingProofreadId !== null) {
    cancelledProofreadId = activeProofreadId ?? pendingProofreadId;
  }
  activeConversation?.cancel();
}

async function proofread(id: string, text: string): Promise<ProofreadWorkerResult> {
  if (pendingProofreadId !== null || activeProofreadId !== null) {
    throw new Error("A proofreading request is already active.");
  }
  pendingProofreadId = id;
  try {
    return await navigator.locks.request(
      INFERENCE_LOCK,
      { mode: "exclusive" },
      async () => {
        if (pendingProofreadId === id) {
          pendingProofreadId = null;
        }
        activeProofreadId = id;
        if (cancelledProofreadId === id) {
          throw new DOMException("Operation cancelled.", "AbortError");
        }
        const localEngine = await ensureEngine();
        if (cancelledProofreadId === id) {
          throw new DOMException("Operation cancelled.", "AbortError");
        }
        const conversation = await localEngine.createConversation({
          preface: {
            messages: [{ role: "system", content: PROOFREAD_SYSTEM_INSTRUCTION }],
          },
          sessionConfig: {
            samplerParams: { type: SamplerType.GREEDY, k: 1 },
            maxOutputTokens: 750,
          },
        });
        activeConversation = conversation;
        try {
          if (cancelledProofreadId === id) {
            throw new DOMException("Operation cancelled.", "AbortError");
          }
        const response = await conversation.sendMessage(buildProofreadPrompt(text));
          if (cancelledProofreadId === id) {
            throw new DOMException("Operation cancelled.", "AbortError");
          }
        const rawResponse = messageText(response).trim();
        const corrected = parseProofreadResponse(rawResponse, text);
        if (corrected === null) {
          throw new Error("Gemma returned a response that was not safe to preview.");
          }
          return corrected === text
            ? { kind: "no-change" }
            : { kind: "suggestion", suggestion: corrected };
        } finally {
          if (activeConversation === conversation) {
            activeConversation = null;
          }
          await conversation.delete().catch(() => undefined);
        }
      },
    );
  } finally {
    if (pendingProofreadId === id) {
      pendingProofreadId = null;
    }
    if (activeProofreadId === id) {
      activeProofreadId = null;
    }
    if (cancelledProofreadId === id) {
      cancelledProofreadId = null;
    }
  }
}

async function handle(request: WorkerRequest): Promise<WorkerResult> {
  switch (request.type) {
    case "probe":
      return capabilities();
    case "inspect":
      return modelStore().inspect();
    case "download": {
      if (downloadController !== null) {
        throw new Error("A model download is already active.");
      }
      downloadController = new AbortController();
      try {
        return await modelStore().download(
          (progress) => post({ type: "progress", id: request.id, progress }),
          downloadController.signal,
        );
      } finally {
        downloadController = null;
      }
    }
    case "cancel-download":
      downloadController?.abort();
      return null;
    case "prepare-runtime":
      await ensureRuntime();
      return null;
    case "remove":
      downloadController?.abort();
      cancelCurrentProofread();
      await navigator.locks.request(
        INFERENCE_LOCK,
        { mode: "exclusive" },
        async () => {
          await disposeEngine();
          await modelStore().remove();
        },
      );
      return null;
    case "proofread":
      return proofread(request.id, request.text);
    case "cancel-proofread":
      cancelCurrentProofread();
      return null;
  }
}

scope.addEventListener("message", (event: MessageEvent<WorkerRequest>) => {
  const request = event.data;
  void handle(request)
    .then((value) => post({ type: "result", id: request.id, value }))
    .catch((error: unknown) => post({
      type: "error",
      id: request.id,
      name: error instanceof Error ? error.name : "Error",
      message: safeMessage(error),
    }));
});
