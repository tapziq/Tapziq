import type { ModelProgress, ModelState } from "./model-store";

export interface CapabilityReport {
  readonly secureContext: boolean;
  readonly webGpu: boolean;
  readonly gpuAdapter: boolean;
  readonly opfs: boolean;
  readonly opfsSyncAccess: boolean;
  readonly webLocks: boolean;
}

export type ProofreadWorkerResult =
  | { readonly kind: "suggestion"; readonly suggestion: string }
  | { readonly kind: "no-change" };

export type WorkerRequest =
  | { readonly id: string; readonly type: "probe" }
  | { readonly id: string; readonly type: "inspect" }
  | { readonly id: string; readonly type: "download" }
  | { readonly id: string; readonly type: "cancel-download" }
  | { readonly id: string; readonly type: "prepare-runtime" }
  | { readonly id: string; readonly type: "remove" }
  | { readonly id: string; readonly type: "proofread"; readonly text: string }
  | { readonly id: string; readonly type: "cancel-proofread" };

export type WorkerResult = CapabilityReport | ModelState | ProofreadWorkerResult | null;

export type WorkerResponse =
  | { readonly type: "result"; readonly id: string; readonly value: WorkerResult }
  | { readonly type: "progress"; readonly id: string; readonly progress: ModelProgress }
  | {
      readonly type: "error";
      readonly id: string;
      readonly name: string;
      readonly message: string;
    };
