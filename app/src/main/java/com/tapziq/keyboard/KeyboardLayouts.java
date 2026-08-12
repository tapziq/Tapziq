package com.tapziq.keyboard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class KeyboardLayouts {
    enum Mode {
        LETTERS,
        NUMBERS,
        SYMBOLS
    }

    enum Action {
        TEXT,
        SHIFT,
        DELETE,
        SPACE,
        ENTER,
        LETTERS,
        NUMBERS,
        SYMBOLS,
        NEXT_IME,
        SPACER
    }

    static final class KeySpec {
        final String label;
        final String text;
        final Action action;
        final float weight;

        private KeySpec(String label, String text, Action action, float weight) {
            this.label = label;
            this.text = text;
            this.action = action;
            this.weight = weight;
        }

        boolean isSpecial() {
            return action != Action.TEXT && action != Action.SPACE;
        }
    }

    private KeyboardLayouts() {
    }

    static List<List<KeySpec>> rows(
            Mode mode,
            boolean shifted,
            boolean offerImeSwitch,
            String enterLabel
    ) {
        switch (mode) {
            case NUMBERS:
                return numberRows(offerImeSwitch, enterLabel);
            case SYMBOLS:
                return symbolRows(offerImeSwitch, enterLabel);
            case LETTERS:
            default:
                return letterRows(shifted, offerImeSwitch, enterLabel);
        }
    }

    private static List<List<KeySpec>> letterRows(
            boolean shifted,
            boolean offerImeSwitch,
            String enterLabel
    ) {
        List<List<KeySpec>> rows = new ArrayList<>();
        rows.add(textRow("qwertyuiop", shifted));

        List<KeySpec> middle = new ArrayList<>();
        middle.add(spacer(0.45f));
        middle.addAll(textRow("asdfghjkl", shifted));
        middle.add(spacer(0.45f));
        rows.add(middle);

        List<KeySpec> lower = new ArrayList<>();
        lower.add(action("⇧", Action.SHIFT, 1.35f));
        lower.addAll(textRow("zxcvbnm", shifted));
        lower.add(action("⌫", Action.DELETE, 1.35f));
        rows.add(lower);

        rows.add(bottomRow("?123", Action.NUMBERS, offerImeSwitch, enterLabel));
        return rows;
    }

    private static List<List<KeySpec>> numberRows(boolean offerImeSwitch, String enterLabel) {
        List<List<KeySpec>> rows = new ArrayList<>();
        rows.add(textKeys("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"));
        rows.add(textKeys("@", "#", "$", "_", "&", "-", "+", "(", ")", "/"));

        List<KeySpec> lower = new ArrayList<>();
        lower.add(action("=\\<", Action.SYMBOLS, 1.35f));
        lower.addAll(textKeys("*", "\"", "'", ":", ";", "!", "?"));
        lower.add(action("⌫", Action.DELETE, 1.35f));
        rows.add(lower);

        rows.add(bottomRow("ABC", Action.LETTERS, offerImeSwitch, enterLabel));
        return rows;
    }

    private static List<List<KeySpec>> symbolRows(boolean offerImeSwitch, String enterLabel) {
        List<List<KeySpec>> rows = new ArrayList<>();
        rows.add(textKeys("[", "]", "{", "}", "#", "%", "^", "*", "+", "="));
        rows.add(textKeys("_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•"));

        List<KeySpec> lower = new ArrayList<>();
        lower.add(action("123", Action.NUMBERS, 1.35f));
        lower.addAll(textKeys(".", ",", "?", "!", "'", "\"", "`"));
        lower.add(action("⌫", Action.DELETE, 1.35f));
        rows.add(lower);

        rows.add(bottomRow("ABC", Action.LETTERS, offerImeSwitch, enterLabel));
        return rows;
    }

    private static List<KeySpec> bottomRow(
            String modeLabel,
            Action modeAction,
            boolean offerImeSwitch,
            String enterLabel
    ) {
        List<KeySpec> row = new ArrayList<>();
        row.add(action(modeLabel, modeAction, 1.6f));
        if (offerImeSwitch) {
            row.add(action("🌐", Action.NEXT_IME, 1.05f));
        }
        row.add(text(",", ",", 0.85f));
        row.add(new KeySpec("space", " ", Action.SPACE, 3.8f));
        row.add(text(".", ".", 0.85f));
        row.add(action(enterLabel, Action.ENTER, 1.55f));
        return row;
    }

    private static List<KeySpec> textRow(String characters, boolean shifted) {
        List<KeySpec> row = new ArrayList<>();
        for (int index = 0; index < characters.length(); index++) {
            String value = String.valueOf(characters.charAt(index));
            if (shifted) {
                value = value.toUpperCase(Locale.ROOT);
            }
            row.add(text(value, value, 1f));
        }
        return row;
    }

    private static List<KeySpec> textKeys(String... values) {
        List<KeySpec> keys = new ArrayList<>();
        Arrays.stream(values).forEach(value -> keys.add(text(value, value, 1f)));
        return keys;
    }

    private static KeySpec text(String label, String value, float weight) {
        return new KeySpec(label, value, Action.TEXT, weight);
    }

    private static KeySpec action(String label, Action action, float weight) {
        return new KeySpec(label, null, action, weight);
    }

    private static KeySpec spacer(float weight) {
        return new KeySpec("", null, Action.SPACER, weight);
    }
}
