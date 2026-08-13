package com.tapziq.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public final class ProofreadSessionTest {
    @After
    public void cleanUp() {
        ProofreadSession.clear();
    }

    @Test
    public void resultIsDeliveredOnlyToItsActiveSession() {
        int first = ProofreadSession.begin("first");
        int second = ProofreadSession.begin("second");

        ProofreadSession.claim(second);
        ProofreadSession.complete(first, ProofreadSession.Result.suggestion("old"));
        ProofreadSession.complete(second, ProofreadSession.Result.suggestion("new"));
        ProofreadSession.notifyResultReady(second);

        assertNull(ProofreadSession.takeResult(first));
        ProofreadSession.Result result = ProofreadSession.takeResult(second);
        assertEquals("new", result.suggestion);
        assertNull(ProofreadSession.getPending(second));
    }

    @Test
    public void cancellationRejectsLateActivityCallbacks() {
        int id = ProofreadSession.begin("text");
        ProofreadSession.claim(id);
        ProofreadSession.cancel(id);
        ProofreadSession.complete(id, ProofreadSession.Result.suggestion("late"));

        assertNull(ProofreadSession.getPending(id));
        assertNull(ProofreadSession.takeResult(id));
    }

    @Test
    public void claimedHandoffSurvivesActivityRecreation() {
        int id = ProofreadSession.begin("text");

        ProofreadSession.Pending pending = ProofreadSession.claim(id);
        assertEquals("text", pending.text);
        assertEquals("text", ProofreadSession.getPending(id).text);
        assertTrue(!ProofreadSession.hasResult(id));
    }

    @Test
    public void expiredResultAndOriginalTextCannotBeConsumed() {
        int id = ProofreadSession.begin("private text");
        ProofreadSession.claim(id);
        ProofreadSession.complete(id, ProofreadSession.Result.suggestion("suggestion"));
        ProofreadSession.notifyResultReady(id);

        ProofreadSession.expireForTest(id, ProofreadSession.expiryGenerationForTest());

        assertNull(ProofreadSession.getPending(id));
        assertNull(ProofreadSession.takeResult(id));
    }

    @Test
    public void claimedActiveBridgeHasNoPendingExpiry() {
        int id = ProofreadSession.begin("private text");
        long launchGeneration = ProofreadSession.expiryGenerationForTest();

        ProofreadSession.claim(id);
        ProofreadSession.expireForTest(id, launchGeneration);

        assertEquals("private text", ProofreadSession.getPending(id).text);
        assertTrue(ProofreadSession.isActive(id));
    }

    @Test
    public void staleLaunchExpiryCannotEraseFreshResult() {
        int id = ProofreadSession.begin("private text");
        long launchGeneration = ProofreadSession.expiryGenerationForTest();
        ProofreadSession.claim(id);
        ProofreadSession.complete(id, ProofreadSession.Result.suggestion("suggestion"));

        ProofreadSession.expireForTest(id, launchGeneration);

        assertEquals("suggestion", ProofreadSession.takeResult(id).suggestion);
    }

    @Test
    public void resultCannotBeConsumedBeforeForegroundBridgeStops() {
        int id = ProofreadSession.begin("private text");
        ProofreadSession.claim(id);
        ProofreadSession.complete(id, ProofreadSession.Result.suggestion("suggestion"));

        assertNull(ProofreadSession.peekDeliverableResult(id));
        assertNull(ProofreadSession.takeDeliverableResult(id));

        ProofreadSession.notifyResultReady(id);
        assertEquals("suggestion", ProofreadSession.takeDeliverableResult(id).suggestion);
    }

    @Test
    public void listenerReceivesCompletionAndCancellationSignals() {
        int[] lastChanged = {0};
        ProofreadSession.setListener(id -> lastChanged[0] = id);
        int id = ProofreadSession.begin("text");
        ProofreadSession.claim(id);
        ProofreadSession.complete(id, ProofreadSession.Result.suggestion("suggestion"));

        ProofreadSession.notifyResultReady(id);
        assertEquals(id, lastChanged[0]);

        lastChanged[0] = 0;
        ProofreadSession.cancel(id);
        assertEquals(id, lastChanged[0]);
        ProofreadSession.setListener(null);
    }
}
