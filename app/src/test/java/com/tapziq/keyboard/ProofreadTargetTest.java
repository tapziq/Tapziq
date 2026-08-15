package com.tapziq.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.inputmethod.ExtractedText;

import org.junit.Test;

public final class ProofreadTargetTest {
    @Test
    public void selectionIsPreferredAndWhitespaceOutsideItIsPreserved() {
        ProofreadTarget.Capture capture = ProofreadTarget.fromExtracted(
                "Before  this are bad.  After",
                0,
                8,
                21,
                true
        );

        assertTrue(capture.succeeded());
        assertEquals("this are bad.", capture.target.text);
        assertEquals(8, capture.target.start());
        assertEquals(21, capture.target.end());
    }

    @Test
    public void collapsedSelectionUsesTrimmedFullField() {
        ProofreadTarget.Capture capture = ProofreadTarget.fromExtracted(
                "  this are bad  ",
                0,
                6,
                6,
                true
        );

        assertTrue(capture.succeeded());
        assertEquals("this are bad", capture.target.text);
        assertEquals(2, capture.target.start());
        assertEquals(14, capture.target.end());
    }

    @Test
    public void textMustStillMatchBeforeReplacement() {
        ProofreadTarget target = ProofreadTarget.fromExtracted(
                "hello wrld",
                0,
                0,
                10,
                true
        ).target;

        assertTrue(target.matches(extracted("hello wrld", 0, 0, 10, -1)));
        assertTrue(!target.matches(extracted("hello world", 0, 0, 10, -1)));
        assertTrue(!target.matches(extracted("hello wrld", 101, 0, 10, -1)));
    }

    @Test
    public void oversizedAndBlankInputsFailClosed() {
        String tooLong = "a".repeat(ProofreadTarget.MAX_CHARACTERS + 1);
        assertEquals(
                ProofreadTarget.Failure.TOO_LONG,
                ProofreadTarget.fromExtracted(tooLong, 0, 0, 0, true).failure
        );
        assertEquals(
                ProofreadTarget.Failure.NO_TEXT,
                ProofreadTarget.fromExtracted("   ", 0, 0, 0, true).failure
        );
        assertEquals(
                ProofreadTarget.Failure.INVALID_SELECTION,
                ProofreadTarget.fromExtracted("text", -1, 0, 4, true).failure
        );
        assertEquals(
                ProofreadTarget.Failure.INVALID_SELECTION,
                ProofreadTarget.fromExtracted("partial", 10, 3, 3, false).failure
        );
    }

    @Test
    public void changedSelectionFailsEvenWhenTargetTextStillMatches() {
        ProofreadTarget target = ProofreadTarget.fromExtracted(
                "bad bad",
                0,
                4,
                7,
                true
        ).target;

        assertTrue(target.matches(extracted("bad bad", 0, 4, 7, -1)));
        assertTrue(!target.matches(extracted("bad bad", 0, 0, 3, -1)));
        assertTrue(!target.matches(extracted("bad bad bad", 0, 4, 7, -1)));
    }

    @Test
    public void fullFieldRequiresSameCompleteSnapshotAndCursor() {
        ProofreadTarget target = ProofreadTarget.fromExtracted(
                "this are bad",
                0,
                4,
                4,
                true
        ).target;

        assertTrue(target.matches(extracted("this are bad", 0, 4, 4, -1)));
        assertTrue(!target.matches(extracted("this are bad", 0, 5, 5, -1)));
        assertTrue(!target.matches(extracted("this are bad now", 0, 4, 4, -1)));
        assertTrue(!target.matches(extracted("this are bad", 0, 4, 4, 0)));
    }

    @Test
    public void modelBoundaryWhitespaceIsRemovedWithoutChangingInternalText() {
        assertEquals(
                "This sentence has  two spaces.",
                ProofreadTarget.normalizeSuggestion(" \nThis sentence has  two spaces.\t")
        );
        assertEquals("", ProofreadTarget.normalizeSuggestion(" \n\t"));
    }

    private static ExtractedText extracted(
            String text,
            int startOffset,
            int selectionStart,
            int selectionEnd,
            int partialStartOffset
    ) {
        ExtractedText extracted = new ExtractedText();
        extracted.text = text;
        extracted.startOffset = startOffset;
        extracted.selectionStart = selectionStart;
        extracted.selectionEnd = selectionEnd;
        extracted.partialStartOffset = partialStartOffset;
        extracted.partialEndOffset = text.length();
        return extracted;
    }

}
