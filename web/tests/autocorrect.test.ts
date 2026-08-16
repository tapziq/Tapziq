import { describe, expect, it } from "vitest";

import {
  applyAutoCorrectSuggestion,
  applyAutoCorrectUndo,
  captureAutoCorrectSnapshot,
  createAutoCorrectUndo,
  isAutoCorrectBoundary,
} from "../src/autocorrect";
import { createPendingSuggestion } from "../src/editor-session";
import type { VirtualKey } from "../src/keyboard";

describe("browser Gemma autocorrect", () => {
  it("recognizes only virtual space, punctuation, and Enter boundaries", () => {
    const boundaries: VirtualKey[] = [
      { kind: "space" },
      { kind: "text", text: "," },
      { kind: "text", text: "." },
      { kind: "enter" },
    ];
    const nonBoundaries: VirtualKey[] = [
      { kind: "text", text: "a" },
      { kind: "text", text: "hello" },
      { kind: "text", text: "'" },
      { kind: "text", text: "-" },
      { kind: "text", text: "\"" },
      { kind: "backspace" },
    ];

    expect(boundaries.every(isAutoCorrectBoundary)).toBe(true);
    expect(nonBoundaries.some(isAutoCorrectBoundary)).toBe(false);
  });

  it("captures recent text and the exact post-boundary editor state", () => {
    const text = `${"old ".repeat(130)}teh word. `;
    const snapshot = captureAutoCorrectSnapshot(
      { text, selectionStart: text.length, selectionEnd: text.length },
      { kind: "space" },
    );

    expect(snapshot).not.toBeNull();
    expect(snapshot?.targetText.length).toBeLessThanOrEqual(500);
    expect(snapshot?.targetText).toMatch(/teh word\.$/u);
    expect(snapshot?.targetText).toMatch(/^\p{L}/u);
    expect(text[snapshot?.targetStart === undefined ? 0 : snapshot.targetStart - 1])
      .toMatch(/[\s\p{P}]/u);
    expect(snapshot).toMatchObject({
      documentText: text,
      selectionStart: text.length,
      selectionEnd: text.length,
      targetEnd: text.length - 1,
    });
  });

  it("does not capture non-boundaries, blank boundaries, selections, or long partial words", () => {
    expect(captureAutoCorrectSnapshot(
      { text: "teh", selectionStart: 3, selectionEnd: 3 },
      { kind: "text", text: "h" },
    )).toBeNull();
    expect(captureAutoCorrectSnapshot(
      { text: " ", selectionStart: 1, selectionEnd: 1 },
      { kind: "space" },
    )).toBeNull();
    expect(captureAutoCorrectSnapshot(
      { text: "teh ", selectionStart: 0, selectionEnd: 4 },
      { kind: "space" },
    )).toBeNull();

    const oversizedWord = `${"x".repeat(501)} `;
    expect(captureAutoCorrectSnapshot(
      {
        text: oversizedWord,
        selectionStart: oversizedWord.length,
        selectionEnd: oversizedWord.length,
      },
      { kind: "space" },
    )).toBeNull();
  });

  it("captures punctuation and Enter after a completed word", () => {
    const punctuation = "teh,";
    expect(captureAutoCorrectSnapshot(
      {
        text: punctuation,
        selectionStart: punctuation.length,
        selectionEnd: punctuation.length,
      },
      { kind: "text", text: "," },
    )?.targetText).toBe("teh");

    const entered = "teh\n";
    expect(captureAutoCorrectSnapshot(
      { text: entered, selectionStart: entered.length, selectionEnd: entered.length },
      { kind: "enter" },
    )?.targetText).toBe("teh");
  });

  it("applies to an exact snapshot while preserving the boundary and caret", () => {
    const editor = { text: "This are wrong. ", selectionStart: 16, selectionEnd: 16 };
    const snapshot = captureAutoCorrectSnapshot(editor, { kind: "space" });
    expect(snapshot).not.toBeNull();
    if (snapshot === null) {
      throw new Error("Expected an autocorrect snapshot.");
    }
    const pending = createPendingSuggestion(snapshot, "This is wrong.");

    expect(applyAutoCorrectSuggestion(editor, pending)).toEqual({
      outcome: "applied",
      editor: { text: "This is wrong. ", selectionStart: 15, selectionEnd: 15 },
    });

    const changedSelection = { ...editor, selectionStart: 0, selectionEnd: 0 };
    expect(applyAutoCorrectSuggestion(changedSelection, pending)).toEqual({
      outcome: "stale",
      editor: changedSelection,
    });

    const entered = { text: "This are\n", selectionStart: 9, selectionEnd: 9 };
    const enteredSnapshot = captureAutoCorrectSnapshot(entered, { kind: "enter" });
    expect(enteredSnapshot).not.toBeNull();
    if (enteredSnapshot === null) {
      throw new Error("Expected an Enter-boundary autocorrect snapshot.");
    }
    expect(applyAutoCorrectSuggestion(
      entered,
      createPendingSuggestion(enteredSnapshot, "This is"),
    )).toEqual({
      outcome: "applied",
      editor: { text: "This is\n", selectionStart: 8, selectionEnd: 8 },
    });
  });

  it("rejects suggestions that introduce punctuation or whitespace at a replacement seam", () => {
    const punctuationEditor = { text: "hello.", selectionStart: 6, selectionEnd: 6 };
    const punctuationSnapshot = captureAutoCorrectSnapshot(
      punctuationEditor,
      { kind: "text", text: "." },
    );
    expect(punctuationSnapshot).not.toBeNull();
    if (punctuationSnapshot === null) {
      throw new Error("Expected a punctuation-boundary autocorrect snapshot.");
    }
    expect(applyAutoCorrectSuggestion(
      punctuationEditor,
      createPendingSuggestion(punctuationSnapshot, "Hello."),
    )).toEqual({ outcome: "unsafe", editor: punctuationEditor });

    const longEditorText = `${"old ".repeat(130)}teh `;
    const longEditor = {
      text: longEditorText,
      selectionStart: longEditorText.length,
      selectionEnd: longEditorText.length,
    };
    const longSnapshot = captureAutoCorrectSnapshot(longEditor, { kind: "space" });
    expect(longSnapshot).not.toBeNull();
    if (longSnapshot === null) {
      throw new Error("Expected a truncated autocorrect snapshot.");
    }
    expect(longSnapshot.targetStart).toBeGreaterThan(0);
    expect(longSnapshot.targetText).toMatch(/^\p{L}/u);
    expect(longEditorText[longSnapshot.targetStart - 1]).toBe(" ");
    expect(applyAutoCorrectSuggestion(
      longEditor,
      createPendingSuggestion(longSnapshot, ` ${longSnapshot.targetText}`),
    )).toEqual({ outcome: "unsafe", editor: longEditor });
  });

  it("rejects a safe suggestion when the resulting editor would exceed its limit", () => {
    const editor = { text: "cant ", selectionStart: 5, selectionEnd: 5 };
    const snapshot = captureAutoCorrectSnapshot(editor, { kind: "space" });
    expect(snapshot).not.toBeNull();
    if (snapshot === null) {
      throw new Error("Expected a space-boundary autocorrect snapshot.");
    }

    expect(applyAutoCorrectSuggestion(
      editor,
      createPendingSuggestion(snapshot, "can't"),
      5,
    )).toEqual({ outcome: "unsafe", editor });
  });

  it("rejects wholesale rewrites and moved formatting anchors", () => {
    const original = "alpha beta gamma delta epsilon zeta eta theta ";
    const editor = {
      text: original,
      selectionStart: original.length,
      selectionEnd: original.length,
    };
    const snapshot = captureAutoCorrectSnapshot(editor, { kind: "space" });
    expect(snapshot).not.toBeNull();
    if (snapshot === null) {
      throw new Error("Expected a space-boundary autocorrect snapshot.");
    }
    expect(applyAutoCorrectSuggestion(
      editor,
      createPendingSuggestion(snapshot, "omega iota kappa lambda omicron mu nu xi rho"),
    )).toEqual({ outcome: "unsafe", editor });

    const multiline = { text: "ab\ncd ", selectionStart: 6, selectionEnd: 6 };
    const multilineSnapshot = captureAutoCorrectSnapshot(multiline, { kind: "space" });
    expect(multilineSnapshot).not.toBeNull();
    if (multilineSnapshot === null) {
      throw new Error("Expected a multiline autocorrect snapshot.");
    }
    expect(applyAutoCorrectSuggestion(
      multiline,
      createPendingSuggestion(multilineSnapshot, "a\nbcd"),
    )).toEqual({ outcome: "unsafe", editor: multiline });
  });

  it("undoes exactly one unchanged auto-application and rejects stale state", () => {
    const before = { text: "teh ", selectionStart: 4, selectionEnd: 4 };
    const after = { text: "the ", selectionStart: 4, selectionEnd: 4 };
    const undo = createAutoCorrectUndo(before, after);

    expect(applyAutoCorrectUndo(after, undo)).toEqual({
      outcome: "undone",
      editor: before,
    });

    const changed = { text: "the next", selectionStart: 8, selectionEnd: 8 };
    expect(applyAutoCorrectUndo(changed, undo)).toEqual({
      outcome: "stale",
      editor: changed,
    });
  });
});
