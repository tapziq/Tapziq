package com.tapziq.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AutocorrectLearningStoreTest {
    @Test
    public void canonicalizationDropsMalformedFutureAndOversizedData() {
        assertNull(AutocorrectLearningStore.canonicalSerialized("not-json"));
        assertNull(AutocorrectLearningStore.canonicalSerialized(
                "{\"version\":2,\"entries\":[]}"
        ));
        assertNull(AutocorrectLearningStore.canonicalSerialized("x".repeat(64 * 1024 + 1)));
        assertNull(AutocorrectLearningStore.canonicalSerialized(
                "{\"version\":1,\"entries\":[]}"
        ));
    }

    @Test
    public void canonicalizationKeepsOnlyStrictValidEntries() {
        AutocorrectLearning.Feedback feedback = AutocorrectLearning.fromSuggestion("teh", "the");
        String expected = new AutocorrectLearningMemory()
                .recordReplacement(feedback, "tech")
                .toJson();
        String mixed = "{\"version\":1,\"entries\":["
                + "{\"written\":\"teh\",\"rejected\":\"the\",\"preferred\":\"tech\"},"
                + "7]}";

        assertEquals(expected, AutocorrectLearningStore.canonicalSerialized(mixed));
        assertEquals(expected, AutocorrectLearningStore.canonicalSerialized(expected));
    }

    @Test
    public void apostropheHeavyValidMemoryCannotExpandPastTheStoreLimit() {
        AutocorrectLearningMemory memory = new AutocorrectLearningMemory();
        String punctuation = "'".repeat(54);
        for (int index = 0; index < AutocorrectLearningMemory.MAX_ENTRIES; index++) {
            AutocorrectLearning.Feedback feedback = AutocorrectLearning.fromSuggestion(
                    "w" + index + punctuation,
                    "r" + index + punctuation
            );
            memory = memory.recordReplacement(feedback, "p" + index + punctuation);
        }

        String canonical = AutocorrectLearningStore.canonicalSerialized(memory.toJson());
        assertTrue(canonical != null && canonical.length() <= 64 * 1024);
    }
}
