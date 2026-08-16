package com.tapziq.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.os.Bundle;
import android.os.Handler;
import android.text.Spanned;
import android.view.KeyEvent;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.TextAttribute;

import org.junit.Test;

import java.lang.reflect.Array;

public final class AutocorrectApplierTest {
    @Test
    public void legacyApplyPreservesBoundaryReportsCorrectionAndUndoRestoresOriginal() {
        RecordingConnection connection = new RecordingConnection("teh ", 4);
        AutocorrectEdit edit = edit("teh ", " ", "the");

        assertEquals(
                AutocorrectApplier.Result.APPLIED,
                AutocorrectApplier.apply(connection, edit, false)
        );
        assertEquals("the ", connection.text);
        assertEquals(4, connection.selectionStart);
        assertEquals(4, connection.selectionEnd);
        assertNotNull(connection.lastCorrection);
        assertEquals(1, connection.correctionCount);

        assertEquals(
                AutocorrectApplier.Result.APPLIED,
                AutocorrectApplier.undo(connection, edit, false)
        );
        assertEquals("teh ", connection.text);
        assertEquals(4, connection.selectionStart);
        assertEquals(2, connection.correctionCount);
    }

    @Test
    public void staleDocumentOrSelectionIsNeverChanged() {
        AutocorrectEdit edit = edit("teh ", " ", "the");
        RecordingConnection changed = new RecordingConnection("teh next", 8);

        assertEquals(
                AutocorrectApplier.Result.STALE,
                AutocorrectApplier.apply(changed, edit, false)
        );
        assertEquals("teh next", changed.text);

        RecordingConnection moved = new RecordingConnection("teh ", 3);
        assertEquals(
                AutocorrectApplier.Result.STALE,
                AutocorrectApplier.apply(moved, edit, false)
        );
        assertEquals("teh ", moved.text);
    }

    @Test
    public void modernReplaceTextPathPreservesThePostBoundaryCaret() {
        RecordingConnection connection = new RecordingConnection("this are bad. ", 14);
        AutocorrectEdit edit = edit("this are bad. ", " ", "this is bad.");

        assertEquals(
                AutocorrectApplier.Result.APPLIED,
                AutocorrectApplier.apply(connection, edit, true)
        );
        assertEquals("this is bad. ", connection.text);
        assertEquals(connection.text.length(), connection.selectionStart);
    }

    @Test
    public void sentButUnappliedReplacementIsNeverReportedOrMadeUndoable() {
        RecordingConnection connection = new RecordingConnection("teh ", 4);
        connection.ignoreReplacement = true;
        AutocorrectEdit edit = edit("teh ", " ", "the");

        assertEquals(
                AutocorrectApplier.Result.FAILED,
                AutocorrectApplier.apply(connection, edit, true)
        );
        assertEquals("teh ", connection.text);
        assertEquals(0, connection.correctionCount);
    }

    @Test
    public void legacyInsertionsAndDeletionUndosAcceptNullForCollapsedSelections() {
        RecordingConnection connection = new RecordingConnection("cant ", 5);
        AutocorrectEdit edit = edit("cant ", " ", "can't");

        assertEquals(
                AutocorrectApplier.Result.APPLIED,
                AutocorrectApplier.apply(connection, edit, false)
        );
        assertEquals("can't ", connection.text);
        assertEquals(6, connection.selectionStart);

        assertEquals(
                AutocorrectApplier.Result.APPLIED,
                AutocorrectApplier.undo(connection, edit, false)
        );
        assertEquals("cant ", connection.text);
        assertEquals(5, connection.selectionStart);

        RecordingConnection deletion = new RecordingConnection("cannt ", 6);
        AutocorrectEdit deletionEdit = edit("cannt ", " ", "cant");
        assertEquals(
                AutocorrectApplier.Result.APPLIED,
                AutocorrectApplier.apply(deletion, deletionEdit, false)
        );
        assertEquals("cant ", deletion.text);
        assertEquals(
                AutocorrectApplier.Result.APPLIED,
                AutocorrectApplier.undo(deletion, deletionEdit, false)
        );
        assertEquals("cannt ", deletion.text);
    }

    @Test
    public void legacyInsertionRejectsASelectionCommandThatWasNotApplied() {
        RecordingConnection connection = new RecordingConnection("cant ", 5);
        connection.ignoreSelection = true;
        AutocorrectEdit edit = edit("cant ", " ", "can't");

        assertEquals(
                AutocorrectApplier.Result.STALE,
                AutocorrectApplier.apply(connection, edit, false)
        );
        assertEquals("cant ", connection.text);
        assertEquals(5, connection.selectionStart);
        assertEquals(0, connection.correctionCount);
    }

    @Test
    public void legacyApplyRejectsFormattingAddedBetweenItsTwoSnapshots() {
        RecordingConnection connection = new RecordingConnection("teh ", 4);
        connection.styleSnapshotsAfterSelection = true;
        AutocorrectEdit edit = edit("teh ", " ", "the");

        assertEquals(
                AutocorrectApplier.Result.STALE,
                AutocorrectApplier.apply(connection, edit, false)
        );
        assertEquals("teh ", connection.text);
        assertEquals(0, connection.correctionCount);
    }

    private static AutocorrectEdit edit(String document, String boundary, String suggestion) {
        AutocorrectTarget.Capture capture = AutocorrectTarget.capture(
                document,
                0,
                document.length(),
                document.length(),
                true,
                boundary
        );
        return AutocorrectEdit.validate(capture.target, suggestion).edit;
    }

    private static final class RecordingConnection implements InputConnection {
        private String text;
        private int selectionStart;
        private int selectionEnd;
        private CorrectionInfo lastCorrection;
        private int correctionCount;
        private boolean ignoreReplacement;
        private boolean ignoreSelection;
        private boolean styleSnapshotsAfterSelection;
        private boolean selectionWasSet;

        RecordingConnection(String text, int caret) {
            this.text = text;
            this.selectionStart = caret;
            this.selectionEnd = caret;
        }

        @Override
        public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
            ExtractedText extracted = new ExtractedText();
            extracted.text = styleSnapshotsAfterSelection && selectionWasSet
                    ? new FakeSpanned(text, 0, Math.max(1, text.length() - 1))
                    : text;
            extracted.startOffset = 0;
            extracted.selectionStart = selectionStart;
            extracted.selectionEnd = selectionEnd;
            extracted.partialStartOffset = -1;
            extracted.partialEndOffset = -1;
            return extracted;
        }

        @Override
        public boolean setSelection(int start, int end) {
            if (start < 0 || end < 0 || start > text.length() || end > text.length()) {
                return false;
            }
            if (ignoreSelection) {
                return true;
            }
            selectionStart = start;
            selectionEnd = end;
            selectionWasSet = true;
            return true;
        }

        @Override
        public CharSequence getSelectedText(int flags) {
            int start = Math.min(selectionStart, selectionEnd);
            int end = Math.max(selectionStart, selectionEnd);
            return start == end ? null : text.substring(start, end);
        }

        @Override
        public boolean commitText(CharSequence replacement, int newCursorPosition) {
            if (ignoreReplacement) {
                return true;
            }
            int start = Math.min(selectionStart, selectionEnd);
            int end = Math.max(selectionStart, selectionEnd);
            text = text.substring(0, start) + replacement + text.substring(end);
            int replacementEnd = start + replacement.length();
            int caret = newCursorPosition > 0
                    ? replacementEnd + newCursorPosition - 1
                    : start + newCursorPosition;
            selectionStart = caret;
            selectionEnd = caret;
            return true;
        }

        @Override
        public boolean commitCorrection(CorrectionInfo correctionInfo) {
            lastCorrection = correctionInfo;
            correctionCount++;
            return true;
        }

        @Override
        public boolean replaceText(
                int start,
                int end,
                CharSequence replacement,
                int newCursorPosition,
                TextAttribute textAttribute
        ) {
            if (!setSelection(start, end)) {
                return false;
            }
            return commitText(replacement, newCursorPosition);
        }

        @Override public CharSequence getTextBeforeCursor(int length, int flags) { return ""; }
        @Override public CharSequence getTextAfterCursor(int length, int flags) { return ""; }
        @Override public int getCursorCapsMode(int reqModes) { return 0; }
        @Override public boolean deleteSurroundingText(int beforeLength, int afterLength) { return false; }
        @Override public boolean setComposingText(CharSequence text, int newCursorPosition) { return false; }
        @Override public boolean setComposingRegion(int start, int end) { return false; }
        @Override public boolean finishComposingText() { return false; }
        @Override public boolean commitCompletion(CompletionInfo text) { return false; }
        @Override public boolean sendKeyEvent(KeyEvent event) { return false; }
        @Override public boolean clearMetaKeyStates(int states) { return false; }
        @Override public boolean performEditorAction(int editorAction) { return false; }
        @Override public boolean performContextMenuAction(int id) { return false; }
        @Override public boolean beginBatchEdit() { return true; }
        @Override public boolean endBatchEdit() { return true; }
        @Override public boolean reportFullscreenMode(boolean enabled) { return false; }
        @Override public boolean performPrivateCommand(String action, Bundle data) { return false; }
        @Override public boolean requestCursorUpdates(int cursorUpdateMode) { return false; }
        @Override public Handler getHandler() { return null; }
        @Override public void closeConnection() { }
        @Override public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) { return false; }
        @Override public boolean commitContent(android.view.inputmethod.InputContentInfo inputContentInfo, int flags, Bundle opts) { return false; }
    }

    private static final class FakeSpanned implements Spanned {
        private final String text;
        private final Object span = new Object();
        private final int spanStart;
        private final int spanEnd;

        FakeSpanned(String text, int spanStart, int spanEnd) {
            this.text = text;
            this.spanStart = spanStart;
            this.spanEnd = spanEnd;
        }

        @Override public int length() { return text.length(); }
        @Override public char charAt(int index) { return text.charAt(index); }
        @Override public CharSequence subSequence(int start, int end) {
            return text.subSequence(start, end);
        }
        @Override public String toString() { return text; }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T[] getSpans(int start, int end, Class<T> type) {
            if (!type.isInstance(span) || spanStart >= end || spanEnd <= start) {
                return (T[]) Array.newInstance(type, 0);
            }
            T[] values = (T[]) Array.newInstance(type, 1);
            values[0] = type.cast(span);
            return values;
        }

        @Override public int getSpanStart(Object query) { return query == span ? spanStart : -1; }
        @Override public int getSpanEnd(Object query) { return query == span ? spanEnd : -1; }
        @Override public int getSpanFlags(Object query) { return 0; }
        @Override
        public int nextSpanTransition(int start, int limit, Class type) {
            if (start < spanStart) {
                return Math.min(spanStart, limit);
            }
            if (start < spanEnd) {
                return Math.min(spanEnd, limit);
            }
            return limit;
        }
    }
}
