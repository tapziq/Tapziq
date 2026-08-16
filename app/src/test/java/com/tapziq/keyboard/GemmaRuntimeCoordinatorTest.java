package com.tapziq.keyboard;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

public final class GemmaRuntimeCoordinatorTest {
    @Test
    public void installedModelCannotBeMutatedDuringInference() throws Exception {
        GemmaRuntimeCoordinator.acquireInference();
        try {
            AtomicBoolean acquired = new AtomicBoolean(true);
            Thread mutation = new Thread(
                    () -> {
                        boolean lease = GemmaRuntimeCoordinator.tryAcquireModelMutation();
                        acquired.set(lease);
                        if (lease) {
                            GemmaRuntimeCoordinator.releaseModelMutation();
                        }
                    }
            );
            mutation.start();
            mutation.join();
            assertFalse(acquired.get());
        } finally {
            GemmaRuntimeCoordinator.releaseInference();
        }

        assertTrue(GemmaRuntimeCoordinator.tryAcquireModelMutation());
        GemmaRuntimeCoordinator.releaseModelMutation();
    }
}
