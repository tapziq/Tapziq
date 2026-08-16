package com.tapziq.keyboard;

import android.annotation.SuppressLint;
import android.os.Build;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

/** Applies and undoes a validated autocorrection while preserving its trailing boundary. */
final class AutocorrectApplier {
    enum Result {
        APPLIED,
        STALE,
        FAILED
    }

    private AutocorrectApplier() {
    }

    static Result apply(InputConnection connection, AutocorrectEdit edit) {
        return apply(connection, edit, Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
    }

    static Result undo(InputConnection connection, AutocorrectEdit edit) {
        return undo(connection, edit, Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
    }

    static Result apply(InputConnection connection, AutocorrectEdit edit, boolean useReplaceText) {
        ExtractedText current = connection.getExtractedText(extractedTextRequest(), 0);
        if (!edit.matches(current)) {
            return Result.STALE;
        }
        int originalCaret = edit.end() + edit.replacementCursorPosition() - 1;
        Result result = replace(
                connection,
                edit.start(),
                edit.end(),
                edit.original(),
                edit.suggestion(),
                edit.replacementCursorPosition(),
                originalCaret,
                edit.sourceDocument(),
                useReplaceText
        );
        if (result != Result.APPLIED) {
            return result;
        }
        if (!edit.matchesApplied(connection.getExtractedText(extractedTextRequest(), 0))) {
            return Result.FAILED;
        }
        notifyCorrection(connection, edit.start(), edit.original(), edit.suggestion());
        return Result.APPLIED;
    }

    static Result undo(InputConnection connection, AutocorrectEdit edit, boolean useReplaceText) {
        ExtractedText current = connection.getExtractedText(extractedTextRequest(), 0);
        if (!edit.matchesApplied(current)) {
            return Result.STALE;
        }
        Result result = replace(
                connection,
                edit.start(),
                edit.appliedEnd(),
                edit.suggestion(),
                edit.original(),
                edit.undoCursorPosition(),
                edit.caretAfter(),
                edit.resultingDocument(),
                useReplaceText
        );
        if (result != Result.APPLIED) {
            return result;
        }
        if (!edit.matches(connection.getExtractedText(extractedTextRequest(), 0))) {
            return Result.FAILED;
        }
        notifyCorrection(connection, edit.start(), edit.suggestion(), edit.original());
        return Result.APPLIED;
    }

    private static Result replace(
            InputConnection connection,
            int start,
            int end,
            String expected,
            String replacement,
            int cursorPosition,
            int restoreCaret,
            String expectedDocument,
            boolean useReplaceText
    ) {
        connection.beginBatchEdit();
        try {
            boolean replaced;
            if (useReplaceText) {
                replaced = Api34.replaceText(
                        connection,
                        start,
                        end,
                        replacement,
                        cursorPosition
                );
            } else {
                if (!connection.setSelection(start, end)) {
                    restoreSelection(connection, restoreCaret);
                    return Result.STALE;
                }
                ExtractedText selectedState = connection.getExtractedText(
                        extractedTextRequest(),
                        0
                );
                if (!matchesSelectionState(selectedState, expectedDocument, start, end)) {
                    restoreSelection(connection, restoreCaret);
                    return Result.STALE;
                }
                if (!expected.isEmpty()) {
                    CharSequence selected = connection.getSelectedText(0);
                    if (selected == null || !expected.contentEquals(selected)) {
                        restoreSelection(connection, restoreCaret);
                        return Result.STALE;
                    }
                }
                replaced = connection.commitText(replacement, cursorPosition);
            }
            if (!replaced) {
                restoreSelection(connection, restoreCaret);
                return Result.FAILED;
            }
            return Result.APPLIED;
        } finally {
            connection.endBatchEdit();
        }
    }

    private static void restoreSelection(InputConnection connection, int caret) {
        connection.setSelection(caret, caret);
    }

    private static boolean matchesSelectionState(
            ExtractedText extracted,
            String expectedDocument,
            int start,
            int end
    ) {
        return extracted != null
                && extracted.text != null
                && extracted.startOffset == 0
                && extracted.partialStartOffset < 0
                && !AutocorrectTarget.hasNonEphemeralSpans(extracted.text)
                && expectedDocument.contentEquals(extracted.text)
                && extracted.selectionStart == start
                && extracted.selectionEnd == end;
    }

    private static void notifyCorrection(
            InputConnection connection,
            int start,
            String oldText,
            String newText
    ) {
        // This only notifies correction-aware editors after the exact post-state was observed.
        connection.commitCorrection(new CorrectionInfo(start, oldText, newText));
    }

    private static ExtractedTextRequest extractedTextRequest() {
        ExtractedTextRequest request = new ExtractedTextRequest();
        request.flags = InputConnection.GET_TEXT_WITH_STYLES;
        request.hintMaxChars = AutocorrectTarget.MAX_SNAPSHOT_CHARACTERS + 1;
        request.hintMaxLines = 20;
        return request;
    }

    /** Keeps the API-34-only call isolated from the legacy implementation. */
    private static final class Api34 {
        private Api34() {
        }

        @SuppressLint("NewApi")
        static boolean replaceText(
                InputConnection connection,
                int start,
                int end,
                String replacement,
                int cursorPosition
        ) {
            return connection.replaceText(start, end, replacement, cursorPosition, null);
        }
    }
}
