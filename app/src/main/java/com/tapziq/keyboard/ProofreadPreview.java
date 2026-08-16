package com.tapziq.keyboard;

import java.util.Locale;

/** Makes formatting characters explicit in the compact proofreading preview. */
final class ProofreadPreview {
    private ProofreadPreview() {
    }

    static String visibleText(String value) {
        StringBuilder visible = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\n' -> visible.append(" ↵ ");
                case '\r' -> visible.append(" ␍ ");
                case '\t' -> visible.append(" ⇥ ");
                default -> {
                    if (isStructural(character)) {
                        visible.append(String.format(
                                Locale.ROOT,
                                " <U+%04X> ",
                                (int) character
                        ));
                    } else {
                        visible.append(character);
                    }
                }
            }
        }
        return visible.toString();
    }

    static String structuralCharacters(String value) {
        StringBuilder structural = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (isStructural(character)) {
                structural.append(character);
            }
        }
        return structural.toString();
    }

    private static boolean isStructural(char character) {
        int type = Character.getType(character);
        return Character.isISOControl(character)
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }
}
