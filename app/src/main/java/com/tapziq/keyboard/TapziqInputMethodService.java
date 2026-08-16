package com.tapziq.keyboard;

import android.inputmethodservice.InputMethodService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import java.util.concurrent.TimeUnit;

public final class TapziqInputMethodService extends InputMethodService {
    private static final long RESULT_PROOFREAD_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(2);
    private static final long AUTOCORRECT_DELAY_MS = 600L;
    private KeyboardPanel keyboardPanel;
    private KeyboardLayouts.Mode mode = KeyboardLayouts.Mode.LETTERS;
    private boolean shifted;
    private EditorInfo editorInfo;
    private ProofreadTarget proofreadTarget;
    private String proofreadSuggestion;
    private int proofreadSessionId;
    private EditorIdentity proofreadEditor;
    private volatile Handler proofreadTimeoutHandler;
    private final Runnable proofreadTimeout = this::clearProofreadState;
    private final Runnable pendingAutocorrect = this::startPendingAutocorrect;
    private final SharedPreferences.OnSharedPreferenceChangeListener autocorrectSettingsListener =
            (preferences, key) -> {
                if (!AutocorrectSettings.isEnabledPreference(key)
                        || AutocorrectSettings.isEnabled(this)) {
                    return;
                }
                Handler handler = proofreadTimeoutHandler;
                if (handler != null) {
                    handler.post(this::disableAutocorrectImmediately);
                }
            };
    private GemmaModelStore autocorrectModelStore;
    private GemmaProofreader autocorrectProofreader;
    private AutocorrectTarget autocorrectTarget;
    private InputConnection autocorrectConnection;
    private EditorInfo autocorrectEditorInfo;
    private AutocorrectEdit autocorrectUndo;
    private String pendingAutocorrectBoundary;
    private int autocorrectOperationId;
    private boolean showingAutocorrectNotice;

    @Override
    public void onCreate() {
        super.onCreate();
        proofreadTimeoutHandler = new Handler(Looper.getMainLooper());
        AutocorrectSettings.registerListener(this, autocorrectSettingsListener);
        ProofreadSession.setListener(this::onProofreadSessionChanged);
    }

    @Override
    public View onCreateInputView() {
        keyboardPanel = new KeyboardPanel(this, new KeyboardPanel.Listener() {
            @Override
            public void onKey(KeyboardLayouts.KeySpec key) {
                handleKey(key);
            }

            @Override
            public void onApplyProofread() {
                applyProofreadSuggestion();
            }

            @Override
            public void onDismissProofread() {
                clearProofreadState();
            }
        });
        renderKeyboard();
        return keyboardPanel;
    }

    @Override
    public void onStartInputView(EditorInfo attribute, boolean restarting) {
        super.onStartInputView(attribute, restarting);
        cancelAutocorrectRequest();
        autocorrectUndo = null;
        editorInfo = attribute;
        mode = EditorBehavior.initialMode(attribute.inputType);
        shifted = shouldStartShifted(attribute);
        boolean activeSession = proofreadSessionId != 0
                && ProofreadSession.isActive(proofreadSessionId);
        boolean showingSuggestion = proofreadSuggestion != null;
        // Some Android variants briefly restart the IME against a TYPE_NULL
        // editor while the foreground proofreader Activity owns the request.
        // Keep that active handoff intact; delivery revalidates the real editor
        // after the Activity stops. An already surfaced suggestion, however,
        // must continue to match its exact source editor.
        if ((!activeSession && !showingSuggestion)
                || (showingSuggestion && !currentProofreadContextMatches())) {
            clearProofreadState();
        }
        renderKeyboard();
        consumeProofreadResult();
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        cancelAutocorrectRequest();
        closeAutocorrectProofreader();
        autocorrectUndo = null;
        editorInfo = null;
        mode = KeyboardLayouts.Mode.LETTERS;
        shifted = false;
        if (proofreadSessionId == 0) {
            clearProofreadState();
        }
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        cancelAutocorrectRequest();
        closeAutocorrectProofreader();
        autocorrectUndo = null;
        hideAutocorrectNotice();
        super.onFinishInputView(finishingInput);
    }

    @Override
    public void onWindowHidden() {
        cancelAutocorrectRequest();
        closeAutocorrectProofreader();
        autocorrectUndo = null;
        hideAutocorrectNotice();
        super.onWindowHidden();
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        consumeProofreadResult();
    }

    private void handleKey(KeyboardLayouts.KeySpec key) {
        if (key.action == KeyboardLayouts.Action.DELETE && undoLastAutocorrect()) {
            return;
        }
        cancelAutocorrectRequest();
        autocorrectUndo = null;
        hideAutocorrectNotice();

        switch (key.action) {
            case TEXT:
                if (commitText(key.text) && AutocorrectTarget.isSupportedBoundary(key.text)) {
                    scheduleAutocorrect(key.text);
                }
                if (mode == KeyboardLayouts.Mode.LETTERS && shifted) {
                    shifted = false;
                    renderKeyboard();
                }
                break;
            case SPACE:
                if (commitText(" ")) {
                    scheduleAutocorrect(" ");
                }
                break;
            case SHIFT:
                shifted = !shifted;
                renderKeyboard();
                break;
            case DELETE:
                deleteBeforeCursor();
                break;
            case ENTER:
                if (pressEnter()) {
                    scheduleAutocorrect("\n");
                }
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
            case PROOFREAD:
                startProofread();
                break;
            case SPACER:
                break;
        }
    }

    private boolean commitText(String text) {
        InputConnection connection = getCurrentInputConnection();
        return connection != null && connection.commitText(text, 1);
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

    private boolean pressEnter() {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return false;
        }

        EditorInfo current = editorInfo != null ? editorInfo : getCurrentInputEditorInfo();
        if (current == null) {
            sendKey(connection, KeyEvent.KEYCODE_ENTER);
            return false;
        }

        int action = EditorBehavior.actionableImeAction(current.imeOptions);
        if (action != EditorInfo.IME_ACTION_NONE && connection.performEditorAction(action)) {
            return false;
        }

        if (EditorBehavior.isMultiline(current.inputType)) {
            return connection.commitText("\n", 1);
        } else {
            sendKey(connection, KeyEvent.KEYCODE_ENTER);
            return false;
        }
    }

    private void sendKey(InputConnection connection, int keyCode) {
        long now = System.currentTimeMillis();
        connection.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        connection.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    private void scheduleAutocorrect(String committedBoundary) {
        Handler handler = proofreadTimeoutHandler;
        if (handler == null
                || !isInputViewShown()
                || !AutocorrectSettings.isEnabled(this)
                || !EditorBehavior.supportsProofreading(editorInfo)
                || proofreadSessionId != 0
                || proofreadSuggestion != null) {
            return;
        }
        pendingAutocorrectBoundary = committedBoundary;
        handler.removeCallbacks(pendingAutocorrect);
        handler.postDelayed(pendingAutocorrect, AUTOCORRECT_DELAY_MS);
    }

    private void startPendingAutocorrect() {
        String boundary = pendingAutocorrectBoundary;
        pendingAutocorrectBoundary = null;
        if (boundary == null
                || !isInputViewShown()
                || !AutocorrectSettings.isEnabled(this)
                || !EditorBehavior.supportsProofreading(editorInfo)
                || proofreadSessionId != 0
                || proofreadSuggestion != null) {
            return;
        }

        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return;
        }
        ExtractedText extracted = connection.getExtractedText(extractedTextRequest(), 0);
        AutocorrectTarget.Capture capture = AutocorrectTarget.capture(extracted, boundary);
        if (!capture.succeeded()) {
            return;
        }

        if (autocorrectModelStore == null) {
            Handler handler = proofreadTimeoutHandler;
            if (handler == null) {
                return;
            }
            autocorrectModelStore = new GemmaModelStore(this, handler::post);
        }
        java.io.File modelFile = autocorrectModelStore.readyModelFile();
        if (modelFile == null) {
            return;
        }

        EditorInfo sourceEditorInfo = editorInfo;
        if (sourceEditorInfo == null) {
            return;
        }
        if (autocorrectProofreader == null) {
            Handler handler = proofreadTimeoutHandler;
            if (handler == null) {
                return;
            }
            autocorrectProofreader = new GemmaProofreader(handler::post, true);
        }

        autocorrectTarget = capture.target;
        autocorrectConnection = connection;
        autocorrectEditorInfo = sourceEditorInfo;
        int operationId = autocorrectOperationId;
        try {
            autocorrectProofreader.autocorrect(
                    modelFile,
                    capture.target,
                    new GemmaProofreader.InferenceCallback() {
                        @Override
                        public void onSuggestion(String suggestion) {
                            applyAutocorrectResult(
                                    operationId,
                                    capture.target,
                                    connection,
                                    sourceEditorInfo,
                                    suggestion
                            );
                        }

                        @Override
                        public void onFailure(Throwable error) {
                            // Autocorrect is opportunistic. A safe no-op, cancellation,
                            // unavailable model, or malformed output must never interrupt typing.
                            finishAutocorrectRequest(operationId);
                        }
                    }
            );
        } catch (RuntimeException error) {
            finishAutocorrectRequest(operationId);
        }
    }

    private void applyAutocorrectResult(
            int operationId,
            AutocorrectTarget target,
            InputConnection sourceConnection,
            EditorInfo sourceEditorInfo,
            String suggestion
    ) {
        if (operationId != autocorrectOperationId
                || target != autocorrectTarget
                || sourceConnection != autocorrectConnection
                || sourceEditorInfo != autocorrectEditorInfo
                || !isInputViewShown()
                || !AutocorrectSettings.isEnabled(this)
                || !EditorBehavior.supportsProofreading(editorInfo)
                || proofreadSessionId != 0
                || proofreadSuggestion != null) {
            finishAutocorrectRequest(operationId);
            return;
        }

        InputConnection connection = getCurrentInputConnection();
        AutocorrectEdit.Validation validation = AutocorrectEdit.validate(target, suggestion);
        if (connection == null
                || connection != sourceConnection
                || editorInfo != sourceEditorInfo
                || !validation.succeeded()
                || !EditorBehavior.supportsProofreading(editorInfo)) {
            finishAutocorrectRequest(operationId);
            return;
        }

        AutocorrectEdit edit = validation.edit;
        AutocorrectApplier.Result result = AutocorrectApplier.apply(connection, edit);
        finishAutocorrectRequest(operationId);
        if (result == AutocorrectApplier.Result.APPLIED) {
            autocorrectUndo = edit;
            showingAutocorrectNotice = true;
            if (keyboardPanel != null) {
                keyboardPanel.showProofreadMessage(
                        getString(R.string.autocorrect_applied),
                        true
                );
            }
        }
    }

    private boolean undoLastAutocorrect() {
        AutocorrectEdit edit = autocorrectUndo;
        if (edit == null) {
            return false;
        }
        cancelAutocorrectRequest();
        InputConnection connection = getCurrentInputConnection();
        AutocorrectApplier.Result result = connection == null
                ? AutocorrectApplier.Result.STALE
                : AutocorrectApplier.undo(connection, edit);
        autocorrectUndo = null;
        if (result != AutocorrectApplier.Result.APPLIED) {
            hideAutocorrectNotice();
            return false;
        }
        showingAutocorrectNotice = true;
        if (keyboardPanel != null) {
            keyboardPanel.showProofreadMessage(
                    getString(R.string.autocorrect_reverted),
                    true
            );
        }
        return true;
    }

    private void finishAutocorrectRequest(int operationId) {
        if (operationId != autocorrectOperationId) {
            return;
        }
        autocorrectTarget = null;
        autocorrectConnection = null;
        autocorrectEditorInfo = null;
    }

    private void cancelAutocorrectRequest() {
        autocorrectOperationId++;
        pendingAutocorrectBoundary = null;
        Handler handler = proofreadTimeoutHandler;
        if (handler != null) {
            handler.removeCallbacks(pendingAutocorrect);
        }
        autocorrectTarget = null;
        autocorrectConnection = null;
        autocorrectEditorInfo = null;
        if (autocorrectProofreader != null) {
            autocorrectProofreader.cancel();
        }
    }

    private void hideAutocorrectNotice() {
        if (!showingAutocorrectNotice) {
            return;
        }
        showingAutocorrectNotice = false;
        if (keyboardPanel != null) {
            keyboardPanel.hideProofreadMessage();
        }
    }

    private void closeAutocorrectProofreader() {
        GemmaProofreader proofreader = autocorrectProofreader;
        autocorrectProofreader = null;
        if (proofreader != null) {
            proofreader.close();
        }
    }

    private void disableAutocorrectImmediately() {
        if (AutocorrectSettings.isEnabled(this)) {
            return;
        }
        cancelAutocorrectRequest();
        closeAutocorrectProofreader();
        autocorrectUndo = null;
        hideAutocorrectNotice();
    }

    private void startProofread() {
        if (proofreadSessionId != 0) {
            return;
        }
        cancelAutocorrectRequest();
        autocorrectUndo = null;
        hideAutocorrectNotice();
        if (!EditorBehavior.supportsProofreading(editorInfo)) {
            showProofreadMessage(getString(R.string.proofread_secure_field));
            return;
        }

        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            showProofreadMessage(getString(R.string.proofread_no_text));
            return;
        }

        ProofreadTarget.Capture capture = captureTarget(connection);
        if (!capture.succeeded()) {
            showProofreadMessage(messageFor(capture.failure));
            return;
        }

        proofreadTarget = capture.target;
        proofreadEditor = EditorIdentity.capture(
                getCurrentInputBinding(),
                editorInfo,
                connection
        );
        if (proofreadEditor == null) {
            proofreadTarget = null;
            showProofreadMessage(getString(R.string.proofread_failed));
            return;
        }
        proofreadSuggestion = null;
        proofreadSessionId = ProofreadSession.begin(proofreadTarget.text);
        keyboardPanel.showProofreadMessage(getString(R.string.proofread_opening), false);

        int sessionId = proofreadSessionId;
        GemmaProofreader proofreader = autocorrectProofreader;
        autocorrectProofreader = null;
        if (proofreader != null) {
            proofreader.close(() -> launchProofreadActivity(sessionId));
        } else {
            launchProofreadActivity(sessionId);
        }
    }

    private void launchProofreadActivity(int sessionId) {
        if (sessionId == 0
                || sessionId != proofreadSessionId
                || !ProofreadSession.isActive(sessionId)
                || !currentProofreadContextMatches()) {
            if (sessionId == proofreadSessionId) {
                ProofreadSession.cancel(sessionId);
                showProofreadMessage(getString(R.string.proofread_text_changed));
            }
            return;
        }
        Intent intent = new Intent(this, ProofreadActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .putExtra(ProofreadActivity.EXTRA_SESSION_ID, proofreadSessionId);
        try {
            startActivity(intent);
        } catch (RuntimeException error) {
            ProofreadSession.clear();
            proofreadTarget = null;
            proofreadEditor = null;
            proofreadSessionId = 0;
            showProofreadMessage(getString(R.string.proofread_failed));
        }
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        cancelAutocorrectRequest();
        autocorrectUndo = null;
        editorInfo = attribute;
    }

    private void consumeProofreadResult() {
        int sessionId = proofreadSessionId;
        ProofreadSession.Result result = ProofreadSession.peekDeliverableResult(sessionId);
        if (result == null || result.id != sessionId || keyboardPanel == null
                || editorInfo == null) {
            return;
        }
        if (proofreadTarget == null || proofreadEditor == null) {
            ProofreadSession.takeDeliverableResult(sessionId);
            clearProofreadState();
            return;
        }
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return;
        }
        if (!EditorBehavior.supportsProofreading(editorInfo)
                && getPackageName().equals(editorInfo.packageName)) {
            // The bridge Activity can transiently become the IME's current
            // non-text editor before Android restores the source field.
            return;
        }
        if (!EditorBehavior.supportsProofreading(editorInfo)
                || !proofreadEditor.matches(
                        getCurrentInputBinding(),
                        editorInfo,
                        connection
                )
                || !targetStillMatches(connection, proofreadTarget)) {
            ProofreadSession.takeDeliverableResult(sessionId);
            proofreadSessionId = 0;
            proofreadTarget = null;
            proofreadEditor = null;
            proofreadSuggestion = null;
            showProofreadMessage(getString(R.string.proofread_text_changed));
            return;
        }
        result = ProofreadSession.takeDeliverableResult(sessionId);
        if (result == null) {
            clearProofreadState();
            return;
        }
        cancelProofreadExpiry();
        proofreadSessionId = 0;
        if (result.suggestion != null) {
            proofreadSuggestion = result.suggestion;
            if (result.suggestion.equals(proofreadTarget.text)) {
                proofreadSuggestion = null;
                proofreadTarget = null;
                proofreadEditor = null;
                showProofreadMessage(getString(R.string.proofread_no_changes));
            } else {
                showingAutocorrectNotice = false;
                keyboardPanel.showSuggestion(result.suggestion);
                scheduleProofreadExpiry(RESULT_PROOFREAD_TIMEOUT_MS);
            }
        } else {
            proofreadSuggestion = null;
            proofreadTarget = null;
            proofreadEditor = null;
            showProofreadMessage(
                    result.message != null ? result.message : getString(R.string.proofread_failed)
            );
        }
    }

    private void applyProofreadSuggestion() {
        if (proofreadTarget == null || proofreadSuggestion == null) {
            clearProofreadState();
            return;
        }
        if (!EditorBehavior.supportsProofreading(editorInfo)) {
            clearProofreadState();
            return;
        }

        InputConnection connection = getCurrentInputConnection();
        if (connection == null
                || proofreadEditor == null
                || !proofreadEditor.matches(
                        getCurrentInputBinding(),
                        editorInfo,
                        connection
                )
                || !targetStillMatches(connection, proofreadTarget)) {
            proofreadTarget = null;
            proofreadEditor = null;
            proofreadSuggestion = null;
            showProofreadMessage(getString(R.string.proofread_text_changed));
            return;
        }

        boolean replaced;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            replaced = connection.replaceText(
                    proofreadTarget.start(),
                    proofreadTarget.end(),
                    proofreadSuggestion,
                    1,
                    null
            );
        } else {
            LegacyProofreadApplier.Result result = LegacyProofreadApplier.apply(
                    connection,
                    proofreadTarget,
                    proofreadSuggestion
            );
            if (result == LegacyProofreadApplier.Result.STALE) {
                showProofreadMessage(getString(R.string.proofread_text_changed));
                return;
            }
            replaced = result == LegacyProofreadApplier.Result.APPLIED;
        }
        if (!replaced) {
            showProofreadMessage(getString(R.string.proofread_failed));
            return;
        }
        clearProofreadState();
    }

    private ProofreadTarget.Capture captureTarget(InputConnection connection) {
        ExtractedText extracted = connection.getExtractedText(extractedTextRequest(), 0);
        if (extracted != null && extracted.text != null) {
            boolean completeDocument = extracted.startOffset == 0
                    && extracted.partialStartOffset < 0;
            return ProofreadTarget.fromExtracted(
                    extracted.text,
                    extracted.startOffset,
                    extracted.selectionStart,
                    extracted.selectionEnd,
                    completeDocument
            );
        }
        return ProofreadTarget.Capture.failure(ProofreadTarget.Failure.INVALID_SELECTION);
    }

    private boolean targetStillMatches(InputConnection connection, ProofreadTarget target) {
        ExtractedText current = connection.getExtractedText(extractedTextRequest(), 0);
        return target.matches(current);
    }

    private ExtractedTextRequest extractedTextRequest() {
        ExtractedTextRequest request = new ExtractedTextRequest();
        request.flags = InputConnection.GET_TEXT_WITH_STYLES;
        request.hintMaxChars = ProofreadTarget.MAX_SNAPSHOT_CHARACTERS + 1;
        request.hintMaxLines = 20;
        return request;
    }

    private String messageFor(ProofreadTarget.Failure failure) {
        switch (failure) {
            case TOO_LONG:
                return getString(R.string.proofread_too_long);
            case INVALID_SELECTION:
                return getString(R.string.proofread_select_text);
            case NO_TEXT:
            default:
                return getString(R.string.proofread_no_text);
        }
    }

    private void showProofreadMessage(String message) {
        cancelProofreadExpiry();
        proofreadTarget = null;
        proofreadEditor = null;
        proofreadSuggestion = null;
        proofreadSessionId = 0;
        showingAutocorrectNotice = false;
        if (keyboardPanel != null) {
            keyboardPanel.showProofreadMessage(message, true);
        }
    }

    private void clearProofreadState() {
        cancelProofreadExpiry();
        if (proofreadSessionId != 0) {
            ProofreadSession.cancel(proofreadSessionId);
        }
        proofreadTarget = null;
        proofreadEditor = null;
        proofreadSuggestion = null;
        proofreadSessionId = 0;
        showingAutocorrectNotice = false;
        if (keyboardPanel != null) {
            keyboardPanel.hideProofreadMessage();
        }
    }

    private boolean currentProofreadContextMatches() {
        if (proofreadTarget == null
                || proofreadEditor == null
                || editorInfo == null
                || !EditorBehavior.supportsProofreading(editorInfo)
                || !proofreadEditor.matches(
                        getCurrentInputBinding(),
                        editorInfo,
                        getCurrentInputConnection()
                )) {
            return false;
        }
        InputConnection connection = getCurrentInputConnection();
        return connection != null && targetStillMatches(connection, proofreadTarget);
    }

    private void onProofreadSessionChanged(int id) {
        Handler handler = proofreadTimeoutHandler;
        if (handler == null) {
            return;
        }
        handler.post(() -> {
            if (id != proofreadSessionId) {
                return;
            }
            if (ProofreadSession.hasResult(id)) {
                consumeProofreadResult();
            } else if (!ProofreadSession.isActive(id)) {
                clearProofreadState();
            }
        });
    }

    private void scheduleProofreadExpiry(long delayMillis) {
        if (proofreadTimeoutHandler == null) {
            return;
        }
        proofreadTimeoutHandler.removeCallbacks(proofreadTimeout);
        proofreadTimeoutHandler.postDelayed(proofreadTimeout, delayMillis);
    }

    private void cancelProofreadExpiry() {
        if (proofreadTimeoutHandler != null) {
            proofreadTimeoutHandler.removeCallbacks(proofreadTimeout);
        }
    }

    @Override
    public void onDestroy() {
        ProofreadSession.setListener(null);
        AutocorrectSettings.unregisterListener(this, autocorrectSettingsListener);
        cancelAutocorrectRequest();
        closeAutocorrectProofreader();
        if (autocorrectModelStore != null) {
            autocorrectModelStore.close();
            autocorrectModelStore = null;
        }
        autocorrectUndo = null;
        clearProofreadState();
        ProofreadSession.clear();
        cancelProofreadExpiry();
        proofreadTimeoutHandler = null;
        super.onDestroy();
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
                enterLabel,
                EditorBehavior.supportsProofreading(current)
        );
    }
}
