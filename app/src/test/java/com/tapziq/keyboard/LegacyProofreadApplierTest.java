package com.tapziq.keyboard;

import static org.junit.Assert.assertEquals;

import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import org.junit.Test;

public final class LegacyProofreadApplierTest {
    @Test
    public void staleSelectionReadRestoresOriginalCursor() {
        RecordingConnection connection = new RecordingConnection("different", true);
        ProofreadTarget target = target();

        assertEquals(
                LegacyProofreadApplier.Result.STALE,
                LegacyProofreadApplier.apply(connection, target, "fixed")
        );
        assertEquals(2, connection.lastSelectionStart);
        assertEquals(2, connection.lastSelectionEnd);
    }

    @Test
    public void failedCommitRestoresOriginalCursor() {
        RecordingConnection connection = new RecordingConnection("bad", false);
        ProofreadTarget target = target();

        assertEquals(
                LegacyProofreadApplier.Result.FAILED,
                LegacyProofreadApplier.apply(connection, target, "fixed")
        );
        assertEquals(2, connection.lastSelectionStart);
        assertEquals(2, connection.lastSelectionEnd);
    }

    private static ProofreadTarget target() {
        return ProofreadTarget.fromExtracted("bad", 0, 2, 2, true).target;
    }

    private static final class RecordingConnection implements InputConnection {
        private final String selectedText;
        private final boolean commitSucceeds;
        private int lastSelectionStart = -1;
        private int lastSelectionEnd = -1;

        RecordingConnection(String selectedText, boolean commitSucceeds) {
            this.selectedText = selectedText;
            this.commitSucceeds = commitSucceeds;
        }

        @Override
        public boolean setSelection(int start, int end) {
            lastSelectionStart = start;
            lastSelectionEnd = end;
            return true;
        }

        @Override
        public CharSequence getSelectedText(int flags) {
            return selectedText;
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            return commitSucceeds;
        }

        @Override public CharSequence getTextBeforeCursor(int length, int flags) { return ""; }
        @Override public CharSequence getTextAfterCursor(int length, int flags) { return ""; }
        @Override public int getCursorCapsMode(int reqModes) { return 0; }
        @Override public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) { return null; }
        @Override public boolean deleteSurroundingText(int beforeLength, int afterLength) { return false; }
        @Override public boolean setComposingText(CharSequence text, int newCursorPosition) { return false; }
        @Override public boolean setComposingRegion(int start, int end) { return false; }
        @Override public boolean finishComposingText() { return false; }
        @Override public boolean commitCompletion(CompletionInfo text) { return false; }
        @Override public boolean commitCorrection(CorrectionInfo correctionInfo) { return false; }
        @Override public boolean sendKeyEvent(KeyEvent event) { return false; }
        @Override public boolean clearMetaKeyStates(int states) { return false; }
        @Override public boolean performEditorAction(int editorAction) { return false; }
        @Override public boolean performContextMenuAction(int id) { return false; }
        @Override public boolean beginBatchEdit() { return false; }
        @Override public boolean endBatchEdit() { return false; }
        @Override public boolean reportFullscreenMode(boolean enabled) { return false; }
        @Override public boolean performPrivateCommand(String action, Bundle data) { return false; }
        @Override public boolean requestCursorUpdates(int cursorUpdateMode) { return false; }
        @Override public Handler getHandler() { return null; }
        @Override public void closeConnection() { }
        @Override public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) { return false; }
        @Override public boolean commitContent(android.view.inputmethod.InputContentInfo inputContentInfo, int flags, Bundle opts) { return false; }
    }
}
