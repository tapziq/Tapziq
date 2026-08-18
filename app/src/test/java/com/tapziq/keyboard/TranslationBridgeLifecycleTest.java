package com.tapziq.keyboard;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TranslationBridgeLifecycleTest {
    @Test
    public void stoppedParentReclamationDoesNotCancelTheChildRequest() {
        assertFalse(TranslationBridgeLifecycle.shouldCancelOnDestroy(
                false,
                false,
                false
        ));
    }

    @Test
    public void configurationChangeAndHandledResultDoNotCancelAgain() {
        assertFalse(TranslationBridgeLifecycle.shouldCancelOnDestroy(false, false, true));
        assertFalse(TranslationBridgeLifecycle.shouldCancelOnDestroy(true, true, false));
    }

    @Test
    public void explicitFinishBeforeAResultCancelsTheRequest() {
        assertTrue(TranslationBridgeLifecycle.shouldCancelOnDestroy(false, true, false));
    }
}
