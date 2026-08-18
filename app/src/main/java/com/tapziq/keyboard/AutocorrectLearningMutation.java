package com.tapziq.keyboard;

/** Exact document transformations attributable to one Tapziq text key. */
final class AutocorrectLearningMutation {
    private AutocorrectLearningMutation() {
    }

    static String replacement(
            String document,
            int selectionStart,
            int selectionEnd,
            String replacement
    ) {
        if (document == null || replacement == null) {
            return null;
        }
        int start = Math.min(selectionStart, selectionEnd);
        int end = Math.max(selectionStart, selectionEnd);
        if (start < 0 || end < start || end > document.length()) {
            return null;
        }
        return document.substring(0, start) + replacement + document.substring(end);
    }

    static String deleteBeforeCursor(
            String document,
            int selectionStart,
            int selectionEnd
    ) {
        if (document == null) {
            return null;
        }
        int start = Math.min(selectionStart, selectionEnd);
        int end = Math.max(selectionStart, selectionEnd);
        if (start < 0 || end < start || end > document.length()) {
            return null;
        }
        if (start != end) {
            return replacement(document, start, end, "");
        }
        int deleteStart = start == 0
                ? 0
                : start - Character.charCount(document.codePointBefore(start));
        return document.substring(0, deleteStart) + document.substring(end);
    }
}
