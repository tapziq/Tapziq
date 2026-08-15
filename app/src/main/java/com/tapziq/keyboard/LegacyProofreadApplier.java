package com.tapziq.keyboard;

import android.view.inputmethod.InputConnection;

/** Fail-closed replacement path for Android 13 and older editors. */
final class LegacyProofreadApplier {
    enum Result {
        APPLIED,
        STALE,
        FAILED
    }

    private LegacyProofreadApplier() {
    }

    static Result apply(
            InputConnection connection,
            ProofreadTarget target,
            String suggestion
    ) {
        if (!connection.setSelection(target.start(), target.end())) {
            return Result.STALE;
        }
        CharSequence selected = connection.getSelectedText(0);
        if (selected == null || !target.text.contentEquals(selected)) {
            restoreSelection(connection, target);
            return Result.STALE;
        }
        if (!connection.commitText(suggestion, 1)) {
            restoreSelection(connection, target);
            return Result.FAILED;
        }
        return Result.APPLIED;
    }

    private static void restoreSelection(InputConnection connection, ProofreadTarget target) {
        connection.setSelection(target.selectionStart(), target.selectionEnd());
    }
}
