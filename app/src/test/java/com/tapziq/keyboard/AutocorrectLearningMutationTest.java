package com.tapziq.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class AutocorrectLearningMutationTest {
    @Test
    public void replacementUsesTheExactPossiblyReversedSelection() {
        assertEquals(
                "Before tech after",
                AutocorrectLearningMutation.replacement(
                        "Before the after",
                        10,
                        7,
                        "tech"
                )
        );
        assertNull(AutocorrectLearningMutation.replacement("word", -1, 0, "x"));
    }

    @Test
    public void deleteRemovesASelectionOrOneWholeCodePoint() {
        assertEquals(
                "Before  after",
                AutocorrectLearningMutation.deleteBeforeCursor("Before the after", 7, 10)
        );
        assertEquals(
                "ab",
                AutocorrectLearningMutation.deleteBeforeCursor("a😀b", 3, 3)
        );
        assertEquals(
                "word",
                AutocorrectLearningMutation.deleteBeforeCursor("word", 0, 0)
        );
    }
}
