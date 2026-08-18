package com.tapziq.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AutocorrectLearningTest {
    @Test
    public void extractsWholeWordFromMinimalCorrectionDiff() {
        AutocorrectEdit edit = edit("Before teh word ", "Before the word");

        AutocorrectLearning.Feedback feedback = AutocorrectLearning.fromEdit(edit);

        assertEquals("teh", feedback.written);
        assertEquals("the", feedback.rejected);
        assertEquals(7, feedback.sourceStart);
        assertEquals(10, feedback.sourceEnd);
    }

    @Test
    public void manualSuggestionMustContainOneWordLevelChange() {
        AutocorrectLearning.Feedback feedback = AutocorrectLearning.fromSuggestion(
                "Keep teh wording",
                "Keep the wording"
        );

        assertEquals("teh", feedback.written);
        assertEquals("the", feedback.rejected);
        assertEquals(5, feedback.sourceStart);
        assertEquals(8, feedback.sourceEnd);
        assertNull(AutocorrectLearning.fromSuggestion("this are bad", "that is good"));
        assertNull(AutocorrectLearning.fromSuggestion("unchanged", "unchanged"));
    }

    @Test
    public void tokenMatchingIsExactCaseSensitiveAndBoundaryAware() {
        assertTrue(AutocorrectLearning.containsExactToken("Use teh here", "teh"));
        assertTrue(AutocorrectLearning.containsExactToken("rock’n-roll", "rock’n-roll"));
        assertTrue(AutocorrectLearning.isValidToken("l'esprit"));
        assertTrue(AutocorrectLearning.isValidToken("cafe\u0301-au-lait"));
        assertFalse(AutocorrectLearning.containsExactToken("other", "the"));
        assertFalse(AutocorrectLearning.containsExactToken("Teh", "teh"));
        assertFalse(AutocorrectLearning.isValidToken("---"));
        assertFalse(AutocorrectLearning.isValidToken("'''"));
        assertFalse(AutocorrectLearning.isValidToken("___"));
        assertFalse(AutocorrectLearning.isValidToken("\u0301"));
        assertFalse(AutocorrectLearning.isValidToken("-\u0301-"));
        assertTrue(AutocorrectLearning.isValidToken("e\u0301"));
        assertFalse(AutocorrectLearning.isValidToken("two words"));
        assertFalse(AutocorrectLearning.isValidToken("x".repeat(65)));
    }

    @Test
    public void tokenAlignmentDistinguishesChangedAndUnchangedOccurrences() {
        assertTrue(AutocorrectLearning.changesToken(
                "Keep teh cats nearby",
                "Keep the cat nearby",
                "teh",
                "the"
        ));
        assertFalse(AutocorrectLearning.changesToken(
                "Keep teh and the nearby",
                "Keep tech and the nearby",
                "teh",
                "the"
        ));
        assertTrue(AutocorrectLearning.changesToken(
                "w r w",
                "r w r",
                "w",
                "r"
        ));
        assertFalse(AutocorrectLearning.changesToken(
                "w x w",
                "x r x",
                "w",
                "r"
        ));
    }

    private static AutocorrectEdit edit(String document, String correctedTarget) {
        AutocorrectTarget target = AutocorrectTarget.capture(
                document,
                0,
                document.length(),
                document.length(),
                true,
                " "
        ).target;
        return AutocorrectEdit.validate(target, correctedTarget).edit;
    }
}
