import {
  MAX_PROOFREAD_CHARACTERS,
  preservesStructuralWhitespace,
  visibleProofreadPreview,
} from "./proofread";

export interface EditorState {
  readonly text: string;
  readonly selectionStart: number;
  readonly selectionEnd: number;
}

export interface EditorSnapshot {
  readonly documentText: string;
  readonly selectionStart: number;
  readonly selectionEnd: number;
  readonly targetStart: number;
  readonly targetEnd: number;
  readonly targetText: string;
}

export interface PendingSuggestion {
  readonly snapshot: EditorSnapshot;
  readonly suggestion: string;
  readonly preview: string;
}

export type EditorActionOutcome = "applied" | "dismissed" | "stale";

export interface EditorActionResult {
  readonly outcome: EditorActionOutcome;
  readonly editor: EditorState;
  readonly pending: null;
}

function normalizedSelection(editor: EditorState): readonly [number, number] {
  const { text, selectionStart, selectionEnd } = editor;
  if (
    !Number.isInteger(selectionStart)
    || !Number.isInteger(selectionEnd)
    || selectionStart < 0
    || selectionEnd < 0
    || selectionStart > text.length
    || selectionEnd > text.length
  ) {
    throw new RangeError("Editor selection is outside the document.");
  }
  return selectionStart <= selectionEnd
    ? [selectionStart, selectionEnd]
    : [selectionEnd, selectionStart];
}

/** Captures the selected passage, or the whole editor when selection is collapsed. */
export function captureEditorSnapshot(editor: EditorState): EditorSnapshot {
  const [selectionStart, selectionEnd] = normalizedSelection(editor);
  const hasSelection = selectionStart !== selectionEnd;
  const targetStart = hasSelection ? selectionStart : 0;
  const targetEnd = hasSelection ? selectionEnd : editor.text.length;
  const targetText = editor.text.slice(targetStart, targetEnd);

  if (targetText.length === 0 || targetText.trim().length === 0) {
    throw new RangeError("There is no text to proofread.");
  }
  if (targetText.length > MAX_PROOFREAD_CHARACTERS) {
    throw new RangeError(
      `Select no more than ${MAX_PROOFREAD_CHARACTERS} UTF-16 code units.`,
    );
  }

  return Object.freeze({
    documentText: editor.text,
    selectionStart,
    selectionEnd,
    targetStart,
    targetEnd,
    targetText,
  });
}

export function createPendingSuggestion(
  snapshot: EditorSnapshot,
  suggestion: string,
): PendingSuggestion {
  if (suggestion.length === 0 || suggestion.trim().length === 0) {
    throw new RangeError("A proofreading suggestion must not be empty.");
  }
  if (!preservesStructuralWhitespace(snapshot.targetText, suggestion)) {
    throw new RangeError("The suggestion changes structural whitespace.");
  }

  return Object.freeze({
    snapshot,
    suggestion,
    preview: visibleProofreadPreview(suggestion),
  });
}

export function snapshotMatches(editor: EditorState, snapshot: EditorSnapshot): boolean {
  let selection: readonly [number, number];
  try {
    selection = normalizedSelection(editor);
  } catch {
    return false;
  }
  return editor.text === snapshot.documentText
    && selection[0] === snapshot.selectionStart
    && selection[1] === snapshot.selectionEnd;
}

/** Applies only to the exact text and selection that produced the suggestion. */
export function applyPendingSuggestion(
  editor: EditorState,
  pending: PendingSuggestion,
): EditorActionResult {
  if (
    !snapshotMatches(editor, pending.snapshot)
    || !preservesStructuralWhitespace(
      pending.snapshot.targetText,
      pending.suggestion,
    )
  ) {
    return Object.freeze({ outcome: "stale", editor, pending: null });
  }

  const { targetStart, targetEnd } = pending.snapshot;
  const text = editor.text.slice(0, targetStart)
    + pending.suggestion
    + editor.text.slice(targetEnd);
  const cursor = targetStart + pending.suggestion.length;
  const updated = Object.freeze({
    text,
    selectionStart: cursor,
    selectionEnd: cursor,
  });
  return Object.freeze({ outcome: "applied", editor: updated, pending: null });
}

/** Dismiss never edits the document, even when the original snapshot is stale. */
export function dismissPendingSuggestion(
  editor: EditorState,
  _pending: PendingSuggestion,
): EditorActionResult {
  return Object.freeze({ outcome: "dismissed", editor, pending: null });
}
