import {
  snapshotMatches,
  type EditorSnapshot,
  type EditorState,
  type PendingSuggestion,
} from "./editor-session";
import type { VirtualKey } from "./keyboard";
import {
  MAX_PROOFREAD_CHARACTERS,
  preservesStructuralWhitespace,
} from "./proofread";

export const AUTO_CORRECT_DEBOUNCE_MS = 250;

const TRAILING_BOUNDARIES = /[\s\p{P}]+$/u;
const WORD_CHARACTER = /^[\p{L}\p{M}\p{N}]$/u;
const LEADING_SEAM_CHARACTERS = /^[\s\p{P}]+/u;
const TRAILING_PUNCTUATION = /\p{P}+$/u;
const TRAILING_WHITESPACE = /\s+$/u;
const STRUCTURAL_CHARACTER = /[\p{Cc}\p{Cf}\p{Zl}\p{Zp}\p{Zs}]/u;
const COMPLETION_PUNCTUATION = new Set([",", ".", "!", "?", ";", ":", "…", "..."]);

export interface AutoCorrectUndo {
  readonly before: EditorState;
  readonly after: EditorState;
}

export type AutoCorrectUndoResult =
  | { readonly outcome: "undone"; readonly editor: EditorState }
  | { readonly outcome: "stale"; readonly editor: EditorState };

function boundaryText(key: VirtualKey): string | null {
  switch (key.kind) {
    case "space":
      return " ";
    case "enter":
      return "\n";
    case "text":
      return COMPLETION_PUNCTUATION.has(key.text) ? key.text : null;
    case "backspace":
      return null;
  }
}

/** Autocorrect is deliberately limited to explicit virtual-key word boundaries. */
export function isAutoCorrectBoundary(key: VirtualKey): boolean {
  return boundaryText(key) !== null;
}

function previousCodePointStart(text: string, index: number): number {
  let start = index - 1;
  if (
    start > 0
    && text.charCodeAt(start) >= 0xdc00
    && text.charCodeAt(start) <= 0xdfff
    && text.charCodeAt(start - 1) >= 0xd800
    && text.charCodeAt(start - 1) <= 0xdbff
  ) {
    start -= 1;
  }
  return start;
}

function isWordCharacterAt(text: string, index: number): boolean {
  const codePoint = text.codePointAt(index);
  return codePoint !== undefined
    && WORD_CHARACTER.test(String.fromCodePoint(codePoint));
}

function nextCodePointStart(text: string, index: number): number {
  const codePoint = text.codePointAt(index);
  return index + (codePoint !== undefined && codePoint > 0xffff ? 2 : 1);
}

function safeRecentStart(text: string, end: number): number {
  let start = Math.max(0, end - MAX_PROOFREAD_CHARACTERS);
  if (
    start > 0
    && text.charCodeAt(start) >= 0xdc00
    && text.charCodeAt(start) <= 0xdfff
    && text.charCodeAt(start - 1) >= 0xd800
    && text.charCodeAt(start - 1) <= 0xdbff
  ) {
    start += 1;
  }

  if (start === 0) {
    return start;
  }

  const previous = previousCodePointStart(text, start);
  if (isWordCharacterAt(text, previous) && isWordCharacterAt(text, start)) {
    // A 500-code-unit suffix must not begin halfway through a word. Dropping
    // that partial word is safer than asking the model to reconstruct it.
    while (start < end && isWordCharacterAt(text, start)) {
      start = nextCodePointStart(text, start);
    }
  }

  // Keep the separator at a truncated left seam outside model control. This
  // also prevents a corrected suffix from deleting the only space between it
  // and the untouched prefix.
  while (start < end && !isWordCharacterAt(text, start)) {
    start = nextCodePointStart(text, start);
  }
  return start;
}

function completedWordBefore(prefix: string): boolean {
  const withoutBoundaries = prefix.replace(TRAILING_BOUNDARIES, "");
  if (withoutBoundaries.length === 0) {
    return false;
  }
  return isWordCharacterAt(
    withoutBoundaries,
    previousCodePointStart(withoutBoundaries, withoutBoundaries.length),
  );
}

/**
 * Captures only recent text before the just-committed boundary. The full editor
 * and exact post-boundary selection are retained so callers can reject stale
 * inference and preserve the delimiter outside model-controlled text.
 */
export function captureAutoCorrectSnapshot(
  editor: EditorState,
  key: VirtualKey,
): EditorSnapshot | null {
  const boundary = boundaryText(key);
  if (
    boundary === null
    || !Number.isInteger(editor.selectionStart)
    || !Number.isInteger(editor.selectionEnd)
    || editor.selectionStart !== editor.selectionEnd
    || editor.selectionStart < boundary.length
    || editor.selectionStart > editor.text.length
    || editor.text.slice(editor.selectionStart - boundary.length, editor.selectionStart)
      !== boundary
  ) {
    return null;
  }

  const targetEnd = editor.selectionStart - boundary.length;
  const beforeBoundary = editor.text.slice(0, targetEnd);
  if (!completedWordBefore(beforeBoundary)) {
    return null;
  }

  const targetStart = safeRecentStart(editor.text, targetEnd);
  const targetText = editor.text.slice(targetStart, targetEnd);
  if (
    targetText.length === 0
    || targetText.trim().length === 0
    || targetText.length > MAX_PROOFREAD_CHARACTERS
  ) {
    return null;
  }

  return Object.freeze({
    documentText: editor.text,
    selectionStart: editor.selectionStart,
    selectionEnd: editor.selectionEnd,
    targetStart,
    targetEnd,
    targetText,
  });
}

export type AutoCorrectApplyResult =
  | { readonly outcome: "applied"; readonly editor: EditorState }
  | { readonly outcome: "stale"; readonly editor: EditorState }
  | { readonly outcome: "unsafe"; readonly editor: EditorState };

function matchingText(value: string, pattern: RegExp): string {
  return pattern.exec(value)?.[0] ?? "";
}

function preservesReplacementSeams(snapshot: EditorSnapshot, suggestion: string): boolean {
  if (
    matchingText(snapshot.targetText, LEADING_SEAM_CHARACTERS)
      !== matchingText(suggestion, LEADING_SEAM_CHARACTERS)
  ) {
    return false;
  }

  const targetTrailingWhitespace = matchingText(snapshot.targetText, TRAILING_WHITESPACE);
  const suggestionTrailingWhitespace = matchingText(suggestion, TRAILING_WHITESPACE);
  if (
    suggestionTrailingWhitespace.length > 0
    && suggestionTrailingWhitespace !== targetTrailingWhitespace
  ) {
    return false;
  }

  const committedBoundary = snapshot.documentText.slice(
    snapshot.targetEnd,
    snapshot.selectionStart,
  );
  if (!COMPLETION_PUNCTUATION.has(committedBoundary)) {
    return true;
  }

  const targetTrailingPunctuation = matchingText(snapshot.targetText, TRAILING_PUNCTUATION);
  const suggestionTrailingPunctuation = matchingText(suggestion, TRAILING_PUNCTUATION);
  return suggestionTrailingPunctuation.length === 0
    || suggestionTrailingPunctuation === targetTrailingPunctuation;
}

function structuralAnchors(value: string): string {
  const anchors: string[] = [];
  let offset = 0;
  for (const character of value) {
    if (character !== " " && STRUCTURAL_CHARACTER.test(character)) {
      anchors.push(`${offset}:${character.codePointAt(0) ?? -1}`);
    }
    offset += character.length;
  }
  return anchors.join(";");
}

function editDistanceAllowance(length: number): number {
  return Math.min(24, Math.max(3, Math.ceil(length / 12)));
}

function isWithinEditDistance(original: string, suggestion: string, limit: number): boolean {
  if (Math.abs(original.length - suggestion.length) > limit) {
    return false;
  }
  let previous = Array.from({ length: suggestion.length + 1 }, (_, index) => index);
  let current = new Array<number>(suggestion.length + 1);
  for (let row = 1; row <= original.length; row += 1) {
    current[0] = row;
    for (let column = 1; column <= suggestion.length; column += 1) {
      const substitution = original[row - 1] === suggestion[column - 1] ? 0 : 1;
      current[column] = Math.min(
        (current[column - 1] ?? 0) + 1,
        (previous[column] ?? 0) + 1,
        (previous[column - 1] ?? 0) + substitution,
      );
    }
    [previous, current] = [current, previous];
  }
  return (previous[suggestion.length] ?? limit + 1) <= limit;
}

function isMinimalCorrection(original: string, suggestion: string): boolean {
  return structuralAnchors(original) === structuralAnchors(suggestion)
    && isWithinEditDistance(original, suggestion, editDistanceAllowance(original.length));
}

/**
 * Replaces the model target while retaining the committed boundary and
 * restoring the exact post-boundary caret with the suggestion length delta.
 */
export function applyAutoCorrectSuggestion(
  editor: EditorState,
  pending: PendingSuggestion,
  maximumDocumentLength = Number.POSITIVE_INFINITY,
): AutoCorrectApplyResult {
  const { snapshot, suggestion } = pending;
  if (!snapshotMatches(editor, snapshot)) {
    return Object.freeze({ outcome: "stale", editor });
  }
  if (
    !preservesStructuralWhitespace(snapshot.targetText, suggestion)
    || !preservesReplacementSeams(snapshot, suggestion)
    || !isMinimalCorrection(snapshot.targetText, suggestion)
  ) {
    return Object.freeze({ outcome: "unsafe", editor });
  }

  const text = editor.text.slice(0, snapshot.targetStart)
    + suggestion
    + editor.text.slice(snapshot.targetEnd);
  if (text.length > maximumDocumentLength) {
    return Object.freeze({ outcome: "unsafe", editor });
  }
  const lengthDelta = suggestion.length - snapshot.targetText.length;
  const updated = Object.freeze({
    text,
    selectionStart: snapshot.selectionStart + lengthDelta,
    selectionEnd: snapshot.selectionEnd + lengthDelta,
  });
  return Object.freeze({ outcome: "applied", editor: updated });
}

function frozenState(editor: EditorState): EditorState {
  return Object.freeze({
    text: editor.text,
    selectionStart: editor.selectionStart,
    selectionEnd: editor.selectionEnd,
  });
}

export function editorStatesMatch(left: EditorState, right: EditorState): boolean {
  return left.text === right.text
    && left.selectionStart === right.selectionStart
    && left.selectionEnd === right.selectionEnd;
}

export function createAutoCorrectUndo(
  before: EditorState,
  after: EditorState,
): AutoCorrectUndo {
  return Object.freeze({ before: frozenState(before), after: frozenState(after) });
}

/** Undo is one-step and fails closed after any subsequent edit or caret move. */
export function applyAutoCorrectUndo(
  editor: EditorState,
  undo: AutoCorrectUndo,
): AutoCorrectUndoResult {
  if (!editorStatesMatch(editor, undo.after)) {
    return Object.freeze({ outcome: "stale", editor });
  }
  return Object.freeze({ outcome: "undone", editor: undo.before });
}
