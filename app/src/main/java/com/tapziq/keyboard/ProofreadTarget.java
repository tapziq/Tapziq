package com.tapziq.keyboard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/** A short, immutable snapshot of the editor text the user asked Tapziq to proofread. */
final class ProofreadTarget {
    static final int MAX_CHARACTERS = 500;
    static final int MAX_SNAPSHOT_CHARACTERS = 2_000;

    enum Failure {
        NONE,
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

    final String text;
    private final int start;
    private final int end;
    private final int selectionStart;
    private final int selectionEnd;
    private final byte[] documentDigest;

    private ProofreadTarget(
            String text,
            int start,
            int end,
            int selectionStart,
            int selectionEnd,
            byte[] documentDigest
    ) {
        this.text = text;
        this.start = start;
        this.end = end;
        this.selectionStart = selectionStart;
        this.selectionEnd = selectionEnd;
        this.documentDigest = documentDigest;
    }

    /**
     * Captures the active selection, or the full field when there is no selection.
     * Selection offsets are relative to {@code extractedText}; {@code startOffset}
     * locates the extracted slice within the editor's full text.
     */
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

        String value = extractedText.toString();
        if (value.length() > MAX_SNAPSHOT_CHARACTERS) {
            return Capture.failure(Failure.INVALID_SELECTION);
        }
        int localStart;
        int localEnd;
        boolean fullField = selectionStart == selectionEnd;
        if (fullField) {
            localStart = 0;
            localEnd = value.length();
        } else {
            localStart = Math.min(selectionStart, selectionEnd);
            localEnd = Math.max(selectionStart, selectionEnd);
            if (localStart < 0 || localEnd > value.length()) {
                return Capture.failure(Failure.INVALID_SELECTION);
            }
        }

        int[] contentBounds = trimmedBounds(value, localStart, localEnd);
        localStart = contentBounds[0];
        localEnd = contentBounds[1];
        if (localStart == localEnd) {
            return Capture.failure(Failure.NO_TEXT);
        }
        if (localEnd - localStart > MAX_CHARACTERS) {
            return Capture.failure(Failure.TOO_LONG);
        }

        return Capture.success(new ProofreadTarget(
                value.substring(localStart, localEnd),
                startOffset + localStart,
                startOffset + localEnd,
                startOffset + Math.min(selectionStart, selectionEnd),
                startOffset + Math.max(selectionStart, selectionEnd),
                digest(value)
        ));
    }

    int start() {
        return start;
    }

    int end() {
        return end;
    }

    int selectionStart() {
        return selectionStart;
    }

    int selectionEnd() {
        return selectionEnd;
    }

    static String normalizeSuggestion(String suggestion) {
        if (suggestion == null) {
            return null;
        }
        int[] bounds = trimmedBounds(suggestion, 0, suggestion.length());
        return suggestion.substring(bounds[0], bounds[1]);
    }

    boolean matches(android.view.inputmethod.ExtractedText extracted) {
        if (!matchesDocument(extracted)) {
            return false;
        }
        int currentSelectionStart = extracted.startOffset
                + Math.min(extracted.selectionStart, extracted.selectionEnd);
        int currentSelectionEnd = extracted.startOffset
                + Math.max(extracted.selectionStart, extracted.selectionEnd);
        return currentSelectionStart == selectionStart && currentSelectionEnd == selectionEnd;
    }

    /** Same immutable source document, regardless of where the user moved the selection. */
    boolean matchesDocument(android.view.inputmethod.ExtractedText extracted) {
        if (extracted == null
                || extracted.text == null
                || extracted.startOffset != 0
                || extracted.partialStartOffset >= 0
                || extracted.text.length() > MAX_SNAPSHOT_CHARACTERS
                || !Arrays.equals(documentDigest, digest(extracted.text.toString()))) {
            return false;
        }
        CharSequence extractedText = extracted.text;
        int startOffset = extracted.startOffset;
        int localStart = start - startOffset;
        int localEnd = end - startOffset;
        if (localStart < 0 || localEnd > extractedText.length() || localStart > localEnd) {
            return false;
        }
        return text.contentEquals(extractedText.subSequence(localStart, localEnd));
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError("SHA-256 is required by Android", error);
        }
    }

    private static int[] trimmedBounds(String value, int start, int end) {
        while (start < end && Character.isWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return new int[]{start, end};
    }
}
