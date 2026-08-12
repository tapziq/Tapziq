package com.tapziq.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;

import org.junit.Test;

public final class EditorBehaviorTest {
    @Test
    public void numericInputClassesOpenTheNumberLayout() {
        assertEquals(
                KeyboardLayouts.Mode.NUMBERS,
                EditorBehavior.initialMode(InputType.TYPE_CLASS_NUMBER)
        );
        assertEquals(
                KeyboardLayouts.Mode.NUMBERS,
                EditorBehavior.initialMode(InputType.TYPE_CLASS_PHONE)
        );
        assertEquals(
                KeyboardLayouts.Mode.LETTERS,
                EditorBehavior.initialMode(InputType.TYPE_CLASS_TEXT)
        );
    }

    @Test
    public void enterLabelMatchesTheRequestedEditorAction() {
        assertEquals("Search", EditorBehavior.enterLabel(EditorInfo.IME_ACTION_SEARCH));
        assertEquals("Send", EditorBehavior.enterLabel(EditorInfo.IME_ACTION_SEND));
        assertEquals("↵", EditorBehavior.enterLabel(EditorInfo.IME_ACTION_NONE));
    }

    @Test
    public void noEnterActionFlagSuppressesEditorAction() {
        int options = EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_ENTER_ACTION;
        assertEquals(EditorInfo.IME_ACTION_NONE, EditorBehavior.actionableImeAction(options));
    }

    @Test
    public void multilineDetectionRequiresTextAndMultilineFlag() {
        assertTrue(EditorBehavior.isMultiline(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
        ));
        assertFalse(EditorBehavior.isMultiline(InputType.TYPE_CLASS_TEXT));
        assertFalse(EditorBehavior.isMultiline(InputType.TYPE_CLASS_NUMBER));
    }
}
