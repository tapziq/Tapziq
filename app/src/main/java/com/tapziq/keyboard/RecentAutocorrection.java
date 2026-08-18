package com.tapziq.keyboard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Minimal, privacy-bounded identity for a recently applied single-word autocorrection.
 *
 * <p>The original document is not retained. Only the exact word mapping, its absolute corrected
 * range, and fixed-size hashes of short context immediately beside the word are kept. This lets
 * the IME recognize a later tap without turning recent corrections into a typing-history log.</p>
 */
final class RecentAutocorrection {
    static final int CONTEXT_CHARACTER_LIMIT = 24;

    private static final byte LEFT_CONTEXT_DOMAIN = 0x4c;
    private static final byte RIGHT_CONTEXT_DOMAIN = 0x52;

    private final AutocorrectLearning.Feedback feedback;
    private final int start;
    private final int end;
    private final int leftContextLength;
    private final int rightContextLength;
    private final byte[] leftContextHash;
    private final byte[] rightContextHash;
    private final long createdAtNanos;

    private RecentAutocorrection(
            AutocorrectLearning.Feedback feedback,
            int start,
            int end,
            int leftContextLength,
            int rightContextLength,
            byte[] leftContextHash,
            byte[] rightContextHash,
            long createdAtNanos
    ) {
        this.feedback = feedback;
        this.start = start;
        this.end = end;
        this.leftContextLength = leftContextLength;
        this.rightContextLength = rightContextLength;
        this.leftContextHash = leftContextHash;
        this.rightContextHash = rightContextHash;
        this.createdAtNanos = createdAtNanos;
    }

    /** Returns {@code null} unless {@code edit} is exactly one whole-word replacement. */
    static RecentAutocorrection from(AutocorrectEdit edit) {
        if (edit == null) {
            return null;
        }
        return from(edit, AutocorrectLearning.fromEdit(edit));
    }

    /**
     * Variant for callers that already derived the feedback while applying the correction.
     * Mismatched or compound feedback is rejected rather than retained.
     */
    static RecentAutocorrection from(
            AutocorrectEdit edit,
            AutocorrectLearning.Feedback feedback
    ) {
        if (edit == null || feedback == null) {
            return null;
        }
        AutocorrectLearning.Feedback derived = AutocorrectLearning.fromEdit(edit);
        if (derived == null
                || !derived.written.equals(feedback.written)
                || !derived.rejected.equals(feedback.rejected)
                || derived.sourceStart != feedback.sourceStart
                || derived.sourceEnd != feedback.sourceEnd) {
            return null;
        }

        String source = edit.sourceDocument();
        String corrected = edit.resultingDocument();
        int start = feedback.sourceStart;
        int sourceEnd = feedback.sourceEnd;
        int end = start + feedback.rejected.length();
        if (!isExactTokenAt(source, feedback.written, start, sourceEnd)
                || !isExactTokenAt(corrected, feedback.rejected, start, end)
                || !corrected.equals(
                        source.substring(0, start)
                                + feedback.rejected
                                + source.substring(sourceEnd))) {
            return null;
        }

        int leftStart = boundedLeftStart(corrected, start);
        int rightEnd = boundedRightEnd(corrected, end);
        return new RecentAutocorrection(
                feedback,
                start,
                end,
                start - leftStart,
                rightEnd - end,
                hashContext(LEFT_CONTEXT_DOMAIN, corrected, leftStart, start),
                hashContext(RIGHT_CONTEXT_DOMAIN, corrected, end, rightEnd),
                System.nanoTime()
        );
    }

    AutocorrectLearning.Feedback feedback() {
        return feedback;
    }

    int start() {
        return start;
    }

    int end() {
        return end;
    }

    long createdAtNanos() {
        return createdAtNanos;
    }

    boolean isExpired(long nowNanos, long timeoutNanos) {
        return timeoutNanos <= 0L || nowNanos - createdAtNanos >= timeoutNanos;
    }

    /**
     * Checks the word and its bounded anchors without requiring the selection to remain nearby.
     * Text appended beyond the original right anchor is intentionally allowed.
     */
    boolean matchesDocument(
            CharSequence currentText,
            int startOffset,
            boolean completeDocument
    ) {
        if (currentText == null || startOffset != 0 || !completeDocument) {
            return false;
        }
        String document = currentText.toString();
        int leftStart = start - leftContextLength;
        int rightEnd = end + rightContextLength;
        if (leftStart < 0
                || end < start
                || rightEnd > document.length()
                || !isExactTokenAt(document, feedback.rejected, start, end)) {
            return false;
        }
        return MessageDigest.isEqual(
                leftContextHash,
                hashContext(LEFT_CONTEXT_DOMAIN, document, leftStart, start)
        ) && MessageDigest.isEqual(
                rightContextHash,
                hashContext(RIGHT_CONTEXT_DOMAIN, document, end, rightEnd)
        );
    }

    /** Checks the retained document identity and whether the selection intersects the word. */
    boolean matches(
            CharSequence currentText,
            int startOffset,
            int selectionStart,
            int selectionEnd,
            boolean completeDocument
    ) {
        if (!matchesDocument(currentText, startOffset, completeDocument)
                || selectionStart < 0
                || selectionEnd < 0
                || selectionStart > currentText.length()
                || selectionEnd > currentText.length()) {
            return false;
        }
        int selectionLow = Math.min(selectionStart, selectionEnd);
        int selectionHigh = Math.max(selectionStart, selectionEnd);
        if (selectionLow == selectionHigh) {
            return selectionLow >= start && selectionLow < end;
        }
        // A phrase/sentence selection that merely happens to cross this word is not a word tap.
        return selectionLow >= start && selectionHigh <= end;
    }

    private static boolean isExactTokenAt(
            String document,
            String token,
            int start,
            int end
    ) {
        if (!AutocorrectLearning.isValidToken(token)
                || start < 0
                || end != start + token.length()
                || end > document.length()
                || !document.regionMatches(start, token, 0, token.length())) {
            return false;
        }
        return (start == 0 || !isTokenPart(document.codePointBefore(start)))
                && (end == document.length() || !isTokenPart(document.codePointAt(end)));
    }

    private static boolean isTokenPart(int codePoint) {
        return AutocorrectTarget.isWordPart(codePoint)
                || codePoint == '\''
                || codePoint == 0x2019
                || Character.getType(codePoint) == Character.DASH_PUNCTUATION;
    }

    private static int boundedLeftStart(String document, int end) {
        int start = Math.max(0, end - CONTEXT_CHARACTER_LIMIT);
        if (start > 0
                && start < document.length()
                && Character.isLowSurrogate(document.charAt(start))
                && Character.isHighSurrogate(document.charAt(start - 1))) {
            start++;
        }
        return start;
    }

    private static int boundedRightEnd(String document, int start) {
        int end = Math.min(document.length(), start + CONTEXT_CHARACTER_LIMIT);
        if (end > start
                && end < document.length()
                && Character.isLowSurrogate(document.charAt(end))
                && Character.isHighSurrogate(document.charAt(end - 1))) {
            end--;
        }
        return end;
    }

    private static byte[] hashContext(
            byte domain,
            String document,
            int start,
            int end
    ) {
        MessageDigest digest = sha256();
        digest.update(domain);
        digest.update(document.substring(start, end).getBytes(StandardCharsets.UTF_8));
        return digest.digest();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("Every supported Android runtime provides SHA-256", impossible);
        }
    }
}
