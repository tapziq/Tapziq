package com.tapziq.keyboard;

import android.content.Context;
import android.content.SharedPreferences;

/** Stores the user's explicit choices for automatic Gemma editing and local learning. */
final class AutocorrectSettings {
    private static final String PREFERENCES = "keyboard_settings";
    private static final String ENABLED = "gemma_autocorrect_enabled";
    private static final String LEARNING_ENABLED = "gemma_autocorrect_learning_enabled";

    private AutocorrectSettings() {
    }

    static boolean isEnabled(Context context) {
        return preferences(context).getBoolean(ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(ENABLED, enabled).apply();
    }

    static boolean isLearningEnabled(Context context) {
        return preferences(context).getBoolean(LEARNING_ENABLED, false);
    }

    static void setLearningEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(LEARNING_ENABLED, enabled).apply();
    }

    static void registerListener(
            Context context,
            SharedPreferences.OnSharedPreferenceChangeListener listener
    ) {
        preferences(context).registerOnSharedPreferenceChangeListener(listener);
    }

    static void unregisterListener(
            Context context,
            SharedPreferences.OnSharedPreferenceChangeListener listener
    ) {
        preferences(context).unregisterOnSharedPreferenceChangeListener(listener);
    }

    static boolean isEnabledPreference(String key) {
        return ENABLED.equals(key);
    }

    static boolean isLearningEnabledPreference(String key) {
        return LEARNING_ENABLED.equals(key);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
    }
}
