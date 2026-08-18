package com.tapziq.keyboard;

/** Tracks only an exact same-word replacement after a Tapziq-owned suggestion rejection. */
final class AutocorrectLearningSession {
    enum Outcome {
        ACTIVE,
        COMPLETE,
        REPLACEMENT,
        REJECTED_ACCEPTED,
        INVALID
    }

    static final class Observation {
        final Outcome outcome;
        final String replacement;

        private Observation(Outcome outcome, String replacement) {
            this.outcome = outcome;
            this.replacement = replacement;
        }

        static Observation of(Outcome outcome) {
            return new Observation(outcome, null);
        }

        static Observation replacement(String replacement) {
            return new Observation(Outcome.REPLACEMENT, replacement);
        }
    }

    static final class Decision {
        final boolean keepSession;
        final boolean recordRejection;
        final boolean recordReplacement;
        final boolean forgetRejection;

        private Decision(
                boolean keepSession,
                boolean recordRejection,
                boolean recordReplacement,
                boolean forgetRejection
        ) {
            this.keepSession = keepSession;
            this.recordRejection = recordRejection;
            this.recordReplacement = recordReplacement;
            this.forgetRejection = forgetRejection;
        }
    }

    private final String baselineDocument;
    private final int tokenStart;
    private final int tokenEnd;
    private final String suffix;
    private final AutocorrectLearning.Feedback feedback;
    private final boolean baselineIsRejected;

    private AutocorrectLearningSession(
            String baselineDocument,
            int tokenStart,
            int tokenEnd,
            AutocorrectLearning.Feedback feedback,
            boolean baselineIsRejected
    ) {
        this.baselineDocument = baselineDocument;
        this.tokenStart = tokenStart;
        this.tokenEnd = tokenEnd;
        this.suffix = baselineDocument.substring(tokenEnd);
        this.feedback = feedback;
        this.baselineIsRejected = baselineIsRejected;
    }

    static AutocorrectLearningSession begin(
            String baselineDocument,
            int tokenStart,
            int tokenEnd,
            AutocorrectLearning.Feedback feedback
    ) {
        return begin(baselineDocument, tokenStart, tokenEnd, feedback, false);
    }

    static AutocorrectLearningSession begin(
            String baselineDocument,
            int tokenStart,
            int tokenEnd,
            AutocorrectLearning.Feedback feedback,
            boolean baselineIsRejected
    ) {
        String expectedToken = feedback == null
                ? null
                : baselineIsRejected ? feedback.rejected : feedback.written;
        if (baselineDocument == null
                || baselineDocument.length() > ProofreadTarget.MAX_SNAPSHOT_CHARACTERS
                || feedback == null
                || tokenStart < 0
                || tokenEnd <= tokenStart
                || tokenEnd > baselineDocument.length()
                || !expectedToken.equals(baselineDocument.substring(tokenStart, tokenEnd))) {
            return null;
        }
        return new AutocorrectLearningSession(
                baselineDocument,
                tokenStart,
                tokenEnd,
                feedback,
                baselineIsRejected
        );
    }

    AutocorrectLearning.Feedback feedback() {
        return feedback;
    }

    static Decision decide(
            Observation observation,
            boolean fromTappedCorrection,
            boolean rejectionAlreadyRecorded,
            boolean sawTapziqKey
    ) {
        boolean recordRejection = fromTappedCorrection
                && !rejectionAlreadyRecorded
                && ((!sawTapziqKey && observation.outcome == Outcome.COMPLETE)
                        || (sawTapziqKey && observation.outcome == Outcome.REPLACEMENT));
        boolean rejectionRecorded = rejectionAlreadyRecorded || recordRejection;
        boolean keepSession = observation.outcome == Outcome.ACTIVE
                || (observation.outcome == Outcome.COMPLETE
                        && !sawTapziqKey
                        && rejectionRecorded);
        boolean recordReplacement = observation.outcome == Outcome.REPLACEMENT
                && sawTapziqKey
                && rejectionRecorded;
        boolean forgetRejection = observation.outcome == Outcome.REJECTED_ACCEPTED
                && sawTapziqKey;
        return new Decision(
                keepSession,
                recordRejection,
                recordReplacement,
                forgetRejection
        );
    }

    Observation observe(
            String currentDocument,
            int selectionStart,
            int selectionEnd,
            boolean finalize,
            boolean keepWhileSelectionTouchesReplacement
    ) {
        if (currentDocument == null
                || currentDocument.length() > ProofreadTarget.MAX_SNAPSHOT_CHARACTERS
                || selectionStart < 0
                || selectionEnd < 0
                || selectionStart > currentDocument.length()
                || selectionEnd > currentDocument.length()) {
            return Observation.of(Outcome.INVALID);
        }
        if (baselineDocument.equals(currentDocument)) {
            if (!finalize || (keepWhileSelectionTouchesReplacement
                    && selectionTouches(selectionStart, selectionEnd, tokenEnd, false))) {
                return Observation.of(Outcome.ACTIVE);
            }
            return Observation.of(Outcome.COMPLETE);
        }
        if (!currentDocument.startsWith(baselineDocument.substring(0, tokenStart))
                || !currentDocument.endsWith(suffix)
                || currentDocument.length() < tokenStart + suffix.length()) {
            return Observation.of(Outcome.INVALID);
        }
        int replacementEnd = currentDocument.length() - suffix.length();
        if (replacementEnd < tokenStart) {
            return Observation.of(Outcome.INVALID);
        }
        String replacement = currentDocument.substring(tokenStart, replacementEnd);
        boolean selectionTouches = selectionTouches(
                selectionStart,
                selectionEnd,
                replacementEnd,
                !finalize
        );
        if (!finalize) {
            return selectionTouches
                    ? Observation.of(Outcome.ACTIVE)
                    : Observation.of(Outcome.INVALID);
        }
        if (keepWhileSelectionTouchesReplacement && selectionTouches) {
            return Observation.of(Outcome.ACTIVE);
        }
        if (!AutocorrectLearning.isValidToken(replacement)
                || (!baselineIsRejected && replacement.equals(feedback.written))) {
            return Observation.of(Outcome.COMPLETE);
        }
        if (!baselineIsRejected && replacement.equals(feedback.rejected)) {
            return Observation.of(Outcome.REJECTED_ACCEPTED);
        }
        if (baselineIsRejected && replacement.equals(feedback.rejected)) {
            return Observation.of(Outcome.COMPLETE);
        }
        return Observation.replacement(replacement);
    }

    private boolean selectionTouches(
            int selectionStart,
            int selectionEnd,
            int replacementEnd,
            boolean includeCollapsedEnd
    ) {
        int start = Math.min(selectionStart, selectionEnd);
        int end = Math.max(selectionStart, selectionEnd);
        if (start == end) {
            return replacementEnd == tokenStart
                    ? start == tokenStart
                    : start >= tokenStart
                            && (start < replacementEnd
                                    || (includeCollapsedEnd && start == replacementEnd));
        }
        return start < replacementEnd && end > tokenStart;
    }
}
