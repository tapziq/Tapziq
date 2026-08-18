package com.tapziq.keyboard;

/** Pure decision policy for a result returning from the external translation Activity. */
final class TranslationReturnPolicy {
    enum Decision {
        WAIT,
        STALE,
        ACCEPT
    }

    private TranslationReturnPolicy() {
    }

    static Decision decide(
            int inputType,
            boolean sameClient,
            boolean supportedEditor,
            boolean sameReportedField,
            boolean unchangedTarget
    ) {
        // TYPE_NULL is zero. Android can expose it briefly while restoring the source window.
        if (inputType == 0 || !sameClient) {
            return Decision.WAIT;
        }
        if (!supportedEditor || !sameReportedField || !unchangedTarget) {
            return Decision.STALE;
        }
        return Decision.ACCEPT;
    }
}
