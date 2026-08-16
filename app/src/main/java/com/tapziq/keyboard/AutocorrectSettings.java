package com.tapziq.keyboard;

import android.content.Context;
import android.content.SharedPreferences;

/** Stores the user's explicit choice to let Gemma edit completed text automatically. */
final class AutocorrectSettings {
    private static final String PREFERENCES = "keyboard_settings";
    private static final String ENABLED = "gemma_autocorrect_enabled";

    private AutocorrectSettings() {
    }

    static boolean isEnabled(Context context) {
        return preferences(context).getBoolean(ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(ENABLED, enabled).apply();
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

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
    }
}
