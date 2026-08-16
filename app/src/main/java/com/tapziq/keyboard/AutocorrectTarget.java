package com.tapziq.keyboard;

import android.text.Spanned;
import android.text.style.SuggestionSpan;
import android.view.inputmethod.ExtractedText;

import java.util.Objects;

/**
 * Immutable, fail-closed editor snapshot for one automatic correction request.
 *
 * <p>The model sees only the most recent completed clause (or a word-boundary-aligned suffix of
 * it), while the target retains the exact complete document and selection that produced the
 * request. An asynchronous result is therefore usable only while both remain unchanged.</p>
 */
final class AutocorrectTarget {
    static final int MAX_CHARACTERS = 500;
    static final int MAX_SNAPSHOT_CHARACTERS = 2_000;

    enum Failure {
        NONE,
        NO_TEXT,
        INVALID_SNAPSHOT,
        NOT_AT_BOUNDARY,
        TARGET_TOO_LONG
    }

    static final class Capture {
        final AutocorrectTarget target;
        final Failure failure;

        private Capture(AutocorrectTarget target, Failure failure) {
            this.target = target;
            this.failure = failure;
        }

        static Capture success(AutocorrectTarget target) {
            return new Capture(Objects.requireNonNull(target), Failure.NONE);
        }

        static Capture failure(Failure failure) {
            return new Capture(null, Objects.requireNonNull(failure));
        }

        boolean succeeded() {
            return target != null;
        }
    }

    private final String documentText;
    private final String text;
    private final int start;
    private final int end;
    private final int selectionStart;
    private final int selectionEnd;
    private final String committedBoundary;

    private AutocorrectTarget(
            String documentText,
            String text,
            int start,
            int end,
            int selectionStart,
            int selectionEnd,
            String committedBoundary
    ) {
        this.documentText = documentText;
        this.text = text;
        this.start = start;
        this.end = end;
        this.selectionStart = selectionStart;
        this.selectionEnd = selectionEnd;
        this.committedBoundary = committedBoundary;
    }

    /** Captures a target only from a complete {@link ExtractedText} snapshot. */
    static Capture capture(ExtractedText extracted, String committedBoundary) {
        if (extracted == null) {
            return Capture.failure(Failure.INVALID_SNAPSHOT);
        }
        return capture(
                extracted.text,
                extracted.startOffset,
                extracted.selectionStart,
                extracted.selectionEnd,
                extracted.startOffset == 0 && extracted.partialStartOffset < 0,
                committedBoundary
        );
    }

    /**
     * Pure capture entry point used by the IME adapter and local unit tests.
     * Selection offsets must describe a collapsed caret immediately after the exact boundary
     * string that the keyboard just committed.
     */
    static Capture capture(
            CharSequence extractedText,
            int startOffset,
            int selectionStart,
            int selectionEnd,
            boolean completeDocument,
            String committedBoundary
    ) {
        if (extractedText == null
                || startOffset != 0
                || !completeDocument
                || selectionStart != selectionEnd
                || hasNonEphemeralSpans(extractedText)) {
            return Capture.failure(Failure.INVALID_SNAPSHOT);
        }

        String document = extractedText.toString();
        if (document.length() > MAX_SNAPSHOT_CHARACTERS) {
            return Capture.failure(Failure.INVALID_SNAPSHOT);
        }
        int caret = selectionStart;
        if (caret < 0 || caret > document.length()) {
            return Capture.failure(Failure.INVALID_SNAPSHOT);
        }
        if (!isSupportedBoundary(committedBoundary)
                || caret < committedBoundary.length()
                || !document.regionMatches(
                        caret - committedBoundary.length(),
                        committedBoundary,
                        0,
                        committedBoundary.length()
                )) {
            return Capture.failure(Failure.NOT_AT_BOUNDARY);
        }

        int contentEnd = caret;
        while (contentEnd > 0 && isWhitespace(document.charAt(contentEnd - 1))) {
            contentEnd--;
        }
        if (contentEnd == 0) {
            return Capture.failure(Failure.NO_TEXT);
        }

        int scan = contentEnd - 1;
        while (scan >= 0 && isClosingDecoration(document.charAt(scan))) {
            scan--;
        }
        // A delimiter immediately before the triggering boundary belongs to this target. Search
        // for the delimiter before it so "First. Second. " selects "Second." rather than none.
        if (scan >= 0 && isClauseDelimiterAt(document, scan, contentEnd)) {
            scan--;
        }

        int previousDelimiter = -1;
        for (int index = scan; index >= 0; index--) {
            if (isClauseDelimiterAt(document, index, contentEnd)) {
                previousDelimiter = index;
                break;
            }
        }

        int contentStart = previousDelimiter + 1;
        while (contentStart < contentEnd
                && (isWhitespace(document.charAt(contentStart))
                || isClosingDecoration(document.charAt(contentStart)))) {
            contentStart++;
        }
        if (contentStart == contentEnd) {
            return Capture.failure(Failure.NO_TEXT);
        }

        if (contentEnd - contentStart > MAX_CHARACTERS) {
            contentStart = suffixStart(document, contentStart, contentEnd);
            if (contentStart < 0) {
                return Capture.failure(Failure.TARGET_TOO_LONG);
            }
        }

        String targetText = document.substring(contentStart, contentEnd);
        if (targetText.isBlank()) {
            return Capture.failure(Failure.NO_TEXT);
        }
        return Capture.success(new AutocorrectTarget(
                document,
                targetText,
                contentStart,
                contentEnd,
                selectionStart,
                selectionEnd,
                committedBoundary
        ));
    }

    static boolean isSupportedBoundary(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (!isWhitespace(codePoint) && !isTriggerPunctuation(codePoint)) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    String documentText() {
        return documentText;
    }

    String text() {
        return text;
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

    String committedBoundary() {
        return committedBoundary;
    }

    boolean matches(ExtractedText extracted) {
        if (extracted == null) {
            return false;
        }
        return matches(
                extracted.text,
                extracted.startOffset,
                extracted.selectionStart,
                extracted.selectionEnd,
                extracted.startOffset == 0 && extracted.partialStartOffset < 0
        );
    }

    /** Pure staleness check for an asynchronous model result. */
    boolean matches(
            CharSequence currentText,
            int startOffset,
            int currentSelectionStart,
            int currentSelectionEnd,
            boolean completeDocument
    ) {
        return currentText != null
                && startOffset == 0
                && completeDocument
                && !hasNonEphemeralSpans(currentText)
                && selectionStart == currentSelectionStart
                && selectionEnd == currentSelectionEnd
                && documentText.contentEquals(currentText);
    }

    /** Rich formatting is not safe to recreate during an automatic plain-text replacement. */
    static boolean hasNonEphemeralSpans(CharSequence value) {
        if (!(value instanceof Spanned)) {
            return false;
        }
        Spanned spanned = (Spanned) value;
        Object[] spans = spanned.getSpans(0, spanned.length(), Object.class);
        if (spans == null) {
            return true;
        }
        for (Object span : spans) {
            int flags = spanned.getSpanFlags(span);
            if ((flags & Spanned.SPAN_COMPOSING) != 0
                    || span instanceof SuggestionSpan
                    || "android.text.style.SpellCheckSpan".equals(span.getClass().getName())
                    || spanned.getSpanStart(span) == spanned.getSpanEnd(span)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static int suffixStart(String document, int clauseStart, int contentEnd) {
        int hardStart = contentEnd - MAX_CHARACTERS;
        if (hardStart <= clauseStart) {
            return clauseStart;
        }
        if (hardStart < contentEnd
                && Character.isLowSurrogate(document.charAt(hardStart))
                && Character.isHighSurrogate(document.charAt(hardStart - 1))) {
            hardStart++;
        }

        int candidate = hardStart;
        int precedingCodePoint = document.codePointBefore(candidate);
        int currentCodePoint = document.codePointAt(candidate);
        if (isWordPart(precedingCodePoint) && isWordPart(currentCodePoint)) {
            while (candidate < contentEnd) {
                int codePoint = document.codePointAt(candidate);
                if (!isWordPart(codePoint)) {
                    break;
                }
                candidate += Character.charCount(codePoint);
            }
        }
        // This is an artificial left seam: keep every separator at that seam outside the
        // model-controlled range. Otherwise deleting punctuation such as an em dash or slash
        // could join the untouched prefix to the corrected suffix.
        while (candidate < contentEnd && !isWordStart(document.codePointAt(candidate))) {
            candidate += Character.charCount(document.codePointAt(candidate));
        }
        return candidate >= contentEnd ? -1 : candidate;
    }

    private static boolean isClauseDelimiterAt(String document, int index, int contentEnd) {
        char character = document.charAt(index);
        if (character == ',' || character == ';' || character == ':') {
            return true;
        }
        if (character == '\n'
                || character == '\r'
                || Character.getType(character) == Character.LINE_SEPARATOR
                || Character.getType(character) == Character.PARAGRAPH_SEPARATOR) {
            return true;
        }
        if (character != '.' && character != '!' && character != '?' && character != '\u2026') {
            return false;
        }
        int next = index + 1;
        while (next < contentEnd && isClosingDecoration(document.charAt(next))) {
            next++;
        }
        return next >= contentEnd || isWhitespace(document.charAt(next));
    }

    private static boolean isTriggerPunctuation(int codePoint) {
        return codePoint == '.'
                || codePoint == ','
                || codePoint == '!'
                || codePoint == '?'
                || codePoint == ';'
                || codePoint == ':'
                || codePoint == 0x2026;
    }

    private static boolean isClosingDecoration(char character) {
        return character == '\''
                || character == '"'
                || character == '\u2019'
                || character == '\u201D'
                || character == ')'
                || character == ']'
                || character == '}';
    }

    static boolean isWordPart(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isLetterOrDigit(codePoint)
                || type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.CONNECTOR_PUNCTUATION
                || codePoint == '\''
                || codePoint == 0x2019
                || codePoint == '-';
    }

    private static boolean isWordStart(int codePoint) {
        return Character.isLetterOrDigit(codePoint);
    }

    private static boolean isWhitespace(char character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character);
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
