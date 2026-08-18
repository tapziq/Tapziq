package com.tapziq.keyboard;

/** Lifecycle policy kept platform-free so stopped-Activity reclamation stays regression tested. */
final class TranslationBridgeLifecycle {
    private TranslationBridgeLifecycle() {
    }

    static boolean shouldCancelOnDestroy(
            boolean resultHandled,
            boolean activityFinishing,
            boolean changingConfigurations
    ) {
        // Android may destroy the stopped parent while its external child is still foreground.
        // A later recreation must be able to reclaim the in-process handoff and receive a result.
        return !resultHandled && activityFinishing && !changingConfigurations;
    }
}
