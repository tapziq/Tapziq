package com.tapziq.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.text.Spanned;
import android.view.inputmethod.ExtractedText;

import org.junit.Test;

import java.lang.reflect.Array;

public final class AutocorrectTargetTest {
    @Test
    public void capturesCurrentCompletedClauseAndLeavesBoundaryOutsideRange() {
        String document = "Earlier sentence.  this are teh problem ";

        AutocorrectTarget.Capture capture = capture(document, " ");

        assertTrue(capture.succeeded());
        assertEquals("this are teh problem", capture.target.text());
        assertEquals(document.indexOf("this"), capture.target.start());
        assertEquals(document.length() - 1, capture.target.end());
        assertEquals(document, capture.target.documentText());
    }

    @Test
    public void punctuationBoundaryRemainsPartOfCompletedSentence() {
        String document = "First sentence. This are bad.";

        AutocorrectTarget.Capture capture = capture(document, ".");

        assertTrue(capture.succeeded());
        assertEquals("This are bad.", capture.target.text());
        assertEquals(document.indexOf("This"), capture.target.start());
        assertEquals(document.length(), capture.target.end());
    }

    @Test
    public void selectsTextAfterMostRecentClauseDelimiter() {
        assertEquals(
                "teh item",
                capture("Intro phrase, teh item ", " ").target.text()
        );
        assertEquals(
                "this are bad",
                capture("First sentence.\"  this are bad ", " ").target.text()
        );
        assertEquals(
                "Version 1.2 is teh value",
                capture("Version 1.2 is teh value ", " ").target.text()
        );
    }

    @Test
    public void longClauseUsesRecentWordAlignedSuffixWithinModelLimit() {
        StringBuilder clause = new StringBuilder();
        for (int index = 0; index < 120; index++) {
            clause.append("token").append(index).append(' ');
        }
        String document = clause + " ";

        AutocorrectTarget target = capture(document, " ").target;

        assertTrue(target.start() > 0);
        assertTrue(target.text().length() <= AutocorrectTarget.MAX_CHARACTERS);
        assertTrue(Character.isWhitespace(document.charAt(target.start() - 1)));
        assertEquals(
                document.substring(target.start(), target.end()),
                target.text()
        );
    }

    @Test
    public void unbrokenOversizedTokenFailsInsteadOfSplittingAWord() {
        String document = "a".repeat(AutocorrectTarget.MAX_CHARACTERS + 1) + " ";

        assertEquals(
                AutocorrectTarget.Failure.TARGET_TOO_LONG,
                capture(document, " ").failure
        );
    }

    @Test
    public void longClauseKeepsLeftSeamPunctuationOutsideModelControl() {
        String document = "abcd\u2014" + "z".repeat(499) + " ";

        AutocorrectTarget target = capture(document, " ").target;

        assertEquals(5, target.start());
        assertEquals('\u2014', document.charAt(target.start() - 1));
        assertEquals("z".repeat(499), target.text());
    }

    @Test
    public void incompleteMovedOrSelectedSnapshotsFailClosed() {
        assertEquals(
                AutocorrectTarget.Failure.INVALID_SNAPSHOT,
                AutocorrectTarget.capture("teh ", 1, 4, 4, true, " ").failure
        );
        assertEquals(
                AutocorrectTarget.Failure.INVALID_SNAPSHOT,
                AutocorrectTarget.capture("teh ", 0, 4, 4, false, " ").failure
        );
        assertEquals(
                AutocorrectTarget.Failure.INVALID_SNAPSHOT,
                AutocorrectTarget.capture("teh ", 0, 0, 3, true, " ").failure
        );
        assertEquals(
                AutocorrectTarget.Failure.NOT_AT_BOUNDARY,
                AutocorrectTarget.capture("teh ", 0, 3, 3, true, " ").failure
        );
        assertEquals(
                AutocorrectTarget.Failure.NOT_AT_BOUNDARY,
                AutocorrectTarget.capture("teh ", 0, 4, 4, true, "x").failure
        );
        String oversized = "a".repeat(AutocorrectTarget.MAX_SNAPSHOT_CHARACTERS) + " ";
        assertEquals(
                AutocorrectTarget.Failure.INVALID_SNAPSHOT,
                capture(oversized, " ").failure
        );
    }

    @Test
    public void asyncResultRequiresExactDocumentSelectionAndCompleteSnapshot() {
        AutocorrectTarget target = capture("teh ", " ").target;

        assertTrue(target.matches("teh ", 0, 4, 4, true));
        assertFalse(target.matches("the ", 0, 4, 4, true));
        assertFalse(target.matches("teh  ", 0, 4, 4, true));
        assertFalse(target.matches("teh ", 0, 3, 3, true));
        assertFalse(target.matches("teh ", 0, 4, 4, false));
        assertFalse(target.matches("teh ", 1, 4, 4, true));

        ExtractedText partial = extracted("teh ", 4, 4, 0);
        assertFalse(target.matches(partial));
        ExtractedText complete = extracted("teh ", 4, 4, -1);
        assertTrue(target.matches(complete));
    }

    @Test
    public void supportedBoundariesAreNarrowAndExplicit() {
        assertTrue(AutocorrectTarget.isSupportedBoundary(" "));
        assertTrue(AutocorrectTarget.isSupportedBoundary(". "));
        assertTrue(AutocorrectTarget.isSupportedBoundary("\u2026"));
        assertFalse(AutocorrectTarget.isSupportedBoundary("'"));
        assertFalse(AutocorrectTarget.isSupportedBoundary("a"));
        assertFalse(AutocorrectTarget.isSupportedBoundary(""));
    }

    @Test
    public void richTextSnapshotsFailClosedBeforeAndAfterInference() {
        FakeSpanned styled = new FakeSpanned("teh ", 0, 3);
        assertEquals(
                AutocorrectTarget.Failure.INVALID_SNAPSHOT,
                AutocorrectTarget.capture(styled, 0, 4, 4, true, " ").failure
        );

        AutocorrectTarget target = capture("teh ", " ").target;
        assertFalse(target.matches(styled, 0, 4, 4, true));

        AutocorrectEdit edit = AutocorrectEdit.validate(target, "the").edit;
        FakeSpanned styledResult = new FakeSpanned("the ", 0, 3);
        assertFalse(edit.matchesApplied(styledResult, 0, 4, 4, true));
    }

    private static AutocorrectTarget.Capture capture(String document, String boundary) {
        return AutocorrectTarget.capture(
                document,
                0,
                document.length(),
                document.length(),
                true,
                boundary
        );
    }

    private static ExtractedText extracted(
            String text,
            int selectionStart,
            int selectionEnd,
            int partialStartOffset
    ) {
        ExtractedText extracted = new ExtractedText();
        extracted.text = text;
        extracted.startOffset = 0;
        extracted.selectionStart = selectionStart;
        extracted.selectionEnd = selectionEnd;
        extracted.partialStartOffset = partialStartOffset;
        extracted.partialEndOffset = partialStartOffset < 0 ? -1 : text.length();
        return extracted;
    }

    private static final class FakeSpanned implements Spanned {
        private final String text;
        private final Object span = new Object();
        private final int spanStart;
        private final int spanEnd;

        FakeSpanned(String text, int spanStart, int spanEnd) {
            this.text = text;
            this.spanStart = spanStart;
            this.spanEnd = spanEnd;
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public char charAt(int index) {
            return text.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return text.subSequence(start, end);
        }

        @Override
        public String toString() {
            return text;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T[] getSpans(int start, int end, Class<T> type) {
            if (!type.isInstance(span) || spanStart >= end || spanEnd <= start) {
                return (T[]) Array.newInstance(type, 0);
            }
            T[] values = (T[]) Array.newInstance(type, 1);
            values[0] = type.cast(span);
            return values;
        }

        @Override
        public int getSpanStart(Object query) {
            return query == span ? spanStart : -1;
        }

        @Override
        public int getSpanEnd(Object query) {
            return query == span ? spanEnd : -1;
        }

        @Override
        public int getSpanFlags(Object query) {
            return 0;
        }

        @Override
        public int nextSpanTransition(int start, int limit, Class type) {
            if (start < spanStart) {
                return Math.min(spanStart, limit);
            }
            if (start < spanEnd) {
                return Math.min(spanEnd, limit);
            }
            return limit;
        }
    }
}
