package com.tapziq.keyboard;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class TranslationReturnPolicyTest {
    private static final int TYPE_CLASS_TEXT = 1;

    @Test
    public void foreignAndTransitionalEditorsWaitForSourceRestoration() {
        assertEquals(
                TranslationReturnPolicy.Decision.WAIT,
                TranslationReturnPolicy.decide(0, true, false, false, false)
        );
        assertEquals(
                TranslationReturnPolicy.Decision.WAIT,
                TranslationReturnPolicy.decide(
                        TYPE_CLASS_TEXT,
                        false,
                        true,
                        false,
                        false
                )
        );
    }

    @Test
    public void restoredSourceMustMatchItsFieldAndExactTarget() {
        assertEquals(
                TranslationReturnPolicy.Decision.STALE,
                TranslationReturnPolicy.decide(
                        TYPE_CLASS_TEXT,
                        true,
                        true,
                        false,
                        true
                )
        );
        assertEquals(
                TranslationReturnPolicy.Decision.STALE,
                TranslationReturnPolicy.decide(
                        TYPE_CLASS_TEXT,
                        true,
                        true,
                        true,
                        false
                )
        );
        assertEquals(
                TranslationReturnPolicy.Decision.ACCEPT,
                TranslationReturnPolicy.decide(
                        TYPE_CLASS_TEXT,
                        true,
                        true,
                        true,
                        true
                )
        );
    }
}
