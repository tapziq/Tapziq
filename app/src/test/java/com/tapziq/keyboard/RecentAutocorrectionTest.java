package com.tapziq.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.junit.Test;

public final class RecentAutocorrectionTest {
    @Test
    public void capturesOnlyAnExactSingleWordReplacement() {
        RecentAutocorrection recent = RecentAutocorrection.from(
                edit("Prefix teh ", "Prefix the")
        );

        assertNotNull(recent);
        assertEquals("teh", recent.feedback().written);
        assertEquals("the", recent.feedback().rejected);
        assertEquals(7, recent.start());
        assertEquals(10, recent.end());

        AutocorrectEdit compound = edit("Prefix teh cats ", "Prefix the cat");
        assertNull(RecentAutocorrection.from(compound));
    }

    @Test
    public void appendedTypingPreservesTheCorrectionIdentity() {
        RecentAutocorrection recent = RecentAutocorrection.from(
                edit("Prefix teh ", "Prefix the")
        );

        assertTrue(recent.matchesDocument("Prefix the ", 0, true));
        assertTrue(recent.matchesDocument("Prefix the next words", 0, true));
        assertTrue(recent.matches("Prefix the next words", 0, 8, 8, true));
    }

    @Test
    public void changedWordOrNearbyAnchorDoesNotMatch() {
        RecentAutocorrection recent = RecentAutocorrection.from(
                edit("Prefix teh suffix ", "Prefix the suffix")
        );

        assertFalse(recent.matchesDocument("Prefix thy suffix more", 0, true));
        assertFalse(recent.matchesDocument("Prefiy the suffix more", 0, true));
        assertFalse(recent.matchesDocument("Prefix the-suffix more", 0, true));
        assertFalse(recent.matchesDocument("Prefix the suffix more", 1, true));
        assertFalse(recent.matchesDocument("Prefix the suffix more", 0, false));
        assertFalse(recent.matchesDocument(null, 0, true));
    }

    @Test
    public void collapsedSelectionUsesAnEndExclusiveWordRange() {
        RecentAutocorrection recent = RecentAutocorrection.from(
                edit("Prefix teh ", "Prefix the")
        );
        String document = "Prefix the more";

        assertTrue(recent.matches(document, 0, 7, 7, true));
        assertTrue(recent.matches(document, 0, 9, 9, true));
        assertFalse(recent.matches(document, 0, 10, 10, true));
        assertFalse(recent.matches(document, 0, 6, 6, true));
    }

    @Test
    public void selectedRangeMustStayInsideTheCorrectedWord() {
        RecentAutocorrection recent = RecentAutocorrection.from(
                edit("Prefix teh ", "Prefix the")
        );
        String document = "Prefix the more";

        assertTrue(recent.matches(document, 0, 7, 10, true));
        assertTrue(recent.matches(document, 0, 8, 9, true));
        assertTrue(recent.matches(document, 0, 9, 8, true));
        assertFalse(recent.matches(document, 0, 6, 8, true));
        assertFalse(recent.matches(document, 0, 9, 11, true));
        assertFalse(recent.matches(document, 0, 11, 9, true));
        assertFalse(recent.matches(document, 0, 5, 7, true));
        assertFalse(recent.matches(document, 0, 10, 12, true));
    }

    @Test
    public void retainedContextIsHashedRatherThanPlaintext() {
        RecentAutocorrection recent = RecentAutocorrection.from(
                edit("private-prefix teh private-suffix ", "private-prefix the private-suffix")
        );

        assertNotNull(recent);
        for (Field field : RecentAutocorrection.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                assertFalse("Unexpected retained plaintext field: " + field.getName(),
                        field.getType() == String.class);
            }
        }
    }

    @Test
    public void monotonicLifetimeExpiresOldTapEvidence() {
        RecentAutocorrection recent = RecentAutocorrection.from(
                edit("Prefix teh ", "Prefix the")
        );
        long created = recent.createdAtNanos();

        assertFalse(recent.isExpired(created + 99L, 100L));
        assertTrue(recent.isExpired(created + 100L, 100L));
    }

    private static AutocorrectEdit edit(String document, String correctedTarget) {
        AutocorrectTarget target = AutocorrectTarget.capture(
                document,
                0,
                document.length(),
                document.length(),
                true,
                " "
        ).target;
        AutocorrectEdit.Validation validation = AutocorrectEdit.validate(target, correctedTarget);
        assertTrue(validation.failure.name(), validation.succeeded());
        return validation.edit;
    }
}
