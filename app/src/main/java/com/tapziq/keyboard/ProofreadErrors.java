package com.tapziq.keyboard;

import android.content.Context;

final class ProofreadErrors {
    private ProofreadErrors() {
    }

    static String userMessage(Context context, Throwable error) {
        if (error instanceof GemmaProofreader.UnavailableException) {
            return context.getString(R.string.proofread_unavailable);
        }
        if (error instanceof GemmaModelStore.NotEnoughSpaceException) {
            return context.getString(R.string.proofread_no_space);
        }
        if (error instanceof GemmaProofreader.InferenceException) {
            return context.getString(R.string.proofread_not_processed);
        }
        return context.getString(R.string.proofread_failed);
    }
}
