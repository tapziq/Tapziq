package com.tapziq.keyboard;

import android.view.inputmethod.ExtractedText;

import java.util.Objects;

/** A validated automatic replacement plus cursor math that is independent of Android APIs. */
final class AutocorrectEdit {
    private static final int MIN_LENGTH_ALLOWANCE = 2;
    private static final int MAX_LENGTH_ALLOWANCE = 24;

    enum Failure {
        NONE,
        EMPTY,
        UNCHANGED,
        BOUNDARY_WHITESPACE,
        SEAM_CHANGED,
        STRUCTURE_CHANGED,
        LENGTH_CHANGE_TOO_LARGE,
        EDIT_DISTANCE_TOO_LARGE,
        DOCUMENT_TOO_LONG,
        INVALID_UNICODE
    }

    static final class Validation {
        final AutocorrectEdit edit;
        final Failure failure;

        private Validation(AutocorrectEdit edit, Failure failure) {
            this.edit = edit;
            this.failure = failure;
        }

        static Validation success(AutocorrectEdit edit) {
            return new Validation(Objects.requireNonNull(edit), Failure.NONE);
        }

        static Validation failure(Failure failure) {
            return new Validation(null, Objects.requireNonNull(failure));
        }

        boolean succeeded() {
            return edit != null;
        }
    }

    private final AutocorrectTarget target;
    private final String correctedText;
    private final int start;
    private final int end;
    private final String original;
    private final String replacement;
    private final int trailingCharacterCount;

    private AutocorrectEdit(AutocorrectTarget target, String correctedText) {
        this.target = target;
        this.correctedText = correctedText;

        int commonPrefix = commonPrefixLength(target.text(), correctedText);
        int[] suffixStarts = commonSuffixStarts(target.text(), correctedText, commonPrefix);
        this.start = target.start() + commonPrefix;
        this.end = target.start() + suffixStarts[0];
        this.original = target.text().substring(commonPrefix, suffixStarts[0]);
        this.replacement = correctedText.substring(commonPrefix, suffixStarts[1]);
        this.trailingCharacterCount = target.selectionStart() - end;
    }

    /**
     * Accepts only a small, nonblank, visible change. Formatting controls and non-ordinary
     * whitespace must remain in the same order, and model-added boundary whitespace is rejected
     * instead of silently trimmed before an automatic edit.
     */
    static Validation validate(AutocorrectTarget target, String suggestion) {
        Objects.requireNonNull(target, "target");
        if (suggestion == null || suggestion.isBlank()) {
            return Validation.failure(Failure.EMPTY);
        }
        if (!isWellFormedUtf16(suggestion)) {
            return Validation.failure(Failure.INVALID_UNICODE);
        }
        if (isWhitespace(suggestion.charAt(0))
                || isWhitespace(suggestion.charAt(suggestion.length() - 1))) {
            return Validation.failure(Failure.BOUNDARY_WHITESPACE);
        }
        if (suggestion.equals(target.text())) {
            return Validation.failure(Failure.UNCHANGED);
        }
        if (!preservesRightSeam(target, suggestion)) {
            return Validation.failure(Failure.SEAM_CHANGED);
        }
        int lengthDelta = Math.abs(suggestion.length() - target.text().length());
        if (lengthDelta > lengthAllowance(target.text().length())) {
            return Validation.failure(Failure.LENGTH_CHANGE_TOO_LARGE);
        }
        if (!structuralAnchors(suggestion).equals(structuralAnchors(target.text()))) {
            return Validation.failure(Failure.STRUCTURE_CHANGED);
        }
        if (!isWithinEditDistance(
                target.text(),
                suggestion,
                editDistanceAllowance(target.text().length())
        )) {
            return Validation.failure(Failure.EDIT_DISTANCE_TOO_LARGE);
        }
        int resultingDocumentLength = target.documentText().length()
                - target.text().length()
                + suggestion.length();
        if (resultingDocumentLength > AutocorrectTarget.MAX_SNAPSHOT_CHARACTERS) {
            return Validation.failure(Failure.DOCUMENT_TOO_LONG);
        }
        return Validation.success(new AutocorrectEdit(target, suggestion));
    }

    private static boolean preservesRightSeam(
            AutocorrectTarget target,
            String suggestion
    ) {
        String document = target.documentText();
        if (target.selectionStart() >= document.length()
                || target.end() != target.selectionStart()) {
            return true;
        }
        // A punctuation boundary inside a document may be the only separator before any kind of
        // untouched Unicode continuation (word, format control, emoji modifier, or grapheme).
        // Preserve that exact boundary instead of trying to classify every possible continuation.
        return suggestion.endsWith(target.committedBoundary());
    }

    int start() {
        return start;
    }

    int end() {
        return end;
    }

    int appliedEnd() {
        return start() + replacement.length();
    }

    String original() {
        return original;
    }

    String suggestion() {
        return replacement;
    }

    String correctedText() {
        return correctedText;
    }

    String sourceDocument() {
        return target.documentText();
    }

    /**
     * Value for InputConnection.replaceText/commitText's newCursorPosition argument.
     * A value of one places the cursor after the replacement; larger values retain any boundary
     * characters that were deliberately left outside the replaced range.
     */
    int replacementCursorPosition() {
        return trailingCharacterCount + 1;
    }

    /** Cursor argument for replacing the applied suggestion with {@link #original()} on undo. */
    int undoCursorPosition() {
        return trailingCharacterCount + 1;
    }

    /** Absolute collapsed-caret position expected after applying this edit. */
    int caretAfter() {
        return target.selectionStart() + correctedText.length() - target.text().length();
    }

    boolean matches(ExtractedText extracted) {
        return target.matches(extracted);
    }

    boolean matchesApplied(ExtractedText extracted) {
        if (extracted == null) {
            return false;
        }
        return matchesApplied(
                extracted.text,
                extracted.startOffset,
                extracted.selectionStart,
                extracted.selectionEnd,
                extracted.startOffset == 0 && extracted.partialStartOffset < 0
        );
    }

    boolean matches(
            CharSequence currentText,
            int startOffset,
            int selectionStart,
            int selectionEnd,
            boolean completeDocument
    ) {
        return target.matches(
                currentText,
                startOffset,
                selectionStart,
                selectionEnd,
                completeDocument
        );
    }

    /** Exact post-application state check used before offering a one-Backspace undo. */
    boolean matchesApplied(
            CharSequence currentText,
            int startOffset,
            int selectionStart,
            int selectionEnd,
            boolean completeDocument
    ) {
        return currentText != null
                && startOffset == 0
                && completeDocument
                && !AutocorrectTarget.hasNonEphemeralSpans(currentText)
                && selectionStart == caretAfter()
                && selectionEnd == caretAfter()
                && resultingDocument().contentEquals(currentText);
    }

    /** Deterministic expected result, useful for verification and legacy connection adapters. */
    String resultingDocument() {
        String document = target.documentText();
        return document.substring(0, start())
                + replacement
                + document.substring(end());
    }

    private static int commonPrefixLength(String original, String corrected) {
        int prefix = 0;
        int limit = Math.min(original.length(), corrected.length());
        while (prefix < limit && original.charAt(prefix) == corrected.charAt(prefix)) {
            prefix++;
        }
        if (prefix > 0
                && prefix < original.length()
                && prefix < corrected.length()
                && Character.isHighSurrogate(original.charAt(prefix - 1))) {
            prefix--;
        }
        return prefix;
    }

    private static int[] commonSuffixStarts(
            String original,
            String corrected,
            int commonPrefix
    ) {
        int originalStart = original.length();
        int correctedStart = corrected.length();
        while (originalStart > commonPrefix
                && correctedStart > commonPrefix
                && original.charAt(originalStart - 1) == corrected.charAt(correctedStart - 1)) {
            originalStart--;
            correctedStart--;
        }
        if (originalStart < original.length()
                && correctedStart < corrected.length()
                && Character.isLowSurrogate(original.charAt(originalStart))) {
            originalStart++;
            correctedStart++;
        }
        return new int[]{originalStart, correctedStart};
    }

    static int lengthAllowance(int originalLength) {
        int relativeAllowance = (originalLength + 5) / 6;
        return Math.min(
                MAX_LENGTH_ALLOWANCE,
                Math.max(MIN_LENGTH_ALLOWANCE, relativeAllowance)
        );
    }

    static int editDistanceAllowance(int originalLength) {
        int relativeAllowance = (originalLength + 11) / 12;
        return Math.min(
                MAX_LENGTH_ALLOWANCE,
                Math.max(3, relativeAllowance)
        );
    }

    /**
     * Bounded Levenshtein distance. Prompt instructions are not a safety boundary, so a model
     * response may change only a small number of UTF-16 code units before it can be auto-applied.
     */
    private static boolean isWithinEditDistance(String original, String suggestion, int limit) {
        if (Math.abs(original.length() - suggestion.length()) > limit) {
            return false;
        }
        int[] previous = new int[suggestion.length() + 1];
        int[] current = new int[suggestion.length() + 1];
        for (int column = 0; column <= suggestion.length(); column++) {
            previous[column] = column;
        }
        for (int row = 1; row <= original.length(); row++) {
            current[0] = row;
            char originalCharacter = original.charAt(row - 1);
            for (int column = 1; column <= suggestion.length(); column++) {
                int substitutionCost = originalCharacter == suggestion.charAt(column - 1) ? 0 : 1;
                current[column] = Math.min(
                        Math.min(current[column - 1] + 1, previous[column] + 1),
                        previous[column - 1] + substitutionCost
                );
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[suggestion.length()] <= limit;
    }

    private static String structuralAnchors(String value) {
        StringBuilder structural = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            int type = Character.getType(codePoint);
            if ((isWhitespace(codePoint) && codePoint != ' ')
                    || Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                structural.append(offset).append(':').append(codePoint).append(';');
            }
            offset += Character.charCount(codePoint);
        }
        return structural.toString();
    }

    private static boolean isWellFormedUtf16(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return false;
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWhitespace(char character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character);
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
