package com.tapziq.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class KeyboardLayoutsTest {
    @Test
    public void letterLayoutContainsEveryLetter() {
        Set<String> typed = outputs(KeyboardLayouts.rows(
                KeyboardLayouts.Mode.LETTERS,
                false,
                true,
                "↵"
        ));

        for (char letter = 'a'; letter <= 'z'; letter++) {
            assertTrue("Missing " + letter, typed.contains(String.valueOf(letter)));
        }
    }

    @Test
    public void shiftedLayoutEmitsUppercaseLetters() {
        Set<String> typed = outputs(KeyboardLayouts.rows(
                KeyboardLayouts.Mode.LETTERS,
                true,
                false,
                "Done"
        ));

        assertTrue(typed.contains("A"));
        assertTrue(typed.contains("Z"));
        assertTrue(!typed.contains("a"));
    }

    @Test
    public void numberAndSymbolLayoutsLinkToEachOtherAndBackToLetters() {
        List<List<KeyboardLayouts.KeySpec>> numbers = KeyboardLayouts.rows(
                KeyboardLayouts.Mode.NUMBERS,
                false,
                false,
                "↵"
        );
        List<List<KeyboardLayouts.KeySpec>> symbols = KeyboardLayouts.rows(
                KeyboardLayouts.Mode.SYMBOLS,
                false,
                false,
                "↵"
        );

        assertTrue(hasAction(numbers, KeyboardLayouts.Action.SYMBOLS));
        assertTrue(hasAction(numbers, KeyboardLayouts.Action.LETTERS));
        assertTrue(hasAction(symbols, KeyboardLayouts.Action.NUMBERS));
        assertTrue(hasAction(symbols, KeyboardLayouts.Action.LETTERS));
        assertEquals(10, countDigits(outputs(numbers)));
    }

    @Test
    public void imeSwitchAppearsOnlyWhenAvailable() {
        List<List<KeyboardLayouts.KeySpec>> withSwitch = KeyboardLayouts.rows(
                KeyboardLayouts.Mode.LETTERS,
                false,
                true,
                "↵"
        );
        List<List<KeyboardLayouts.KeySpec>> withoutSwitch = KeyboardLayouts.rows(
                KeyboardLayouts.Mode.LETTERS,
                false,
                false,
                "↵"
        );

        assertTrue(hasAction(withSwitch, KeyboardLayouts.Action.NEXT_IME));
        assertTrue(!hasAction(withoutSwitch, KeyboardLayouts.Action.NEXT_IME));
    }

    private static Set<String> outputs(List<List<KeyboardLayouts.KeySpec>> rows) {
        Set<String> output = new HashSet<>();
        for (List<KeyboardLayouts.KeySpec> row : rows) {
            for (KeyboardLayouts.KeySpec key : row) {
                if (key.text != null) {
                    output.add(key.text);
                }
            }
        }
        return output;
    }

    private static boolean hasAction(
            List<List<KeyboardLayouts.KeySpec>> rows,
            KeyboardLayouts.Action action
    ) {
        for (List<KeyboardLayouts.KeySpec> row : rows) {
            for (KeyboardLayouts.KeySpec key : row) {
                if (key.action == action) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int countDigits(Set<String> values) {
        int count = 0;
        for (char digit = '0'; digit <= '9'; digit++) {
            if (values.contains(String.valueOf(digit))) {
                count++;
            }
        }
        return count;
    }
}
