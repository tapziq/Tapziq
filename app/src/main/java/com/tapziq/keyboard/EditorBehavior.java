package com.tapziq.keyboard;

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
