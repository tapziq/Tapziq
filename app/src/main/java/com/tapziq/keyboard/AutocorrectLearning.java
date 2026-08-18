package com.tapziq.keyboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Extracts one exact word-level preference from a rejected correction. */
final class AutocorrectLearning {
    static final int MAX_TOKEN_CHARACTERS = 64;

    static final class Feedback {
        final String written;
        final String rejected;
        final int sourceStart;
        final int sourceEnd;

        private Feedback(String written, String rejected, int sourceStart, int sourceEnd) {
            this.written = written;
            this.rejected = rejected;
            this.sourceStart = sourceStart;
            this.sourceEnd = sourceEnd;
        }
    }

    private AutocorrectLearning() {
    }

    static Feedback fromEdit(AutocorrectEdit edit) {
        Objects.requireNonNull(edit, "edit");
        return fromDocuments(
                edit.sourceDocument(),
                edit.resultingDocument(),
                edit.start(),
                edit.end(),
                edit.appliedEnd()
        );
    }

    static Feedback fromSuggestion(String writtenText, String suggestedText) {
        if (writtenText == null
                || suggestedText == null
                || writtenText.equals(suggestedText)) {
            return null;
        }
        int prefix = commonPrefixLength(writtenText, suggestedText);
        int[] suffixes = commonSuffixStarts(writtenText, suggestedText, prefix);
        return fromDocuments(
                writtenText,
                suggestedText,
                prefix,
                suffixes[0],
                suffixes[1]
        );
    }

    private static Feedback fromDocuments(
            String writtenDocument,
            String suggestedDocument,
            int writtenChangeStart,
            int writtenChangeEnd,
            int suggestedChangeEnd
    ) {
        if (writtenDocument == null
                || suggestedDocument == null
                || writtenChangeStart < 0
                || writtenChangeEnd < writtenChangeStart
                || writtenChangeEnd > writtenDocument.length()
                || writtenChangeStart > suggestedChangeEnd
                || suggestedChangeEnd > suggestedDocument.length()) {
            return null;
        }
        int[] writtenBounds = tokenBounds(
                writtenDocument,
                writtenChangeStart,
                writtenChangeEnd
        );
        int[] suggestedBounds = tokenBounds(
                suggestedDocument,
                writtenChangeStart,
                suggestedChangeEnd
        );
        if (writtenBounds == null || suggestedBounds == null) {
            return null;
        }
        String written = writtenDocument.substring(writtenBounds[0], writtenBounds[1]);
        String rejected = suggestedDocument.substring(suggestedBounds[0], suggestedBounds[1]);
        if (!isValidToken(written) || !isValidToken(rejected) || written.equals(rejected)) {
            return null;
        }
        return new Feedback(written, rejected, writtenBounds[0], writtenBounds[1]);
    }

    static boolean isValidToken(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_TOKEN_CHARACTERS) {
            return false;
        }
        boolean hasBaseLetterOrDigit = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (!isTokenPart(codePoint)) {
                return false;
            }
            hasBaseLetterOrDigit |= Character.isLetterOrDigit(codePoint);
            offset += Character.charCount(codePoint);
        }
        return hasBaseLetterOrDigit;
    }

    static boolean containsExactToken(String text, String token) {
        if (text == null || !isValidToken(token) || token.length() > text.length()) {
            return false;
        }
        int match = text.indexOf(token);
        while (match >= 0) {
            int end = match + token.length();
            boolean leftBoundary = match == 0
                    || !isTokenPart(text.codePointBefore(match));
            boolean rightBoundary = end == text.length()
                    || !isTokenPart(text.codePointAt(end));
            if (leftBoundary && rightBoundary) {
                return true;
            }
            match = text.indexOf(token, match + 1);
        }
        return false;
    }

    /**
     * Returns whether an exact token substitution participates in a minimum token-level edit
     * alignment between the two documents. Mere presence of both values is deliberately
     * insufficient: an unchanged occurrence of {@code rejected} elsewhere must not make a
     * different correction look like the remembered one.
     */
    static boolean changesToken(
            String sourceDocument,
            String resultingDocument,
            String written,
            String rejected
    ) {
        if (sourceDocument == null
                || resultingDocument == null
                || !isValidToken(written)
                || !isValidToken(rejected)
                || written.equals(rejected)) {
            return false;
        }

        List<String> sourceTokens = tokens(sourceDocument);
        List<String> resultingTokens = tokens(resultingDocument);
        int commonPrefix = 0;
        int sharedLength = Math.min(sourceTokens.size(), resultingTokens.size());
        while (commonPrefix < sharedLength
                && sourceTokens.get(commonPrefix).equals(resultingTokens.get(commonPrefix))) {
            commonPrefix++;
        }

        int sourceEnd = sourceTokens.size();
        int resultingEnd = resultingTokens.size();
        while (sourceEnd > commonPrefix
                && resultingEnd > commonPrefix
                && sourceTokens.get(sourceEnd - 1).equals(resultingTokens.get(resultingEnd - 1))) {
            sourceEnd--;
            resultingEnd--;
        }

        List<String> changedSource = sourceTokens.subList(commonPrefix, sourceEnd);
        List<String> changedResult = resultingTokens.subList(commonPrefix, resultingEnd);
        if (!changedSource.contains(written) || !changedResult.contains(rejected)) {
            return false;
        }

        // With the same number of changed tokens, their ordinal positions are the stable
        // identity. A minimum-edit alignment can slide repeated values across each other and
        // both miss a visible substitution and invent one at a different position.
        if (changedSource.size() == changedResult.size()) {
            for (int index = 0; index < changedSource.size(); index++) {
                if (written.equals(changedSource.get(index))
                        && rejected.equals(changedResult.get(index))) {
                    return true;
                }
            }
            return false;
        }

        int[][] forward = tokenEditPrefixes(changedSource, changedResult);
        int[][] backward = tokenEditSuffixes(changedSource, changedResult);
        int minimumDistance = forward[changedSource.size()][changedResult.size()];
        for (int sourceIndex = 0; sourceIndex < changedSource.size(); sourceIndex++) {
            if (!written.equals(changedSource.get(sourceIndex))) {
                continue;
            }
            for (int resultIndex = 0; resultIndex < changedResult.size(); resultIndex++) {
                if (!rejected.equals(changedResult.get(resultIndex))) {
                    continue;
                }
                int distanceThroughSubstitution = forward[sourceIndex][resultIndex]
                        + 1
                        + backward[sourceIndex + 1][resultIndex + 1];
                if (distanceThroughSubstitution == minimumDistance) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int[] tokenBounds(String value, int changeStart, int changeEnd) {
        int start = changeStart;
        int end = changeEnd;
        while (start > 0 && isTokenPart(value.codePointBefore(start))) {
            start -= Character.charCount(value.codePointBefore(start));
        }
        while (end < value.length() && isTokenPart(value.codePointAt(end))) {
            end += Character.charCount(value.codePointAt(end));
        }
        if (start == end) {
            return null;
        }
        return new int[]{start, end};
    }

    private static boolean isTokenPart(int codePoint) {
        return AutocorrectTarget.isWordPart(codePoint)
                || codePoint == '\''
                || codePoint == 0x2019
                || Character.getType(codePoint) == Character.DASH_PUNCTUATION;
    }

    private static List<String> tokens(String document) {
        List<String> result = new ArrayList<>();
        int offset = 0;
        while (offset < document.length()) {
            int codePoint = document.codePointAt(offset);
            if (!isTokenPart(codePoint)) {
                offset += Character.charCount(codePoint);
                continue;
            }
            int start = offset;
            do {
                offset += Character.charCount(codePoint);
                if (offset >= document.length()) {
                    break;
                }
                codePoint = document.codePointAt(offset);
            } while (isTokenPart(codePoint));
            String token = document.substring(start, offset);
            if (isValidToken(token)) {
                result.add(token);
            }
        }
        return result;
    }

    private static int[][] tokenEditPrefixes(List<String> source, List<String> result) {
        int[][] distances = new int[source.size() + 1][result.size() + 1];
        for (int sourceCount = 0; sourceCount <= source.size(); sourceCount++) {
            distances[sourceCount][0] = sourceCount;
        }
        for (int resultCount = 0; resultCount <= result.size(); resultCount++) {
            distances[0][resultCount] = resultCount;
        }
        for (int sourceCount = 1; sourceCount <= source.size(); sourceCount++) {
            for (int resultCount = 1; resultCount <= result.size(); resultCount++) {
                int substitutionCost = source.get(sourceCount - 1)
                        .equals(result.get(resultCount - 1)) ? 0 : 1;
                distances[sourceCount][resultCount] = Math.min(
                        Math.min(
                                distances[sourceCount - 1][resultCount] + 1,
                                distances[sourceCount][resultCount - 1] + 1
                        ),
                        distances[sourceCount - 1][resultCount - 1] + substitutionCost
                );
            }
        }
        return distances;
    }

    private static int[][] tokenEditSuffixes(List<String> source, List<String> result) {
        int[][] distances = new int[source.size() + 1][result.size() + 1];
        for (int sourceIndex = 0; sourceIndex <= source.size(); sourceIndex++) {
            distances[sourceIndex][result.size()] = source.size() - sourceIndex;
        }
        for (int resultIndex = 0; resultIndex <= result.size(); resultIndex++) {
            distances[source.size()][resultIndex] = result.size() - resultIndex;
        }
        for (int sourceIndex = source.size() - 1; sourceIndex >= 0; sourceIndex--) {
            for (int resultIndex = result.size() - 1; resultIndex >= 0; resultIndex--) {
                int substitutionCost = source.get(sourceIndex).equals(result.get(resultIndex))
                        ? 0
                        : 1;
                distances[sourceIndex][resultIndex] = Math.min(
                        Math.min(
                                distances[sourceIndex + 1][resultIndex] + 1,
                                distances[sourceIndex][resultIndex + 1] + 1
                        ),
                        distances[sourceIndex + 1][resultIndex + 1] + substitutionCost
                );
            }
        }
        return distances;
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
}
