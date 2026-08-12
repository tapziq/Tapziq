package com.tapziq.keyboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;

import java.util.List;

@SuppressLint("ViewConstructor")
final class KeyboardPanel extends LinearLayout {
    interface Listener {
        void onKey(KeyboardLayouts.KeySpec key);
    }

    private final Listener listener;

    KeyboardPanel(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setPadding(dp(2), dp(4), dp(2), dp(5));
        setBackgroundColor(context.getColor(R.color.keyboard_background));
        setOnApplyWindowInsetsListener((view, windowInsets) -> {
            applyNavigationInsets(windowInsets);
            return windowInsets;
        });
    }

    void render(
            KeyboardLayouts.Mode mode,
            boolean shifted,
            boolean offerImeSwitch,
            String enterLabel
    ) {
        removeAllViews();
        List<List<KeyboardLayouts.KeySpec>> rows =
                KeyboardLayouts.rows(mode, shifted, offerImeSwitch, enterLabel);

        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        int rowHeight = dp(landscape ? 44 : 52);

        for (List<KeyboardLayouts.KeySpec> keys : rows) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, rowHeight));

            for (KeyboardLayouts.KeySpec key : keys) {
                LinearLayout.LayoutParams params =
                        new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, key.weight);
                params.setMargins(dp(2), dp(3), dp(2), dp(3));

                if (key.action == KeyboardLayouts.Action.SPACER) {
                    row.addView(new View(getContext()), params);
                    continue;
                }

                Button button = new Button(getContext());
                button.setText(key.label);
                button.setAllCaps(false);
                button.setGravity(Gravity.CENTER);
                button.setTypeface(Typeface.create("sans", Typeface.NORMAL));
                button.setTextSize(key.label.length() > 3 ? 13f : 18f);
                button.setTextColor(getContext().getColor(
                        key.action == KeyboardLayouts.Action.SHIFT && shifted
                                ? R.color.key_text_active
                                : R.color.key_text
                ));
                button.setMinWidth(0);
                button.setMinimumWidth(0);
                button.setMinHeight(0);
                button.setMinimumHeight(0);
                button.setPadding(0, 0, 0, 0);
                button.setStateListAnimator(null);
                button.setBackgroundResource(backgroundFor(key, shifted));
                button.setContentDescription(descriptionFor(key));
                button.setOnClickListener(view -> listener.onKey(key));
                row.addView(button, params);
            }
        }
    }

    private int backgroundFor(KeyboardLayouts.KeySpec key, boolean shifted) {
        if (key.action == KeyboardLayouts.Action.SHIFT && shifted) {
            return R.drawable.key_shift_active_background;
        }
        return key.isSpecial()
                ? R.drawable.key_special_background
                : R.drawable.key_background;
    }

    private String descriptionFor(KeyboardLayouts.KeySpec key) {
        switch (key.action) {
            case SHIFT:
                return "Shift";
            case DELETE:
                return "Backspace";
            case SPACE:
                return "Space";
            case ENTER:
                return key.label.equals("↵") ? "Enter" : key.label;
            case LETTERS:
                return "Letters";
            case NUMBERS:
                return "Numbers and symbols";
            case SYMBOLS:
                return "More symbols";
            case NEXT_IME:
                return "Switch keyboard";
            case TEXT:
            default:
                return key.label;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("deprecation")
    private void applyNavigationInsets(WindowInsets windowInsets) {
        int left;
        int right;
        int bottom;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Insets navigation = windowInsets.getInsets(WindowInsets.Type.navigationBars());
            left = navigation.left;
            right = navigation.right;
            bottom = navigation.bottom;
        } else {
            left = windowInsets.getSystemWindowInsetLeft();
            right = windowInsets.getSystemWindowInsetRight();
            bottom = windowInsets.getSystemWindowInsetBottom();
        }
        setPadding(dp(2) + left, dp(4), dp(2) + right, dp(5) + bottom);
    }
}
