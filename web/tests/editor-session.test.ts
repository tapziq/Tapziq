import { describe, expect, it } from "vitest";

import {
  applyPendingSuggestion,
  captureEditorSnapshot,
  createPendingSuggestion,
  dismissPendingSuggestion,
  snapshotMatches,
} from "../src/editor-session";

describe("editor proofreading session", () => {
  it("captures the selected passage and creates a visible preview", () => {
    const snapshot = captureEditorSnapshot({
      text: "Before teh\nline after",
      selectionStart: 7,
      selectionEnd: 15,
    });
    const pending = createPendingSuggestion(snapshot, "the\nline");

    expect(snapshot.targetText).toBe("teh\nline");
    expect(pending.preview).toBe("the ↵ line");
  });

  it("uses the whole editor when the selection is collapsed", () => {
    const snapshot = captureEditorSnapshot({
      text: "This are wrong.",
      selectionStart: 5,
      selectionEnd: 5,
    });

    expect(snapshot.targetStart).toBe(0);
    expect(snapshot.targetEnd).toBe(15);
    expect(snapshot.targetText).toBe("This are wrong.");
  });

  it("applies to the exact snapshot and moves the caret after the replacement", () => {
    const editor = { text: "Fix teh word.", selectionStart: 4, selectionEnd: 7 };
    const snapshot = captureEditorSnapshot(editor);
    const pending = createPendingSuggestion(snapshot, "the");
    const result = applyPendingSuggestion(editor, pending);

    expect(result).toEqual({
      outcome: "applied",
      editor: { text: "Fix the word.", selectionStart: 7, selectionEnd: 7 },
      pending: null,
    });
  });

  it("fails closed when text or selection changed", () => {
    const original = { text: "Fix teh word.", selectionStart: 4, selectionEnd: 7 };
    const pending = createPendingSuggestion(captureEditorSnapshot(original), "the");
    const changed = { text: "Fix teh word!", selectionStart: 4, selectionEnd: 7 };
    const moved = { text: original.text, selectionStart: 0, selectionEnd: 0 };

    expect(snapshotMatches(changed, pending.snapshot)).toBe(false);
    expect(applyPendingSuggestion(changed, pending)).toEqual({
      outcome: "stale",
      editor: changed,
      pending: null,
    });
    expect(applyPendingSuggestion(moved, pending).outcome).toBe("stale");
  });

  it("dismisses without editing even after the document changed", () => {
    const original = { text: "teh", selectionStart: 0, selectionEnd: 3 };
    const pending = createPendingSuggestion(captureEditorSnapshot(original), "the");
    const current = { text: "teh!", selectionStart: 4, selectionEnd: 4 };

    expect(dismissPendingSuggestion(current, pending)).toEqual({
      outcome: "dismissed",
      editor: current,
      pending: null,
    });
  });

  it("rejects empty targets, oversized targets, and formatting changes", () => {
    expect(() => captureEditorSnapshot({ text: "   ", selectionStart: 0, selectionEnd: 0 }))
      .toThrow(RangeError);
    expect(() => captureEditorSnapshot({
      text: "x".repeat(501),
      selectionStart: 0,
      selectionEnd: 0,
    })).toThrow(RangeError);

    const snapshot = captureEditorSnapshot({
      text: "teh\nline",
      selectionStart: 0,
      selectionEnd: 8,
    });
    expect(() => createPendingSuggestion(snapshot, "the line")).toThrow(RangeError);
  });
});
