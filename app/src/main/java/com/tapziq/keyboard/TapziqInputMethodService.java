package com.tapziq.keyboard;

import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.IBinder;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

public final class TapziqInputMethodService extends InputMethodService {
    private KeyboardPanel keyboardPanel;
    private KeyboardLayouts.Mode mode = KeyboardLayouts.Mode.LETTERS;
    private boolean shifted;
    private EditorInfo editorInfo;

    @Override
    public View onCreateInputView() {
        keyboardPanel = new KeyboardPanel(this, this::handleKey);
        renderKeyboard();
        return keyboardPanel;
    }

    @Override
    public void onStartInputView(EditorInfo attribute, boolean restarting) {
        super.onStartInputView(attribute, restarting);
        editorInfo = attribute;
        mode = EditorBehavior.initialMode(attribute.inputType);
        shifted = shouldStartShifted(attribute);
        renderKeyboard();
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        editorInfo = null;
        mode = KeyboardLayouts.Mode.LETTERS;
        shifted = false;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    private void handleKey(KeyboardLayouts.KeySpec key) {
        switch (key.action) {
            case TEXT:
                commitText(key.text);
                if (mode == KeyboardLayouts.Mode.LETTERS && shifted) {
                    shifted = false;
                    renderKeyboard();
                }
                break;
            case SPACE:
                commitText(" ");
                break;
            case SHIFT:
                shifted = !shifted;
                renderKeyboard();
                break;
            case DELETE:
                deleteBeforeCursor();
                break;
            case ENTER:
                pressEnter();
                break;
            case LETTERS:
                mode = KeyboardLayouts.Mode.LETTERS;
                shifted = false;
                renderKeyboard();
                break;
            case NUMBERS:
                mode = KeyboardLayouts.Mode.NUMBERS;
                shifted = false;
                renderKeyboard();
                break;
            case SYMBOLS:
                mode = KeyboardLayouts.Mode.SYMBOLS;
                shifted = false;
                renderKeyboard();
                break;
            case NEXT_IME:
                switchKeyboard();
                break;
            case SPACER:
                break;
        }
    }

    private void commitText(String text) {
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) {
            connection.commitText(text, 1);
        }
    }

    private void deleteBeforeCursor() {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return;
        }

        CharSequence selection = connection.getSelectedText(0);
        if (selection != null && selection.length() > 0) {
            connection.commitText("", 1);
            return;
        }

        if (!connection.deleteSurroundingTextInCodePoints(1, 0)) {
            sendKey(connection, KeyEvent.KEYCODE_DEL);
        }
    }

    private void pressEnter() {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return;
        }

        EditorInfo current = editorInfo != null ? editorInfo : getCurrentInputEditorInfo();
        if (current == null) {
            sendKey(connection, KeyEvent.KEYCODE_ENTER);
            return;
        }

        int action = EditorBehavior.actionableImeAction(current.imeOptions);
        if (action != EditorInfo.IME_ACTION_NONE && connection.performEditorAction(action)) {
            return;
        }

        if (EditorBehavior.isMultiline(current.inputType)) {
            connection.commitText("\n", 1);
        } else {
            sendKey(connection, KeyEvent.KEYCODE_ENTER);
        }
    }

    private void sendKey(InputConnection connection, int keyCode) {
        long now = System.currentTimeMillis();
        connection.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        connection.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    private boolean shouldStartShifted(EditorInfo info) {
        if ((info.inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) {
            return false;
        }
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) {
            return false;
        }
        InputConnection connection = getCurrentInputConnection();
        return connection != null && connection.getCursorCapsMode(info.inputType) != 0;
    }

    @SuppressWarnings("deprecation")
    private void switchKeyboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToNextInputMethod(false);
            return;
        }
        InputMethodManager manager = getSystemService(InputMethodManager.class);
        IBinder token = inputMethodWindowToken();
        if (manager != null && token != null) {
            manager.switchToNextInputMethod(token, false);
        }
    }

    @SuppressWarnings("deprecation")
    private boolean shouldOfferImeSwitch() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return shouldOfferSwitchingToNextInputMethod();
        }
        InputMethodManager manager = getSystemService(InputMethodManager.class);
        IBinder token = inputMethodWindowToken();
        return manager != null
                && token != null
                && manager.shouldOfferSwitchingToNextInputMethod(token);
    }

    private IBinder inputMethodWindowToken() {
        if (getWindow() == null) {
            return null;
        }
        Window window = getWindow().getWindow();
        return window == null ? null : window.getAttributes().token;
    }

    private void renderKeyboard() {
        if (keyboardPanel == null) {
            return;
        }
        EditorInfo current = editorInfo != null ? editorInfo : getCurrentInputEditorInfo();
        String enterLabel = current == null ? "↵" : EditorBehavior.enterLabel(current.imeOptions);
        keyboardPanel.render(
                mode,
                shifted,
                shouldOfferImeSwitch(),
                enterLabel
        );
    }
}
