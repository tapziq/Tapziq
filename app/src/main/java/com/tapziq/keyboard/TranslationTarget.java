package com.tapziq.keyboard;

import java.util.Objects;

/** Selection-only capture and bounded result validation for manual translation. */
final class TranslationTarget {
    static final int MAX_RESULT_CHARACTERS = 2_000;

    enum Failure {
        NONE,
        NO_SELECTION,
        NO_TEXT,
        TOO_LONG,
        INVALID_SELECTION
    }

    static final class Capture {
        final ProofreadTarget target;
        final Failure failure;

        private Capture(ProofreadTarget target, Failure failure) {
            this.target = target;
            this.failure = failure;
        }

        static Capture success(ProofreadTarget target) {
            return new Capture(Objects.requireNonNull(target), Failure.NONE);
        }

        static Capture failure(Failure failure) {
            return new Capture(null, failure);
        }

        boolean succeeded() {
            return target != null;
        }
    }

    private TranslationTarget() {
    }

    static Capture fromExtracted(
            CharSequence extractedText,
            int startOffset,
            int selectionStart,
            int selectionEnd,
            boolean completeDocument
    ) {
        if (extractedText == null) {
            return Capture.failure(Failure.NO_TEXT);
        }
        if (startOffset != 0 || !completeDocument) {
            return Capture.failure(Failure.INVALID_SELECTION);
        }
        if (selectionStart == selectionEnd) {
            return Capture.failure(Failure.NO_SELECTION);
        }

        ProofreadTarget.Capture capture = ProofreadTarget.fromExtracted(
                extractedText,
                startOffset,
                selectionStart,
                selectionEnd,
                completeDocument
        );
        if (capture.succeeded()) {
            return Capture.success(capture.target);
        }
        switch (capture.failure) {
            case NO_TEXT:
                return Capture.failure(Failure.NO_TEXT);
            case TOO_LONG:
                return Capture.failure(Failure.TOO_LONG);
            case INVALID_SELECTION:
            default:
                return Capture.failure(Failure.INVALID_SELECTION);
        }
    }

    static String normalizeResult(CharSequence translatedText) {
        if (translatedText == null) {
            return null;
        }
        String normalized = ProofreadTarget.normalizeSuggestion(translatedText.toString());
        if (normalized == null
                || normalized.isEmpty()
                || normalized.length() > MAX_RESULT_CHARACTERS) {
            return null;
        }
        return normalized;
    }
}
