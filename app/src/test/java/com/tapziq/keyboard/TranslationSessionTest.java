package com.tapziq.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public final class TranslationSessionTest {
    @After
    public void cleanUp() {
        TranslationSession.setListener(null);
        TranslationSession.clear();
    }

    @Test
    public void resultRequiresTheActiveClaimedSessionAndBridgeStop() {
        int first = TranslationSession.begin("first");
        int second = TranslationSession.begin("second");

        TranslationSession.claim(second);
        assertTrue(!TranslationSession.complete(
                first,
                TranslationSession.Result.suggestion("old")
        ));
        assertTrue(TranslationSession.complete(
                second,
                TranslationSession.Result.suggestion("nuevo")
        ));
        assertNull(TranslationSession.peekDeliverableResult(second));

        TranslationSession.notifyResultReady(second);
        assertEquals(
                "nuevo",
                TranslationSession.takeDeliverableResult(second).suggestion
        );
        assertNull(TranslationSession.getPending(second));
    }

    @Test
    public void cancellationRejectsLateCompanionResults() {
        int id = TranslationSession.begin("private text");
        TranslationSession.claim(id);
        TranslationSession.cancel(id);

        assertTrue(!TranslationSession.complete(
                id,
                TranslationSession.Result.suggestion("late")
        ));
        assertNull(TranslationSession.getPending(id));
    }

    @Test
    public void claimedBridgeReplacesTheShortLaunchExpiry() {
        int id = TranslationSession.begin("private text");
        long launchGeneration = TranslationSession.expiryGenerationForTest();

        assertEquals("private text", TranslationSession.claim(id).text);
        TranslationSession.expireForTest(id, launchGeneration);

        assertTrue(TranslationSession.isActive(id));
        assertEquals("private text", TranslationSession.getPending(id).text);
    }

    @Test
    public void currentBridgeExpiryClearsPrivateTextAndSignalsListener() {
        int[] changed = {0};
        TranslationSession.setListener(id -> changed[0] = id);
        int id = TranslationSession.begin("private text");
        TranslationSession.claim(id);
        long bridgeGeneration = TranslationSession.expiryGenerationForTest();

        TranslationSession.expireForTest(id, bridgeGeneration);

        assertNull(TranslationSession.getPending(id));
        assertEquals(id, changed[0]);
    }

    @Test
    public void bridgeRecreationCanReclaimTheSamePendingText() {
        int id = TranslationSession.begin("selected text");

        assertEquals("selected text", TranslationSession.claim(id).text);
        assertEquals("selected text", TranslationSession.claim(id).text);
        assertTrue(TranslationSession.isActive(id));
    }
}
