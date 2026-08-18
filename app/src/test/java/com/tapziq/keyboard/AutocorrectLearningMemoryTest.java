package com.tapziq.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AutocorrectLearningMemoryTest {
    @Test
    public void rejectionSurvivesRoundTripAndBlocksOnlyTheExactMapping() {
        AutocorrectLearning.Feedback feedback = feedback("teh", "the");
        AutocorrectLearningMemory memory = new AutocorrectLearningMemory()
                .recordRejection(feedback);

        AutocorrectLearningMemory restored = AutocorrectLearningMemory.fromJson(memory.toJson());

        assertEquals(1, restored.size());
        assertEquals("teh", restored.relevantTo("Keep teh please").get(0).preferred);
        assertTrue(restored.rejects(edit("teh ", "the")));
        assertFalse(restored.rejects(edit("teh ", "tech")));
        assertTrue(restored.relevantTo("other words").isEmpty());
    }

    @Test
    public void rememberedMappingBlocksOnePartOfACompoundCorrection() {
        AutocorrectLearning.Feedback feedback = feedback("teh", "the");
        AutocorrectLearningMemory memory = new AutocorrectLearningMemory()
                .recordRejection(feedback)
                .recordReplacement(feedback, "tha");

        assertTrue(memory.rejects(edit("Keep teh cats ", "Keep the cat")));
        assertTrue(memory.rejects(edit("Keep tha cats ", "Keep the cat")));
    }

    @Test
    public void unchangedRejectedWordElsewhereDoesNotBlockDifferentCompoundCorrection() {
        AutocorrectLearningMemory memory = new AutocorrectLearningMemory()
                .recordRejection(feedback("teh", "the"));

        assertFalse(memory.rejects(edit(
                "Keep teh and the cats ",
                "Keep tech and the cat"
        )));
    }

    @Test
    public void repeatedTokensUseStableOrdinalsForExactSuppression() {
        AutocorrectLearningMemory memory = new AutocorrectLearningMemory()
                .recordRejection(feedback("w", "r"));

        assertTrue(memory.rejects(edit("w r w ", "r w r")));
        assertFalse(memory.rejects(edit("w x w ", "x r x")));
    }

    @Test
    public void typedReplacementBecomesThePreferenceAndAcceptanceForgetsRejection() {
        AutocorrectLearning.Feedback feedback = feedback("teh", "the");
        AutocorrectLearningMemory memory = new AutocorrectLearningMemory()
                .recordRejection(feedback)
                .recordReplacement(feedback, "tech");

        AutocorrectLearningMemory.Entry entry = memory.relevantTo("Use tech here").get(0);
        assertEquals("teh", entry.written);
        assertEquals("the", entry.rejected);
        assertEquals("tech", entry.preferred);
        assertEquals(0, memory.forget(feedback).size());
    }

    @Test
    public void newestHundredValidEntriesAreRetained() {
        AutocorrectLearningMemory memory = new AutocorrectLearningMemory();
        for (int index = 0; index < 105; index++) {
            memory = memory.recordRejection(feedback("word" + index, "edit" + index));
        }

        assertEquals(AutocorrectLearningMemory.MAX_ENTRIES, memory.size());
        assertEquals("word104", memory.entries().get(0).written);
        assertTrue(memory.relevantTo("word0").isEmpty());
        assertFalse(memory.relevantTo("word5").isEmpty());

        AutocorrectLearningMemory restored = AutocorrectLearningMemory.fromJson(memory.toJson());
        assertEquals("word104", restored.entries().get(0).written);
        assertEquals("word5", restored.entries().get(99).written);
    }

    @Test
    public void malformedOrOversizedRecordsFailClosedWithoutPoisoningValidOnes() {
        assertEquals(0, AutocorrectLearningMemory.fromJson("not json").size());
        assertEquals(0, AutocorrectLearningMemory.fromJson(
                "{\"version\":\"1\",\"entries\":[{\"written\":\"teh\","
                        + "\"rejected\":\"the\",\"preferred\":\"teh\"}]}"
        ).size());
        String oversized = "x".repeat(AutocorrectLearning.MAX_TOKEN_CHARACTERS + 1);
        String mixed = "{\"version\":1,\"entries\":["
                + "{\"written\":3},"
                + "{\"written\":\"teh\",\"rejected\":\"the\","
                + "\"preferred\":\"the\"},"
                + "{\"written\":\"" + oversized + "\",\"rejected\":\"edit\","
                + "\"preferred\":\"word\"},"
                + "{\"written\":\"teh\",\"rejected\":\"the\","
                + "\"preferred\":\"teh\"}]}";
        assertEquals(1, AutocorrectLearningMemory.fromJson(mixed).size());
    }

    private static AutocorrectLearning.Feedback feedback(String written, String rejected) {
        return AutocorrectLearning.fromSuggestion(written, rejected);
    }

    private static AutocorrectEdit edit(String document, String suggestion) {
        AutocorrectTarget target = AutocorrectTarget.capture(
                document,
                0,
                document.length(),
                document.length(),
                true,
                " "
        ).target;
        return AutocorrectEdit.validate(target, suggestion).edit;
    }
}
