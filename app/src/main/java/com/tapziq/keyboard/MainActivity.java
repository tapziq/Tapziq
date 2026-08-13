package com.tapziq.keyboard;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

public final class MainActivity extends Activity {
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(getColor(R.color.screen_background));
        scrollView.setFitsSystemWindows(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(30), dp(24), dp(32));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_keyboard);
        icon.setContentDescription(null);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(62), dp(62));
        iconParams.bottomMargin = dp(16);
        content.addView(icon, iconParams);

        content.addView(text(
                getString(R.string.setup_title),
                30,
                R.color.primary_text,
                Typeface.BOLD
        ));
        TextView intro = text(
                getString(R.string.setup_intro),
                17,
                R.color.secondary_text,
                Typeface.NORMAL
        );
        LinearLayout.LayoutParams introParams = wrapParams();
        introParams.topMargin = dp(6);
        introParams.bottomMargin = dp(22);
        content.addView(intro, introParams);

        statusView = text("", 15, R.color.primary_text, Typeface.BOLD);
        statusView.setPadding(dp(14), dp(11), dp(14), dp(11));
        statusView.setBackground(roundedBackground(R.color.surface, 10));
        LinearLayout.LayoutParams statusParams = matchParams();
        statusParams.bottomMargin = dp(12);
        content.addView(statusView, statusParams);

        Button enableButton = primaryButton(getString(R.string.enable_keyboard));
        enableButton.setOnClickListener(view -> startActivity(
                new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        ));
        content.addView(enableButton, buttonParams());

        Button chooseButton = secondaryButton(getString(R.string.choose_keyboard));
        chooseButton.setOnClickListener(view -> {
            InputMethodManager manager = getSystemService(InputMethodManager.class);
            if (manager != null) {
                manager.showInputMethodPicker();
            }
        });
        LinearLayout.LayoutParams chooseParams = buttonParams();
        chooseParams.topMargin = dp(10);
        chooseParams.bottomMargin = dp(22);
        content.addView(chooseButton, chooseParams);

        content.addView(sectionTitle(getString(R.string.try_title)));
        EditText testField = new EditText(this);
        testField.setId(R.id.test_field);
        testField.setHint(R.string.try_hint);
        testField.setTextColor(getColor(R.color.primary_text));
        testField.setHintTextColor(getColor(R.color.secondary_text));
        testField.setTextSize(17);
        testField.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        testField.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE
        );
        testField.setMinLines(4);
        testField.setPadding(dp(14), dp(12), dp(14), dp(12));
        testField.setBackground(roundedBackground(R.color.surface, 10));
        LinearLayout.LayoutParams fieldParams = matchParams();
        fieldParams.topMargin = dp(9);
        fieldParams.bottomMargin = dp(24);
        content.addView(testField, fieldParams);

        content.addView(sectionTitle(getString(R.string.privacy_title)));
        TextView privacy = text(
                getString(R.string.privacy_body),
                15,
                R.color.secondary_text,
                Typeface.NORMAL
        );
        LinearLayout.LayoutParams privacyParams = wrapParams();
        privacyParams.topMargin = dp(7);
        content.addView(privacy, privacyParams);

        TextView warning = text(
                getString(R.string.system_warning_note),
                13,
                R.color.secondary_text,
                Typeface.NORMAL
        );
        LinearLayout.LayoutParams warningParams = wrapParams();
        warningParams.topMargin = dp(13);
        content.addView(warning, warningParams);

        setContentView(scrollView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        if (statusView == null) {
            return;
        }
        if (isCurrentKeyboard()) {
            statusView.setText(R.string.status_selected);
        } else if (isKeyboardEnabled()) {
            statusView.setText(R.string.status_enabled);
        } else {
            statusView.setText(R.string.status_not_enabled);
        }
    }

    private boolean isKeyboardEnabled() {
        InputMethodManager manager = getSystemService(InputMethodManager.class);
        if (manager == null) {
            return false;
        }
        ComponentName ours = new ComponentName(this, TapziqInputMethodService.class);
        List<InputMethodInfo> enabled = manager.getEnabledInputMethodList();
        for (InputMethodInfo info : enabled) {
            ComponentName candidate = new ComponentName(info.getPackageName(), info.getServiceName());
            if (ours.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean isCurrentKeyboard() {
        String current = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD
        );
        ComponentName selected = current == null ? null : ComponentName.unflattenFromString(current);
        return new ComponentName(this, TapziqInputMethodService.class).equals(selected);
    }

    private TextView sectionTitle(String value) {
        return text(value, 19, R.color.primary_text, Typeface.BOLD);
    }

    private TextView text(String value, int sizeSp, int colorResource, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(getColor(colorResource));
        view.setTypeface(Typeface.create("sans", style));
        view.setLineSpacing(0, 1.12f);
        return view;
    }

    private Button primaryButton(String label) {
        Button button = baseButton(label);
        button.setTextColor(getColor(R.color.key_text_active));
        button.setBackgroundTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_pressed}, new int[]{}},
                new int[]{getColor(R.color.accent_pressed), getColor(R.color.accent)}
        ));
        return button;
    }

    private Button secondaryButton(String label) {
        Button button = baseButton(label);
        button.setTextColor(getColor(R.color.primary_text));
        button.setBackgroundTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_pressed}, new int[]{}},
                new int[]{getColor(R.color.key_pressed), getColor(R.color.surface)}
        ));
        return button;
    }

    private Button baseButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTypeface(Typeface.create("sans", Typeface.BOLD));
        button.setMinHeight(dp(52));
        return button;
    }

    private GradientDrawable roundedBackground(int colorResource, int radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(getColor(colorResource));
        background.setCornerRadius(dp(radiusDp));
        return background;
    }

    private LinearLayout.LayoutParams buttonParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
        );
    }

    private LinearLayout.LayoutParams matchParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams wrapParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
