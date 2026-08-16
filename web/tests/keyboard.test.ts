import { describe, expect, it } from "vitest";

import {
  applyVirtualKey,
  deleteVirtualBackward,
  insertVirtualText,
} from "../src/keyboard";

describe("virtual keyboard edits", () => {
  it("inserts at the caret and replaces a selection", () => {
    expect(insertVirtualText(
      { text: "helo", selectionStart: 2, selectionEnd: 2 },
      "l",
    )).toEqual({ text: "hello", selectionStart: 3, selectionEnd: 3 });

    expect(insertVirtualText(
      { text: "bad word", selectionStart: 0, selectionEnd: 3 },
      "good",
    )).toEqual({ text: "good word", selectionStart: 4, selectionEnd: 4 });
  });

  it("normalizes a reversed selection", () => {
    expect(insertVirtualText(
      { text: "abcde", selectionStart: 4, selectionEnd: 1 },
      "X",
    )).toEqual({ text: "aXe", selectionStart: 2, selectionEnd: 2 });
  });

  it("backspaces a selection or one complete Unicode code point", () => {
    expect(deleteVirtualBackward(
      { text: "abcde", selectionStart: 1, selectionEnd: 4 },
    )).toEqual({ text: "ae", selectionStart: 1, selectionEnd: 1 });

    const emoji = "A😀B";
    expect(deleteVirtualBackward(
      { text: emoji, selectionStart: 3, selectionEnd: 3 },
    )).toEqual({ text: "AB", selectionStart: 1, selectionEnd: 1 });
  });

  it("maps text, space, enter, and backspace keys without DOM state", () => {
    let editor = { text: "", selectionStart: 0, selectionEnd: 0 };
    editor = applyVirtualKey(editor, { kind: "text", text: "Hi" });
    editor = applyVirtualKey(editor, { kind: "space" });
    editor = applyVirtualKey(editor, { kind: "text", text: "there" });
    editor = applyVirtualKey(editor, { kind: "enter" });
    editor = applyVirtualKey(editor, { kind: "backspace" });

    expect(editor).toEqual({ text: "Hi there", selectionStart: 8, selectionEnd: 8 });
  });

  it("rejects selections outside the current text", () => {
    expect(() => insertVirtualText(
      { text: "abc", selectionStart: 0, selectionEnd: 4 },
      "x",
    )).toThrow(RangeError);
  });
});
