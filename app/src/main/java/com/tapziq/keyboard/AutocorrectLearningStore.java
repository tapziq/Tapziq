package com.tapziq.keyboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** App-private persistence for the user's explicitly enabled correction memory. */
@SuppressLint("ApplySharedPref")
final class AutocorrectLearningStore {
    private static final String PREFERENCES = "autocorrect_learning";
    private static final String SERIALIZED_MEMORY = "word_preferences_v1";
    private static final String CLEAR_GENERATION = "clear_generation";
    private static final int MAX_SERIALIZED_CHARACTERS = 64 * 1024;
    private static final AtomicBoolean CLEAR_RETRY_REQUIRED = new AtomicBoolean();

    private final SharedPreferences preferences;
    private String cachedSerialized;
    private AutocorrectLearningMemory cachedMemory;

    AutocorrectLearningStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
    }

    synchronized void recordRejection(AutocorrectLearning.Feedback feedback) {
        persist(memory().recordRejection(feedback));
    }

    synchronized void recordReplacement(
            AutocorrectLearning.Feedback feedback,
            String preferred
    ) {
        persist(memory().recordReplacement(feedback, preferred));
    }

    synchronized void forget(AutocorrectLearning.Feedback feedback) {
        persist(memory().forget(feedback));
    }

    synchronized List<AutocorrectLearningMemory.Entry> relevantTo(String text) {
        return memory().relevantTo(text);
    }

    synchronized boolean rejects(AutocorrectEdit edit) {
        return memory().rejects(edit);
    }

    synchronized int size() {
        return memory().size();
    }

    synchronized boolean hasStoredData() {
        memory();
        return CLEAR_RETRY_REQUIRED.get() || preferences.contains(SERIALIZED_MEMORY);
    }

    synchronized boolean needsClearRetry() {
        return CLEAR_RETRY_REQUIRED.get();
    }

    synchronized long clearGeneration() {
        return readClearGeneration();
    }

    synchronized boolean clear() {
        long generation = readClearGeneration();
        boolean cleared = preferences.edit()
                .remove(SERIALIZED_MEMORY)
                .putLong(CLEAR_GENERATION, generation == Long.MAX_VALUE ? 0L : generation + 1L)
                .commit();
        if (cleared) {
            cachedSerialized = null;
            cachedMemory = new AutocorrectLearningMemory();
            CLEAR_RETRY_REQUIRED.set(false);
        } else {
            // SharedPreferences updates its process-local map before the disk write completes.
            // Keep the erase action retryable across Activity/store recreation even though this
            // process now observes no value. Process death reloads the actual on-disk state.
            cachedSerialized = null;
            cachedMemory = new AutocorrectLearningMemory();
            CLEAR_RETRY_REQUIRED.set(true);
        }
        return cleared;
    }

    void registerClearListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences.registerOnSharedPreferenceChangeListener(listener);
    }

    void unregisterClearListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener);
    }

    static boolean isClearPreference(String key) {
        return CLEAR_GENERATION.equals(key);
    }

    private AutocorrectLearningMemory memory() {
        String serialized = readSerializedMemory();
        String canonical = canonicalSerialized(serialized);
        if (!Objects.equals(serialized, canonical)) {
            SharedPreferences.Editor editor = preferences.edit();
            if (canonical == null) {
                editor.remove(SERIALIZED_MEMORY);
            } else {
                editor.putString(SERIALIZED_MEMORY, canonical);
            }
            // Self-healing malformed bytes and explicit erasure are privacy operations: make
            // their durability decision before telling the setup UI that the bytes are gone.
            boolean durable = editor.commit();
            if (!durable && canonical == null && serialized != null) {
                CLEAR_RETRY_REQUIRED.set(true);
            }
            // Treat invalid data as empty for inference even if disk persistence fails;
            // hasStoredData() still exposes the raw preference so the user can retry clearing it.
            serialized = canonical;
        }
        if (cachedMemory == null || !Objects.equals(cachedSerialized, serialized)) {
            cachedSerialized = serialized;
            cachedMemory = AutocorrectLearningMemory.fromJson(serialized);
        }
        return cachedMemory;
    }

    static String canonicalSerialized(String serialized) {
        if (serialized == null || serialized.length() > MAX_SERIALIZED_CHARACTERS) {
            return null;
        }
        AutocorrectLearningMemory parsed = AutocorrectLearningMemory.fromJson(serialized);
        if (parsed.size() == 0) {
            return null;
        }
        String canonical = parsed.toJson();
        return canonical.length() <= MAX_SERIALIZED_CHARACTERS ? canonical : null;
    }

    private String readSerializedMemory() {
        try {
            return preferences.getString(SERIALIZED_MEMORY, null);
        } catch (ClassCastException wrongType) {
            // A stale/corrupt preference of the wrong primitive type is never inference input.
            if (!preferences.edit().remove(SERIALIZED_MEMORY).commit()) {
                CLEAR_RETRY_REQUIRED.set(true);
            }
            return null;
        }
    }

    private long readClearGeneration() {
        try {
            return preferences.getLong(CLEAR_GENERATION, 0L);
        } catch (ClassCastException wrongType) {
            preferences.edit().remove(CLEAR_GENERATION).commit();
            return 0L;
        }
    }

    private void persist(AutocorrectLearningMemory memory) {
        if (memory == cachedMemory) {
            return;
        }
        if (memory.size() == 0) {
            cachedMemory = memory;
            cachedSerialized = null;
            preferences.edit().remove(SERIALIZED_MEMORY).apply();
            return;
        }
        String serialized = memory.toJson();
        if (serialized.length() > MAX_SERIALIZED_CHARACTERS) {
            return;
        }
        cachedMemory = memory;
        cachedSerialized = serialized;
        preferences.edit().putString(SERIALIZED_MEMORY, serialized).apply();
    }
}
