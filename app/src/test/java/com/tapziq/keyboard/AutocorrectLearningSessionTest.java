package com.tapziq.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AutocorrectLearningSessionTest {
    private static final String BASELINE = "Before teh after";

    @Test
    public void multiKeySameWordReplacementLearnsOnlyWhenFinalized() {
        AutocorrectLearningSession session = session();

        assertEquals(
                AutocorrectLearningSession.Outcome.ACTIVE,
                session.observe("Before  after", 7, 7, false, false).outcome
        );
        assertEquals(
                AutocorrectLearningSession.Outcome.ACTIVE,
                session.observe("Before t after", 8, 8, false, false).outcome
        );
        AutocorrectLearningSession.Observation completed = session.observe(
                "Before tech after",
                11,
                11,
                true,
                false
        );
        assertEquals(AutocorrectLearningSession.Outcome.REPLACEMENT, completed.outcome);
        assertEquals("tech", completed.replacement);
    }

    @Test
    public void tappingInsideKeepsSessionAndTappingAwayFinishesIt() {
        assertEquals(
                AutocorrectLearningSession.Outcome.ACTIVE,
                session().observe(BASELINE, 7, 10, true, true).outcome
        );
        assertEquals(
                AutocorrectLearningSession.Outcome.COMPLETE,
                session().observe(BASELINE, 0, 0, true, true).outcome
        );
        assertEquals(
                AutocorrectLearningSession.Outcome.COMPLETE,
                session().observe(BASELINE, 10, 10, true, true).outcome
        );
        assertEquals(
                AutocorrectLearningSession.Outcome.REPLACEMENT,
                session().observe("Before tech after", 0, 0, true, true).outcome
        );
    }

    @Test
    public void unrelatedOrMultiwordEditsNeverBecomePreferences() {
        assertEquals(
                AutocorrectLearningSession.Outcome.INVALID,
                session().observe("Changed teh after", 7, 7, false, false).outcome
        );
        assertEquals(
                AutocorrectLearningSession.Outcome.COMPLETE,
                session().observe("Before two words after", 16, 16, true, false).outcome
        );
    }

    @Test
    public void independentlyTypingRejectedCandidateCancelsTheRejection() {
        assertEquals(
                AutocorrectLearningSession.Outcome.REJECTED_ACCEPTED,
                session().observe("Before the after", 10, 10, true, false).outcome
        );
    }

    @Test
    public void tappedAppliedCorrectionTracksTheWordThatGemmaInserted() {
        AutocorrectLearning.Feedback feedback = AutocorrectLearning.fromSuggestion("teh", "the");
        AutocorrectLearningSession applied = AutocorrectLearningSession.begin(
                "Before the after",
                7,
                10,
                feedback,
                true
        );

        assertEquals(
                AutocorrectLearningSession.Outcome.ACTIVE,
                applied.observe("Before the after", 8, 8, true, true).outcome
        );
        AutocorrectLearningSession.Observation replacement = applied.observe(
                "Before tech after",
                11,
                11,
                true,
                false
        );
        assertEquals(AutocorrectLearningSession.Outcome.REPLACEMENT, replacement.outcome);
        assertEquals("tech", replacement.replacement);
    }

    @Test
    public void tappedReviewRecordsOnTapAwayAndKeepsWaitingForReplacement() {
        AutocorrectLearningSession.Decision decision = AutocorrectLearningSession.decide(
                AutocorrectLearningSession.Observation.of(
                        AutocorrectLearningSession.Outcome.COMPLETE
                ),
                true,
                false,
                false
        );

        assertTrue(decision.recordRejection);
        assertTrue(decision.keepSession);
        assertFalse(decision.recordReplacement);
        assertFalse(decision.forgetRejection);
    }

    @Test
    public void directTypedReplacementRecordsBothRejectionAndPreference() {
        AutocorrectLearningSession.Decision decision = AutocorrectLearningSession.decide(
                AutocorrectLearningSession.Observation.replacement("tech"),
                true,
                false,
                true
        );

        assertTrue(decision.recordRejection);
        assertTrue(decision.recordReplacement);
        assertFalse(decision.keepSession);
        assertFalse(decision.forgetRejection);
    }

    @Test
    public void externalSuggestionChoiceIsNeverLearnedAsTypedFeedback() {
        AutocorrectLearningSession.Decision decision = AutocorrectLearningSession.decide(
                AutocorrectLearningSession.Observation.replacement("tech"),
                true,
                false,
                false
        );

        assertFalse(decision.recordRejection);
        assertFalse(decision.recordReplacement);
        assertFalse(decision.keepSession);
        assertFalse(decision.forgetRejection);
    }

    private static AutocorrectLearningSession session() {
        AutocorrectLearning.Feedback feedback = AutocorrectLearning.fromSuggestion("teh", "the");
        return AutocorrectLearningSession.begin(BASELINE, 7, 10, feedback);
    }
}
