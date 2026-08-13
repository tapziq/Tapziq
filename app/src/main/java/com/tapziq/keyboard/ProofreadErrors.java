package com.tapziq.keyboard;

import android.content.Context;

import com.google.mlkit.genai.common.GenAiException;

final class ProofreadErrors {
    private ProofreadErrors() {
    }

    static String userMessage(Context context, Throwable error) {
        if (!(error instanceof GenAiException)) {
            return context.getString(R.string.proofread_failed);
        }

        int errorCode = ((GenAiException) error).getErrorCode();
        if (errorCode == 30) {
            // BACKGROUND_USE_BLOCKED exists in current docs but not beta1's constants.
            return context.getString(R.string.proofread_foreground_required);
        }
        if (errorCode == 27) {
            // PER_APP_BATTERY_USE_QUOTA_EXCEEDED is documented after beta1.
            return context.getString(R.string.proofread_busy);
        }
        if (errorCode == 16) {
            // NOT_SUPPORTED is documented after beta1.
            return context.getString(R.string.proofread_unavailable);
        }

        switch (errorCode) {
            case GenAiException.ErrorCode.AICORE_INCOMPATIBLE:
            case GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE:
            case GenAiException.ErrorCode.NOT_AVAILABLE:
                return context.getString(R.string.proofread_unavailable);
            case GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE:
                return context.getString(R.string.proofread_no_space);
            case GenAiException.ErrorCode.BUSY:
                return context.getString(R.string.proofread_busy);
            case GenAiException.ErrorCode.REQUEST_TOO_LARGE:
                return context.getString(R.string.proofread_too_long);
            case GenAiException.ErrorCode.REQUEST_PROCESSING_ERROR:
            case GenAiException.ErrorCode.RESPONSE_GENERATION_ERROR:
            case GenAiException.ErrorCode.RESPONSE_PROCESSING_ERROR:
                return context.getString(R.string.proofread_not_processed);
            default:
                return context.getString(R.string.proofread_failed);
        }
    }
}
