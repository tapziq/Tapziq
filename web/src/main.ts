import "./styles.css";

import {
  AUTO_CORRECT_DEBOUNCE_MS,
  applyAutoCorrectSuggestion,
  applyAutoCorrectUndo,
  captureAutoCorrectSnapshot,
  createAutoCorrectUndo,
  editorStatesMatch,
  type AutoCorrectUndo,
} from "./autocorrect";
import {
  applyPendingSuggestion,
  captureEditorSnapshot,
  createPendingSuggestion,
  dismissPendingSuggestion,
  snapshotMatches,
  type EditorSnapshot,
  type EditorState,
  type PendingSuggestion,
} from "./editor-session";
import { applyVirtualKey, type VirtualKey } from "./keyboard";
import {
  MODEL_BYTES,
  MODEL_HEADROOM_BYTES,
  formatBytes,
} from "./model-metadata";
import type { ModelState } from "./model-store";
import {
  FakeModelClient,
  WorkerModelClient,
  type BrowserModelClient,
} from "./worker-client";

function requiredElement<T extends HTMLElement>(id: string): T {
  const element = document.getElementById(id);
  if (element === null) {
    throw new Error(`Missing required element: ${id}`);
  }
  return element as T;
}

const editor = requiredElement<HTMLTextAreaElement>("editor");
const characterCount = requiredElement<HTMLSpanElement>("character-count");
const keyboard = requiredElement<HTMLDivElement>("keyboard");
const preview = requiredElement<HTMLDivElement>("preview");
const previewText = requiredElement<HTMLPreElement>("preview-text");
const applyButton = requiredElement<HTMLButtonElement>("apply-button");
const dismissButton = requiredElement<HTMLButtonElement>("dismiss-button");
const proofreadButton = requiredElement<HTMLButtonElement>("proofread-button");
const cancelProofreadButton = requiredElement<HTMLButtonElement>("cancel-proofread-button");
const modelBadge = requiredElement<HTMLSpanElement>("model-badge");
const downloadButton = requiredElement<HTMLButtonElement>("download-button");
const cancelDownloadButton = requiredElement<HTMLButtonElement>("cancel-download-button");
const removeButton = requiredElement<HTMLButtonElement>("remove-button");
const downloadProgress = requiredElement<HTMLProgressElement>("download-progress");
const downloadDetail = requiredElement<HTMLParagraphElement>("download-detail");
const status = requiredElement<HTMLParagraphElement>("status");
const autoCorrectToggle = requiredElement<HTMLInputElement>("autocorrect-toggle");
const autoCorrectStatus = requiredElement<HTMLParagraphElement>("autocorrect-status");
const undoAutoCorrectButton = requiredElement<HTMLButtonElement>(
  "undo-autocorrect-button",
);

const query = new URLSearchParams(location.search);
const requestedFakeDelayText = query.get("test-model-delay");
const requestedFakeDelay = Number(requestedFakeDelayText);
const fakeDelay = requestedFakeDelayText !== null && Number.isFinite(requestedFakeDelay)
  ? Math.max(0, Math.min(1_000, requestedFakeDelay))
  : undefined;
const client: BrowserModelClient = import.meta.env.VITE_ALLOW_FAKE_MODEL === "true"
  && query.get("test-model") === "1"
  ? new FakeModelClient(fakeDelay)
  : new WorkerModelClient();

let modelState: ModelState = { ready: false, storedBytes: 0 };
let capabilitiesReady = false;
let modelBusy = false;
let proofreadBusy = false;
let autoCorrectBusy = false;
let pendingSuggestion: PendingSuggestion | null = null;
let activeSnapshot: EditorSnapshot | null = null;
let autoCorrectGeneration = 0;
let scheduledAutoCorrect: {
  readonly generation: number;
  readonly snapshot: EditorSnapshot;
  readonly timer: number;
} | null = null;
let activeAutoCorrect: {
  readonly generation: number;
  readonly snapshot: EditorSnapshot;
  cancelRequested: boolean;
} | null = null;
let autoCorrectUndo: AutoCorrectUndo | null = null;
let shift = false;

function setStatus(message: string): void {
  status.textContent = message;
}

function setAutoCorrectStatus(message: string): void {
  autoCorrectStatus.textContent = message;
}

function editorState(): EditorState {
  return {
    text: editor.value,
    selectionStart: editor.selectionStart,
    selectionEnd: editor.selectionEnd,
  };
}

function writeEditorState(next: EditorState): void {
  editor.value = next.text;
  editor.setSelectionRange(next.selectionStart, next.selectionEnd);
  updateEditorUi();
  editor.focus();
}

function inferenceBusy(): boolean {
  return proofreadBusy || autoCorrectBusy;
}

function updateEditorUi(): void {
  characterCount.textContent = `${editor.value.length.toLocaleString()} / 2,000`;
  if (pendingSuggestion !== null) {
    const valid = snapshotMatches(editorState(), pendingSuggestion.snapshot);
    applyButton.disabled = !valid;
    preview.classList.toggle("stale", !valid);
  }
  proofreadButton.disabled = !modelState.ready || inferenceBusy() || modelBusy
    || editor.value.trim().length === 0;
  downloadButton.disabled = !capabilitiesReady || modelBusy || inferenceBusy();
  removeButton.disabled = inferenceBusy() || modelBusy;
  autoCorrectToggle.disabled = !capabilitiesReady || !modelState.ready || modelBusy;
  undoAutoCorrectButton.hidden = autoCorrectUndo === null;
  undoAutoCorrectButton.disabled = autoCorrectBusy
    || autoCorrectUndo === null
    || !editorStatesMatch(editorState(), autoCorrectUndo.after);
}

function renderModelState(): void {
  if (modelBusy) {
    modelBadge.textContent = "Working";
    modelBadge.className = "badge busy";
  } else if (modelState.ready) {
    modelBadge.textContent = "Verified · offline ready";
    modelBadge.className = "badge ready";
  } else if (modelState.storedBytes > 0) {
    modelBadge.textContent = `Paused · ${formatBytes(modelState.storedBytes)}`;
    modelBadge.className = "badge paused";
  } else {
    modelBadge.textContent = "Not downloaded";
    modelBadge.className = "badge";
  }
  downloadButton.hidden = modelState.ready;
  downloadButton.disabled = !capabilitiesReady || modelBusy;
  downloadButton.textContent = modelState.storedBytes > 0
    ? "Resume verified download"
    : "Download verified model";
  removeButton.hidden = modelState.storedBytes === 0 || modelBusy;
  updateEditorUi();
}

function setModelBusy(busy: boolean, downloading = false): void {
  modelBusy = busy;
  cancelDownloadButton.hidden = !downloading;
  downloadProgress.hidden = !downloading;
  downloadDetail.hidden = !downloading;
  renderModelState();
}

function setProofreadBusy(busy: boolean): void {
  proofreadBusy = busy;
  cancelProofreadButton.hidden = !busy;
  proofreadButton.textContent = busy
    ? "Proofreading locally…"
    : "Proofread with browser Gemma 4";
  updateEditorUi();
}

function setAutoCorrectBusy(busy: boolean): void {
  autoCorrectBusy = busy;
  updateEditorUi();
}

function showSuggestion(pending: PendingSuggestion): void {
  pendingSuggestion = pending;
  previewText.textContent = pending.preview;
  preview.hidden = false;
  preview.classList.remove("stale");
  applyButton.disabled = false;
  preview.scrollIntoView({ behavior: "smooth", block: "nearest" });
}

function hideSuggestion(): void {
  pendingSuggestion = null;
  previewText.textContent = "";
  preview.hidden = true;
  preview.classList.remove("stale");
}

function clearAutoCorrectUndo(): void {
  autoCorrectUndo = null;
  updateEditorUi();
}

function stopAutoCorrectWork(): boolean {
  autoCorrectGeneration += 1;
  const hadScheduled = scheduledAutoCorrect !== null;
  if (scheduledAutoCorrect !== null) {
    window.clearTimeout(scheduledAutoCorrect.timer);
    scheduledAutoCorrect = null;
  }

  const request = activeAutoCorrect;
  if (request !== null && !request.cancelRequested) {
    request.cancelRequested = true;
    void client.cancelProofread().catch(() => undefined);
  }
  return hadScheduled || request !== null;
}

function autoCorrectIdleMessage(): string {
  return autoCorrectToggle.checked
    ? "Auto-correct is on. It waits for a pause after a virtual word boundary."
    : "Auto-correct is off. Turn it on to check virtual-keyboard word boundaries.";
}

function scheduleAutoCorrectTimer(
  snapshot: EditorSnapshot,
  generation: number,
  delay: number,
): void {
  const timer = window.setTimeout(() => {
    if (scheduledAutoCorrect?.generation === generation) {
      scheduledAutoCorrect = null;
    }
    void runAutoCorrect(snapshot, generation);
  }, delay);
  scheduledAutoCorrect = { generation, snapshot, timer };
}

function scheduleAutoCorrect(key: VirtualKey, next: EditorState): void {
  if (!autoCorrectToggle.checked || !capabilitiesReady || !modelState.ready) {
    return;
  }
  const snapshot = captureAutoCorrectSnapshot(next, key);
  if (snapshot === null) {
    setAutoCorrectStatus(autoCorrectIdleMessage());
    return;
  }

  const generation = ++autoCorrectGeneration;
  scheduleAutoCorrectTimer(snapshot, generation, AUTO_CORRECT_DEBOUNCE_MS);
  setAutoCorrectStatus("Auto-correct is waiting briefly for you to finish typing…");
}

async function runAutoCorrect(
  snapshot: EditorSnapshot,
  generation: number,
): Promise<void> {
  if (
    generation !== autoCorrectGeneration
    || !autoCorrectToggle.checked
    || !capabilitiesReady
    || !modelState.ready
  ) {
    return;
  }
  if (!snapshotMatches(editorState(), snapshot)) {
    setAutoCorrectStatus(
      "Auto-correct discarded an outdated check. Your newer text was not changed.",
    );
    return;
  }
  if (inferenceBusy() || modelBusy) {
    scheduleAutoCorrectTimer(snapshot, generation, 100);
    return;
  }

  const request = { generation, snapshot, cancelRequested: false };
  activeAutoCorrect = request;
  setAutoCorrectBusy(true);
  setAutoCorrectStatus(
    "Auto-correct is checking up to 500 recent characters locally with Gemma 4…",
  );
  try {
    const result = await client.proofread(snapshot.targetText);
    if (
      generation !== autoCorrectGeneration
      || activeAutoCorrect !== request
      || !autoCorrectToggle.checked
      || !snapshotMatches(editorState(), snapshot)
    ) {
      if (generation === autoCorrectGeneration) {
        setAutoCorrectStatus(
          "Auto-correct discarded an outdated result. Your newer text was not changed.",
        );
      }
      return;
    }
    if (result.kind === "no-change") {
      setAutoCorrectStatus("Gemma checked the recent text locally; no change was needed.");
      return;
    }

    const pending = createPendingSuggestion(snapshot, result.suggestion);
    const before = editorState();
    const applied = applyAutoCorrectSuggestion(before, pending, editor.maxLength);
    if (applied.outcome === "stale") {
      setAutoCorrectStatus(
        "Auto-correct discarded an outdated result. Your newer text was not changed.",
      );
      return;
    }
    if (applied.outcome === "unsafe") {
      setAutoCorrectStatus(
        "Gemma returned an auto-correction that was not safe to apply. The editor was not changed.",
      );
      return;
    }

    autoCorrectUndo = createAutoCorrectUndo(before, applied.editor);
    writeEditorState(applied.editor);
    setAutoCorrectStatus("Gemma auto-corrected the recent text locally. Undo is available.");
  } catch (error) {
    if (generation !== autoCorrectGeneration || activeAutoCorrect !== request) {
      return;
    }
    setAutoCorrectStatus(error instanceof Error && error.name === "AbortError"
      ? "Auto-correct stopped. The editor was not changed."
      : `Auto-correct failed safely: ${error instanceof Error ? error.message : "Unknown error."}`);
  } finally {
    if (activeAutoCorrect === request) {
      activeAutoCorrect = null;
      setAutoCorrectBusy(false);
    }
  }
}

const letterRows = [
  ["q", "w", "e", "r", "t", "y", "u", "i", "o", "p"],
  ["a", "s", "d", "f", "g", "h", "j", "k", "l"],
  ["z", "x", "c", "v", "b", "n", "m"],
];

function keyboardButton(label: string, key: VirtualKey, className = ""): HTMLButtonElement {
  const button = document.createElement("button");
  button.type = "button";
  button.className = `key ${className}`.trim();
  button.textContent = label;
  button.setAttribute("aria-label", label);
  button.addEventListener("pointerdown", (event) => event.preventDefault());
  button.addEventListener("click", () => {
    stopAutoCorrectWork();
    autoCorrectUndo = null;
    const next = applyVirtualKey(editorState(), key);
    writeEditorState(next);
    scheduleAutoCorrect(key, next);
    if (key.kind === "text" && shift) {
      shift = false;
      renderKeyboard();
    }
  });
  return button;
}

function renderKeyboard(): void {
  keyboard.replaceChildren();
  for (const letters of letterRows) {
    const row = document.createElement("div");
    row.className = "key-row";
    if (letters === letterRows[2]) {
      const shiftButton = document.createElement("button");
      shiftButton.type = "button";
      shiftButton.className = `key special${shift ? " active" : ""}`;
      shiftButton.textContent = "⇧";
      shiftButton.setAttribute("aria-label", shift ? "Turn shift off" : "Turn shift on");
      shiftButton.addEventListener("pointerdown", (event) => event.preventDefault());
      shiftButton.addEventListener("click", () => {
        shift = !shift;
        renderKeyboard();
        editor.focus();
      });
      row.append(shiftButton);
    }
    for (const letter of letters) {
      const value = shift ? letter.toUpperCase() : letter;
      row.append(keyboardButton(value, { kind: "text", text: value }));
    }
    if (letters === letterRows[2]) {
      row.append(keyboardButton("⌫", { kind: "backspace" }, "special backspace"));
    }
    keyboard.append(row);
  }
  const actionRow = document.createElement("div");
  actionRow.className = "key-row action-row";
  actionRow.append(
    keyboardButton(",", { kind: "text", text: "," }),
    keyboardButton("space", { kind: "space" }, "space"),
    keyboardButton(".", { kind: "text", text: "." }),
    keyboardButton("↵", { kind: "enter" }, "special enter"),
  );
  keyboard.append(actionRow);
}

async function registerServiceWorker(): Promise<void> {
  if (!import.meta.env.PROD || !("serviceWorker" in navigator)) {
    return;
  }
  const serviceWorkerUrl = new URL("../sw.js", import.meta.url);
  const scopeUrl = new URL("../", import.meta.url);
  await navigator.serviceWorker.register(serviceWorkerUrl, { scope: scopeUrl.pathname });
  await navigator.serviceWorker.ready;
}

async function checkCapabilities(): Promise<void> {
  const report = await client.probe();
  const missing = [
    !report.secureContext && "a secure HTTPS or localhost context",
    !report.webGpu && "WebGPU",
    report.webGpu && !report.gpuAdapter && "an available GPU adapter",
    !report.opfs && "origin-private file storage",
    report.opfs && !report.opfsSyncAccess && "resumable origin-private file access",
    !report.webLocks && "cross-tab Web Locks",
  ].filter((item): item is string => typeof item === "string");
  capabilitiesReady = missing.length === 0;
  if (capabilitiesReady) {
    setStatus("Browser checks passed. The editor is local; download Gemma only when ready.");
  } else {
    setStatus(`This browser cannot run local Gemma: missing ${missing.join(", ")}.`);
  }
}

async function refreshModelState(): Promise<void> {
  modelState = await client.inspect();
  renderModelState();
}

downloadButton.addEventListener("click", () => {
  void (async () => {
    const remaining = Math.max(0, MODEL_BYTES - modelState.storedBytes);
    const accepted = window.confirm(
      `Download ${formatBytes(remaining)} now? The exact Gemma 4 web model will be `
      + "stored only in this browser origin and verified before use.",
    );
    if (!accepted) {
      return;
    }

    setModelBusy(true);
    try {
      const persistent = await navigator.storage.persist?.() ?? false;
      const estimate = await navigator.storage.estimate();
      const available = estimate.quota !== undefined && estimate.usage !== undefined
        ? estimate.quota - estimate.usage
        : undefined;
      if (available !== undefined && available < remaining + MODEL_HEADROOM_BYTES) {
        setStatus(
          `Not enough browser storage. ${formatBytes(remaining + MODEL_HEADROOM_BYTES)} `
          + "of free quota is required for the remaining model and safety headroom.",
        );
        return;
      }

      setModelBusy(true, true);
      downloadProgress.value = modelState.storedBytes;
      setStatus(persistent
        ? "Downloading into persistent browser storage…"
        : "Downloading into browser storage. This browser may evict it later.");
      modelState = await client.download((progress) => {
        downloadProgress.value = progress.bytes;
        const verb = progress.phase === "checking"
          ? "Checking saved bytes"
          : progress.phase === "verifying"
            ? "Verifying SHA-256"
            : "Downloading";
        downloadDetail.textContent = `${verb}: ${formatBytes(progress.bytes)} / ${formatBytes(progress.total)}`;
      });
      setStatus("Model verified. Preparing the self-hosted LiteRT-LM runtime for offline use…");
      await client.prepareRuntime();
      setStatus("Gemma 4 is verified and ready for local, offline proofreading.");
      setAutoCorrectStatus(
        "Auto-correct is off. Turn it on to check virtual-keyboard word boundaries.",
      );
    } catch (error) {
      setStatus(error instanceof Error && error.name === "AbortError"
        ? "Download paused. Saved browser bytes can be resumed."
        : `Model setup failed: ${error instanceof Error ? error.message : "Unknown error."}`);
      await refreshModelState();
    } finally {
      setModelBusy(false);
    }
  })();
});

cancelDownloadButton.addEventListener("click", () => {
  cancelDownloadButton.disabled = true;
  void client.cancelDownload().finally(() => {
    cancelDownloadButton.disabled = false;
  });
});

removeButton.addEventListener("click", () => {
  if (inferenceBusy() || modelBusy) {
    return;
  }
  if (!window.confirm("Remove the verified Gemma model and all partial model bytes from this browser?")) {
    return;
  }
  void (async () => {
    stopAutoCorrectWork();
    autoCorrectToggle.checked = false;
    setAutoCorrectStatus(
      "Auto-correct is off. Download the verified model again to enable it.",
    );
    setModelBusy(true);
    try {
      await client.remove();
      modelState = { ready: false, storedBytes: 0 };
      hideSuggestion();
      setStatus("The browser-owned model was removed.");
    } catch (error) {
      setStatus(`Could not remove the model: ${error instanceof Error ? error.message : "Unknown error."}`);
    } finally {
      setModelBusy(false);
    }
  })();
});

proofreadButton.addEventListener("click", () => {
  void (async () => {
    if (stopAutoCorrectWork() && autoCorrectToggle.checked) {
      setAutoCorrectStatus("Auto-correct paused for this manual proofreading check.");
    }
    hideSuggestion();
    let snapshot: EditorSnapshot;
    try {
      snapshot = captureEditorSnapshot(editorState());
    } catch (error) {
      setStatus(error instanceof Error ? error.message : "Select valid text to proofread.");
      return;
    }
    activeSnapshot = snapshot;
    setProofreadBusy(true);
    setStatus("Running Gemma locally in this browser. No editor text is being sent to a server…");
    try {
      const result = await client.proofread(snapshot.targetText);
      if (activeSnapshot !== snapshot || !snapshotMatches(editorState(), snapshot)) {
        setStatus("The editor changed while Gemma was running, so the result was discarded.");
        return;
      }
      if (result.kind === "no-change") {
        setStatus("Gemma found no safe proofreading change.");
        return;
      }
      const pending = createPendingSuggestion(snapshot, result.suggestion);
      showSuggestion(pending);
      setStatus("Review the exact suggestion, then choose Apply or Dismiss.");
    } catch (error) {
      setStatus(error instanceof Error && error.name === "AbortError"
        ? "Proofreading cancelled. The editor was not changed."
        : `Proofreading failed safely: ${error instanceof Error ? error.message : "Unknown error."}`);
    } finally {
      if (activeSnapshot === snapshot) {
        activeSnapshot = null;
      }
      setProofreadBusy(false);
    }
  })();
});

cancelProofreadButton.addEventListener("click", () => {
  cancelProofreadButton.disabled = true;
  void client.cancelProofread().finally(() => {
    cancelProofreadButton.disabled = false;
  });
});

applyButton.addEventListener("click", () => {
  if (pendingSuggestion === null) {
    return;
  }
  const result = applyPendingSuggestion(editorState(), pendingSuggestion);
  hideSuggestion();
  if (result.outcome !== "applied") {
    setStatus("The editor changed, so the stale suggestion was not applied.");
    return;
  }
  clearAutoCorrectUndo();
  writeEditorState(result.editor);
  setStatus("Suggestion applied locally.");
});

dismissButton.addEventListener("click", () => {
  if (pendingSuggestion === null) {
    return;
  }
  dismissPendingSuggestion(editorState(), pendingSuggestion);
  hideSuggestion();
  setStatus("Suggestion dismissed. The editor was not changed.");
});

autoCorrectToggle.addEventListener("change", () => {
  if (!autoCorrectToggle.checked) {
    stopAutoCorrectWork();
    setAutoCorrectStatus("Auto-correct is off. Existing editor text was not changed.");
  } else if (!capabilitiesReady || !modelState.ready) {
    autoCorrectToggle.checked = false;
    setAutoCorrectStatus("Auto-correct needs the verified local model before it can run.");
  } else {
    setAutoCorrectStatus(
      "Auto-correct is on. It waits for a pause after a virtual word boundary.",
    );
  }
  updateEditorUi();
});

undoAutoCorrectButton.addEventListener("click", () => {
  if (autoCorrectUndo === null) {
    return;
  }
  const result = applyAutoCorrectUndo(editorState(), autoCorrectUndo);
  autoCorrectUndo = null;
  if (result.outcome !== "undone") {
    updateEditorUi();
    setAutoCorrectStatus(
      "Undo expired because the editor or caret changed. No text was changed.",
    );
    return;
  }
  writeEditorState(result.editor);
  setAutoCorrectStatus("The last Gemma auto-correction was undone locally.");
});

function editorChangedOutsideVirtualKeyboard(): void {
  const hadWork = stopAutoCorrectWork();
  const hadUndo = autoCorrectUndo !== null;
  autoCorrectUndo = null;
  updateEditorUi();
  if (hadWork) {
    setAutoCorrectStatus(
      "Auto-correct stopped because the editor changed. No outdated result was applied.",
    );
  } else if (hadUndo) {
    setAutoCorrectStatus(autoCorrectIdleMessage());
  }
}

function selectionMayHaveChanged(): void {
  const current = editorState();
  const expected = scheduledAutoCorrect?.snapshot
    ?? (activeAutoCorrect?.generation === autoCorrectGeneration
      ? activeAutoCorrect.snapshot
      : undefined);
  const isJustAppliedAutoCorrect = autoCorrectUndo !== null
    && editorStatesMatch(current, autoCorrectUndo.after);
  if (
    expected !== undefined
    && !isJustAppliedAutoCorrect
    && !snapshotMatches(current, expected)
  ) {
    stopAutoCorrectWork();
    setAutoCorrectStatus(
      "Auto-correct stopped because the caret changed. No outdated result was applied.",
    );
  }
  if (autoCorrectUndo !== null && !editorStatesMatch(current, autoCorrectUndo.after)) {
    autoCorrectUndo = null;
  }
  updateEditorUi();
}

editor.addEventListener("input", editorChangedOutsideVirtualKeyboard);
editor.addEventListener("select", selectionMayHaveChanged);
editor.addEventListener("keyup", selectionMayHaveChanged);
window.addEventListener("pagehide", () => {
  stopAutoCorrectWork();
  client.terminate();
}, { once: true });

renderKeyboard();
updateEditorUi();
renderModelState();

void (async () => {
  try {
    await registerServiceWorker();
    await checkCapabilities();
    await refreshModelState();
    if (modelState.ready && capabilitiesReady) {
      setStatus("Verified Gemma 4 model found in this browser. Ready for local proofreading.");
      setAutoCorrectStatus(
        "Auto-correct is off. Turn it on to check virtual-keyboard word boundaries.",
      );
    } else if (modelState.ready) {
      setAutoCorrectStatus(
        "Auto-correct is off because this browser cannot run the verified local model.",
      );
    } else {
      setAutoCorrectStatus(
        "Auto-correct is off. Download the verified model before it can be enabled.",
      );
    }
  } catch (error) {
    setStatus(`Browser setup failed: ${error instanceof Error ? error.message : "Unknown error."}`);
  }
})();
