package com.tapziq.keyboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

@SuppressLint("ViewConstructor")
final class KeyboardPanel extends LinearLayout {
    interface Listener {
        void onKey(KeyboardLayouts.KeySpec key);

        void onApplyProofread();

        void onDismissProofread();
    }

    private final Listener listener;
    private LinearLayout suggestionBar;
    private TextView suggestionText;
    private Button applyButton;
    private Button dismissButton;
    private String visibleProofreadText;
    private boolean visibleSuggestion;
    private boolean visibleDismiss;

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

    void showSuggestion(String suggestion) {
        visibleProofreadText = suggestion;
        visibleSuggestion = true;
        visibleDismiss = true;
        if (suggestionBar == null || suggestionText == null
                || applyButton == null || dismissButton == null) {
            return;
        }
        restoreProofreadBar();
    }

    void showProofreadMessage(String message, boolean showDismiss) {
        visibleProofreadText = message;
        visibleSuggestion = false;
        visibleDismiss = showDismiss;
        if (suggestionBar == null || suggestionText == null
                || applyButton == null || dismissButton == null) {
            return;
        }
        restoreProofreadBar();
    }

    void hideProofreadMessage() {
        visibleProofreadText = null;
        visibleSuggestion = false;
        visibleDismiss = false;
        if (suggestionBar != null) {
            setProofreadBarVisible(false);
        }
    }

    void render(
            KeyboardLayouts.Mode mode,
            boolean shifted,
            boolean offerImeSwitch,
            String enterLabel,
            boolean offerProofread
    ) {
        removeAllViews();
        suggestionBar = createSuggestionBar();
        if (visibleProofreadText != null) {
            addView(suggestionBar, new LayoutParams(LayoutParams.MATCH_PARENT, dp(48)));
        } else {
            addView(suggestionBar, new LayoutParams(LayoutParams.MATCH_PARENT, 0));
        }
        restoreProofreadBar();
        List<List<KeyboardLayouts.KeySpec>> rows =
                KeyboardLayouts.rows(
                        mode,
                        shifted,
                        offerImeSwitch,
                        enterLabel,
                        offerProofread
                );

        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        int rowHeight = dp(landscape ? 40 : 48);

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
                setHapticClickListener(button, () -> listener.onKey(key));
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

    private LinearLayout createSuggestionBar() {
        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(3), dp(4), dp(3));

        HorizontalScrollView scroller = new HorizontalScrollView(getContext());
        scroller.setHorizontalScrollBarEnabled(false);
        suggestionText = new TextView(getContext());
        suggestionText.setTextSize(15f);
        suggestionText.setGravity(Gravity.CENTER_VERTICAL);
        suggestionText.setPadding(dp(10), 0, dp(10), 0);
        suggestionText.setSingleLine(true);
        suggestionText.setTypeface(android.graphics.Typeface.MONOSPACE);
        scroller.addView(suggestionText, new HorizontalScrollView.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT
        ));
        bar.addView(scroller, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));

        applyButton = compactButton("Apply", "Apply proofreading suggestion");
        setHapticClickListener(applyButton, listener::onApplyProofread);
        bar.addView(applyButton, new LayoutParams(dp(72), LayoutParams.MATCH_PARENT));

        dismissButton = compactButton("×", "Dismiss proofreading suggestion");
        setHapticClickListener(dismissButton, listener::onDismissProofread);
        LinearLayout.LayoutParams dismissParams = new LayoutParams(dp(46), LayoutParams.MATCH_PARENT);
        dismissParams.leftMargin = dp(4);
        bar.addView(dismissButton, dismissParams);
        return bar;
    }

    private void setHapticClickListener(View view, Runnable action) {
        view.setOnClickListener(clickedView -> {
            clickedView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            action.run();
        });
    }

    private void restoreProofreadBar() {
        if (visibleProofreadText == null) {
            setProofreadBarVisible(false);
            return;
        }
        setProofreadBarVisible(true);
        String previewText = visibleSuggestion
                ? ProofreadPreview.visibleText(visibleProofreadText)
                : visibleProofreadText;
        suggestionText.setText(previewText);
        suggestionText.setTextColor(getContext().getColor(R.color.key_text));
        suggestionText.setContentDescription(
                visibleSuggestion
                        ? "Proofreading suggestion. Line breaks are shown as return arrows and "
                                + "tabs as tab arrows: " + previewText
                        : visibleProofreadText
        );
        applyButton.setVisibility(visibleSuggestion ? VISIBLE : GONE);
        applyButton.setEnabled(visibleSuggestion);
        dismissButton.setVisibility(visibleDismiss ? VISIBLE : GONE);
    }

    private void setProofreadBarVisible(boolean visible) {
        if (suggestionBar == null) {
            return;
        }
        LayoutParams params = (LayoutParams) suggestionBar.getLayoutParams();
        if (params != null) {
            params.height = visible ? dp(48) : 0;
            suggestionBar.setLayoutParams(params);
        }
        suggestionBar.setVisibility(visible ? VISIBLE : GONE);
        suggestionBar.requestLayout();
    }

    private Button compactButton(String label, String description) {
        Button button = new Button(getContext());
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(label.length() > 1 ? 13f : 20f);
        button.setTextColor(getContext().getColor(R.color.key_text));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(0, 0, 0, 0);
        button.setStateListAnimator(null);
        button.setBackgroundResource(R.drawable.key_special_background);
        button.setContentDescription(description);
        return button;
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
            case PROOFREAD:
                return "Proofread with local Gemma 4";
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
