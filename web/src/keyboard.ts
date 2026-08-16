import type { EditorState } from "./editor-session";

export type VirtualKey =
  | { readonly kind: "text"; readonly text: string }
  | { readonly kind: "space" }
  | { readonly kind: "enter" }
  | { readonly kind: "backspace" };

function normalizedSelection(editor: EditorState): readonly [number, number] {
  if (
    !Number.isInteger(editor.selectionStart)
    || !Number.isInteger(editor.selectionEnd)
    || editor.selectionStart < 0
    || editor.selectionEnd < 0
    || editor.selectionStart > editor.text.length
    || editor.selectionEnd > editor.text.length
  ) {
    throw new RangeError("Editor selection is outside the document.");
  }
  return editor.selectionStart <= editor.selectionEnd
    ? [editor.selectionStart, editor.selectionEnd]
    : [editor.selectionEnd, editor.selectionStart];
}

/** Replaces the current selection without reading or mutating a DOM element. */
export function insertVirtualText(editor: EditorState, inserted: string): EditorState {
  const [start, end] = normalizedSelection(editor);
  const text = editor.text.slice(0, start) + inserted + editor.text.slice(end);
  const cursor = start + inserted.length;
  return Object.freeze({ text, selectionStart: cursor, selectionEnd: cursor });
}

/** Deletes a selection or one complete Unicode code point before the caret. */
export function deleteVirtualBackward(editor: EditorState): EditorState {
  const [start, end] = normalizedSelection(editor);
  if (start !== end) {
    return insertVirtualText(editor, "");
  }
  if (start === 0) {
    return Object.freeze({
      text: editor.text,
      selectionStart: 0,
      selectionEnd: 0,
    });
  }

  let deleteFrom = start - 1;
  const last = editor.text.charCodeAt(deleteFrom);
  if (last >= 0xdc00 && last <= 0xdfff && deleteFrom > 0) {
    const previous = editor.text.charCodeAt(deleteFrom - 1);
    if (previous >= 0xd800 && previous <= 0xdbff) {
      deleteFrom -= 1;
    }
  }

  const text = editor.text.slice(0, deleteFrom) + editor.text.slice(start);
  return Object.freeze({
    text,
    selectionStart: deleteFrom,
    selectionEnd: deleteFrom,
  });
}

export function applyVirtualKey(editor: EditorState, key: VirtualKey): EditorState {
  switch (key.kind) {
    case "text":
      return insertVirtualText(editor, key.text);
    case "space":
      return insertVirtualText(editor, " ");
    case "enter":
      return insertVirtualText(editor, "\n");
    case "backspace":
      return deleteVirtualBackward(editor);
  }
}
