package com.tapziq.keyboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Bounded, context-free correction preferences suitable for app-private persistence. */
final class AutocorrectLearningMemory {
    static final int MAX_ENTRIES = 100;
    static final int MAX_PROMPT_ENTRIES = 8;
    private static final int VERSION = 1;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    static final class Entry {
        final String written;
        final String rejected;
        final String preferred;

        Entry(String written, String rejected, String preferred) {
            this.written = Objects.requireNonNull(written);
            this.rejected = Objects.requireNonNull(rejected);
            this.preferred = Objects.requireNonNull(preferred);
        }
    }

    private final List<Entry> entries;

    AutocorrectLearningMemory() {
        this(Collections.emptyList());
    }

    private AutocorrectLearningMemory(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(entries);
    }

    int size() {
        return entries.size();
    }

    List<Entry> entries() {
        return entries;
    }

    AutocorrectLearningMemory recordRejection(AutocorrectLearning.Feedback feedback) {
        if (feedback == null) {
            return this;
        }
        Entry existing = matching(feedback);
        String preferred = existing == null ? feedback.written : existing.preferred;
        return upsert(new Entry(
                feedback.written,
                feedback.rejected,
                preferred
        ));
    }

    AutocorrectLearningMemory recordReplacement(
            AutocorrectLearning.Feedback feedback,
            String preferred
    ) {
        if (feedback == null
                || !AutocorrectLearning.isValidToken(preferred)
                || preferred.equals(feedback.rejected)) {
            return this;
        }
        return upsert(new Entry(
                feedback.written,
                feedback.rejected,
                preferred
        ));
    }

    AutocorrectLearningMemory forget(AutocorrectLearning.Feedback feedback) {
        if (feedback == null) {
            return this;
        }
        List<Entry> updated = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            if (!matches(entry, feedback)) {
                updated.add(entry);
            }
        }
        return updated.size() == entries.size() ? this : new AutocorrectLearningMemory(updated);
    }

    List<Entry> relevantTo(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        List<Entry> relevant = new ArrayList<>();
        for (Entry entry : entries) {
            if (AutocorrectLearning.containsExactToken(text, entry.written)
                    || AutocorrectLearning.containsExactToken(text, entry.preferred)) {
                relevant.add(entry);
                if (relevant.size() == MAX_PROMPT_ENTRIES) {
                    break;
                }
            }
        }
        return Collections.unmodifiableList(relevant);
    }

    boolean rejects(AutocorrectEdit edit) {
        Objects.requireNonNull(edit, "edit");
        for (Entry entry : entries) {
            if (AutocorrectLearning.changesToken(
                    edit.sourceDocument(),
                    edit.resultingDocument(),
                    entry.written,
                    entry.rejected
            ) || AutocorrectLearning.changesToken(
                    edit.sourceDocument(),
                    edit.resultingDocument(),
                    entry.preferred,
                    entry.rejected
            )) {
                return true;
            }
        }
        return false;
    }

    String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonArray values = new JsonArray();
        for (Entry entry : entries) {
            JsonObject value = new JsonObject();
            value.addProperty("written", entry.written);
            value.addProperty("rejected", entry.rejected);
            value.addProperty("preferred", entry.preferred);
            values.add(value);
        }
        root.add("entries", values);
        return GSON.toJson(root);
    }

    static AutocorrectLearningMemory fromJson(String serialized) {
        if (serialized == null || serialized.isBlank()) {
            return new AutocorrectLearningMemory();
        }
        try {
            JsonElement parsed = JsonParser.parseString(serialized);
            if (!parsed.isJsonObject()) {
                return new AutocorrectLearningMemory();
            }
            JsonObject root = parsed.getAsJsonObject();
            if (root.size() != 2
                    || !isExactVersion(root.get("version"))
                    || !root.has("entries")
                    || !root.get("entries").isJsonArray()) {
                return new AutocorrectLearningMemory();
            }
            List<Entry> loaded = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("entries")) {
                Entry entry = parseEntry(element);
                if (entry != null && !containsKey(loaded, entry.written, entry.rejected)) {
                    loaded.add(entry);
                    if (loaded.size() == MAX_ENTRIES) {
                        break;
                    }
                }
            }
            return new AutocorrectLearningMemory(loaded);
        } catch (RuntimeException error) {
            return new AutocorrectLearningMemory();
        }
    }

    private AutocorrectLearningMemory upsert(Entry replacement) {
        List<Entry> updated = new ArrayList<>(Math.min(MAX_ENTRIES, entries.size() + 1));
        updated.add(replacement);
        for (Entry entry : entries) {
            if (sameKey(entry, replacement)) {
                continue;
            }
            updated.add(entry);
            if (updated.size() == MAX_ENTRIES) {
                break;
            }
        }
        return new AutocorrectLearningMemory(updated);
    }

    private Entry matching(AutocorrectLearning.Feedback feedback) {
        for (Entry entry : entries) {
            if (matches(entry, feedback)) {
                return entry;
            }
        }
        return null;
    }

    private static boolean matches(Entry entry, AutocorrectLearning.Feedback feedback) {
        return entry.written.equals(feedback.written)
                && entry.rejected.equals(feedback.rejected);
    }

    private static boolean sameKey(Entry left, Entry right) {
        return left.written.equals(right.written) && left.rejected.equals(right.rejected);
    }

    private static Entry parseEntry(JsonElement element) {
        try {
            if (!element.isJsonObject()) {
                return null;
            }
            JsonObject value = element.getAsJsonObject();
            if (value.size() != 3
                    || !isString(value.get("written"))
                    || !isString(value.get("rejected"))
                    || !isString(value.get("preferred"))
                    || !value.has("written")
                    || !value.has("rejected")
                    || !value.has("preferred")) {
                return null;
            }
            String written = value.get("written").getAsString();
            String rejected = value.get("rejected").getAsString();
            String preferred = value.get("preferred").getAsString();
            if (!AutocorrectLearning.isValidToken(written)
                    || !AutocorrectLearning.isValidToken(rejected)
                    || !AutocorrectLearning.isValidToken(preferred)
                    || written.equals(rejected)
                    || preferred.equals(rejected)) {
                return null;
            }
            return new Entry(written, rejected, preferred);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static boolean isExactVersion(JsonElement element) {
        return element != null
                && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isNumber()
                && Integer.toString(VERSION).equals(element.getAsString());
    }

    private static boolean isString(JsonElement element) {
        return element != null
                && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString();
    }

    private static boolean containsKey(List<Entry> entries, String written, String rejected) {
        for (Entry entry : entries) {
            if (entry.written.equals(written) && entry.rejected.equals(rejected)) {
                return true;
            }
        }
        return false;
    }
}
