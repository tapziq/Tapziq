package com.tapziq.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.inputmethod.ExtractedText;

import org.junit.Test;

public final class AutocorrectEditTest {
    @Test
    public void acceptsOnlyChangedNonblankBoundaryCleanSuggestions() {
        AutocorrectTarget target = target("teh ", " ");

        assertTrue(AutocorrectEdit.validate(target, "the").succeeded());
        assertEquals(
                AutocorrectEdit.Failure.EMPTY,
                AutocorrectEdit.validate(target, " \t").failure
        );
        assertEquals(
                AutocorrectEdit.Failure.UNCHANGED,
                AutocorrectEdit.validate(target, "teh").failure
        );
        assertEquals(
                AutocorrectEdit.Failure.BOUNDARY_WHITESPACE,
                AutocorrectEdit.validate(target, " the").failure
        );
        assertEquals(
                AutocorrectEdit.Failure.BOUNDARY_WHITESPACE,
                AutocorrectEdit.validate(target, "the\u00a0").failure
        );
    }

    @Test
    public void preservesStructuralWhitespaceAndFormattingControls() {
        AutocorrectTarget target = target("teh\tline\u200bhere ", " ");

        assertTrue(AutocorrectEdit.validate(target, "the\tline\u200bhere").succeeded());
        assertEquals(
                AutocorrectEdit.Failure.STRUCTURE_CHANGED,
                AutocorrectEdit.validate(target, "the line\u200bhere").failure
        );
        assertEquals(
                AutocorrectEdit.Failure.STRUCTURE_CHANGED,
                AutocorrectEdit.validate(target, "the\tlinehere").failure
        );
        AutocorrectTarget movedAnchor = target("ab\tcd ", " ");
        assertEquals(
                AutocorrectEdit.Failure.STRUCTURE_CHANGED,
                AutocorrectEdit.validate(movedAnchor, "a\tbcd").failure
        );
    }

    @Test
    public void rejectsRunawayLengthAndMalformedUtf16() {
        AutocorrectTarget target = target("teh ", " ");

        assertEquals(
                AutocorrectEdit.Failure.LENGTH_CHANGE_TOO_LARGE,
                AutocorrectEdit.validate(target, "something").failure
        );
        assertEquals(
                AutocorrectEdit.Failure.INVALID_UNICODE,
                AutocorrectEdit.validate(target, "th\ud800").failure
        );
        assertEquals(2, AutocorrectEdit.lengthAllowance(1));
        assertEquals(24, AutocorrectEdit.lengthAllowance(500));
    }

    @Test
    public void rejectsWholesaleRewritesEvenWhenLengthAndWhitespaceLookSafe() {
        String original = "alpha beta gamma delta epsilon zeta eta theta";
        AutocorrectTarget target = target(original + " ", " ");

        assertEquals(
                AutocorrectEdit.Failure.EDIT_DISTANCE_TOO_LARGE,
                AutocorrectEdit.validate(
                        target,
                        "omega iota kappa lambda omicron mu nu xi rho"
                ).failure
        );
        assertTrue(AutocorrectEdit.validate(target, "alpha beta gamma delta epsilon zeta eta thetha")
                .succeeded());
        assertEquals(3, AutocorrectEdit.editDistanceAllowance(3));
        assertEquals(24, AutocorrectEdit.editDistanceAllowance(500));
    }

    @Test
    public void rejectsAutomaticExpansionPastTheCompleteFieldLimit() {
        String document = "x".repeat(AutocorrectTarget.MAX_SNAPSHOT_CHARACTERS - 6)
                + ",cant ";
        AutocorrectTarget target = target(document, " ");

        assertEquals(
                AutocorrectEdit.Failure.DOCUMENT_TOO_LONG,
                AutocorrectEdit.validate(target, "can't").failure
        );
    }

    @Test
    public void exposesReplacementAndUndoMathWithoutMovingPastBoundary() {
        AutocorrectTarget target = target("Earlier, teh ", " ");
        AutocorrectEdit edit = AutocorrectEdit.validate(target, "there").edit;

        assertEquals(10, edit.start());
        assertEquals(12, edit.end());
        assertEquals(14, edit.appliedEnd());
        assertEquals("eh", edit.original());
        assertEquals("here", edit.suggestion());
        assertEquals("there", edit.correctedText());
        assertEquals(2, edit.replacementCursorPosition());
        assertEquals(15, edit.caretAfter());
        assertEquals("Earlier, there ", edit.resultingDocument());

        assertTrue(edit.matches("Earlier, teh ", 0, 13, 13, true));
        assertTrue(edit.matchesApplied("Earlier, there ", 0, 15, 15, true));
        assertFalse(edit.matchesApplied("Earlier, there ", 0, 14, 14, true));
        assertFalse(edit.matchesApplied("Earlier, there! ", 0, 16, 16, true));
        assertFalse(edit.matchesApplied("Earlier, there ", 0, 15, 15, false));
        assertEquals(2, edit.undoCursorPosition());

        ExtractedText applied = extracted("Earlier, there ", 15, -1);
        assertTrue(edit.matchesApplied(applied));
        applied.partialStartOffset = 0;
        assertFalse(edit.matchesApplied(applied));
    }

    @Test
    public void punctuationReplacementUsesCursorImmediatelyAfterSuggestion() {
        AutocorrectTarget target = target("This are bad.", ".");
        AutocorrectEdit edit = AutocorrectEdit.validate(target, "This is bad.").edit;

        assertEquals(6, edit.replacementCursorPosition());
        assertEquals(edit.appliedEnd() + 5, edit.caretAfter());
        assertEquals(6, edit.undoCursorPosition());
    }

    @Test
    public void midDocumentPunctuationCannotBeRemovedAtTheUntouchedRightSeam() {
        AutocorrectTarget.Capture capture = AutocorrectTarget.capture(
                "hello,world",
                0,
                6,
                6,
                true,
                ","
        );
        assertTrue(capture.succeeded());

        assertEquals(
                AutocorrectEdit.Failure.SEAM_CHANGED,
                AutocorrectEdit.validate(capture.target, "hello").failure
        );
        assertTrue(AutocorrectEdit.validate(capture.target, "Hello,").succeeded());

        AutocorrectTarget.Capture identifier = AutocorrectTarget.capture(
                "hello,_world",
                0,
                6,
                6,
                true,
                ","
        );
        assertTrue(identifier.succeeded());
        assertEquals(
                AutocorrectEdit.Failure.SEAM_CHANGED,
                AutocorrectEdit.validate(identifier.target, "hello").failure
        );

        AutocorrectTarget.Capture formatContinuation = AutocorrectTarget.capture(
                "hello,\u200Cworld",
                0,
                6,
                6,
                true,
                ","
        );
        assertTrue(formatContinuation.succeeded());
        assertEquals(
                AutocorrectEdit.Failure.SEAM_CHANGED,
                AutocorrectEdit.validate(formatContinuation.target, "hello").failure
        );

        AutocorrectTarget.Capture emojiModifier = AutocorrectTarget.capture(
                "\uD83D\uDC4D,\uD83C\uDFFD",
                0,
                3,
                3,
                true,
                ","
        );
        assertTrue(emojiModifier.succeeded());
        assertEquals(
                AutocorrectEdit.Failure.SEAM_CHANGED,
                AutocorrectEdit.validate(emojiModifier.target, "\uD83D\uDC4D").failure
        );
    }

    private static AutocorrectTarget target(String document, String boundary) {
        AutocorrectTarget.Capture capture = AutocorrectTarget.capture(
                document,
                0,
                document.length(),
                document.length(),
                true,
                boundary
        );
        assertTrue(capture.succeeded());
        return capture.target;
    }

    private static ExtractedText extracted(String text, int caret, int partialStartOffset) {
        ExtractedText extracted = new ExtractedText();
        extracted.text = text;
        extracted.startOffset = 0;
        extracted.selectionStart = caret;
        extracted.selectionEnd = caret;
        extracted.partialStartOffset = partialStartOffset;
        extracted.partialEndOffset = partialStartOffset < 0 ? -1 : text.length();
        return extracted;
    }
}
