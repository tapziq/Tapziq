package com.tapziq.keyboard;

import android.os.Build;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;

final class EditorBehavior {
    private EditorBehavior() {
    }

    static KeyboardLayouts.Mode initialMode(int inputType) {
        int inputClass = inputType & InputType.TYPE_MASK_CLASS;
        if (inputClass == InputType.TYPE_CLASS_NUMBER
                || inputClass == InputType.TYPE_CLASS_PHONE
                || inputClass == InputType.TYPE_CLASS_DATETIME) {
            return KeyboardLayouts.Mode.NUMBERS;
        }
        return KeyboardLayouts.Mode.LETTERS;
    }

    static int actionableImeAction(int imeOptions) {
        if ((imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) {
            return EditorInfo.IME_ACTION_NONE;
        }

        int action = imeOptions & EditorInfo.IME_MASK_ACTION;
        switch (action) {
            case EditorInfo.IME_ACTION_DONE:
            case EditorInfo.IME_ACTION_GO:
            case EditorInfo.IME_ACTION_NEXT:
            case EditorInfo.IME_ACTION_PREVIOUS:
            case EditorInfo.IME_ACTION_SEARCH:
            case EditorInfo.IME_ACTION_SEND:
                return action;
            default:
                return EditorInfo.IME_ACTION_NONE;
        }
    }

    static boolean isMultiline(int inputType) {
        return (inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
                && (inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
    }

    static boolean supportsProofreading(EditorInfo info) {
        if (info == null
                || (info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA
                        && !info.isWritingToolsEnabled())) {
            return false;
        }
        return supportsProofreadingInputType(info.inputType);
    }

    static boolean supportsProofreadingInputType(int inputType) {
        if ((inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) {
            return false;
        }
        if ((inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) {
            return false;
        }
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        switch (variation) {
            case InputType.TYPE_TEXT_VARIATION_NORMAL:
            case InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE:
            case InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE:
            case InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT:
            case InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT:
                return true;
            default:
                return false;
        }
    }

    static String enterLabel(int imeOptions) {
        switch (actionableImeAction(imeOptions)) {
            case EditorInfo.IME_ACTION_DONE:
                return "Done";
            case EditorInfo.IME_ACTION_GO:
                return "Go";
            case EditorInfo.IME_ACTION_NEXT:
                return "Next";
            case EditorInfo.IME_ACTION_PREVIOUS:
                return "Prev";
            case EditorInfo.IME_ACTION_SEARCH:
                return "Search";
            case EditorInfo.IME_ACTION_SEND:
                return "Send";
            default:
                return "↵";
        }
    }
}
