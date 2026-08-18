package com.tapziq.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TranslationTargetTest {
    @Test
    public void capturesOnlyTheHighlightedPassage() {
        TranslationTarget.Capture capture = TranslationTarget.fromExtracted(
                "Keep  hello world.  Keep",
                0,
                6,
                18,
                true
        );

        assertTrue(capture.succeeded());
        assertEquals("hello world.", capture.target.text);
        assertEquals(6, capture.target.start());
        assertEquals(18, capture.target.end());
    }

    @Test
    public void reversedSelectionAndOuterWhitespacePreserveDocumentBounds() {
        TranslationTarget.Capture capture = TranslationTarget.fromExtracted(
                "Before   hello world  After",
                0,
                22,
                6,
                true
        );

        assertTrue(capture.succeeded());
        assertEquals("hello world", capture.target.text);
        assertEquals(9, capture.target.start());
        assertEquals(20, capture.target.end());
    }

    @Test
    public void collapsedSelectionNeverFallsBackToTheWholeField() {
        TranslationTarget.Capture capture = TranslationTarget.fromExtracted(
                "translate all of this",
                0,
                5,
                5,
                true
        );

        assertEquals(TranslationTarget.Failure.NO_SELECTION, capture.failure);
        assertNull(capture.target);
    }

    @Test
    public void partialBlankAndOversizedSelectionsFailClosed() {
        assertEquals(
                TranslationTarget.Failure.INVALID_SELECTION,
                TranslationTarget.fromExtracted("partial", 8, 0, 7, false).failure
        );
        assertEquals(
                TranslationTarget.Failure.NO_TEXT,
                TranslationTarget.fromExtracted("   ", 0, 0, 3, true).failure
        );
        String tooLong = "a".repeat(ProofreadTarget.MAX_CHARACTERS + 1);
        assertEquals(
                TranslationTarget.Failure.TOO_LONG,
                TranslationTarget.fromExtracted(
                        tooLong,
                        0,
                        0,
                        tooLong.length(),
                        true
                ).failure
        );
    }

    @Test
    public void returnedTranslationIsTrimmedAndBounded() {
        assertEquals("Hola\n mundo", TranslationTarget.normalizeResult(" \nHola\n mundo\t"));
        assertNull(TranslationTarget.normalizeResult(" \n\t"));
        assertNull(TranslationTarget.normalizeResult(
                "x".repeat(TranslationTarget.MAX_RESULT_CHARACTERS + 1)
        ));
    }
}
