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

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

public final class TapziqInputMethodService extends InputMethodService {
    private static final long RESULT_PROOFREAD_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(2);
    private static final long AUTOCORRECT_DELAY_MS = 600L;
    private static final long AUTOCORRECT_LEARNING_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30);
    private static final long RECENT_AUTOCORRECTION_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(2);
    private static final long EDITOR_TAP_SETTLE_MS = 75L;
    private static final int MAX_RECENT_AUTOCORRECTIONS = 8;
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
    private final Runnable autocorrectLearningTimeout = this::clearAutocorrectLearningSession;
    private final Runnable recentAutocorrectionExpiry = this::expireRecentAutocorrections;
    private final Runnable editorTapSettled = this::settlePendingEditorTapForLearning;
    private final SharedPreferences.OnSharedPreferenceChangeListener autocorrectSettingsListener =
            (preferences, key) -> {
                boolean disabledAutocorrect = AutocorrectSettings.isEnabledPreference(key)
                        && !AutocorrectSettings.isEnabled(this);
                boolean disabledLearning = AutocorrectSettings.isLearningEnabledPreference(key)
                        && !AutocorrectSettings.isLearningEnabled(this);
                if (!disabledAutocorrect && !disabledLearning) {
                    return;
                }
                Handler handler = proofreadTimeoutHandler;
                if (handler != null) {
                    handler.post(() -> {
                        if (disabledAutocorrect) {
                            disableAutocorrectImmediately();
                        }
                        if (disabledLearning) {
                            clearAutocorrectLearningSession();
                            clearRecentAutocorrections();
                            // A request already owns a copy of the preferences that were
                            // enabled when it started, so opt-out must cancel that request too.
                            cancelAutocorrectRequest();
                        }
                    });
                }
            };
    private final SharedPreferences.OnSharedPreferenceChangeListener learningMemoryListener =
            (preferences, key) -> {
                if (!AutocorrectLearningStore.isClearPreference(key)) {
                    return;
                }
                Handler handler = proofreadTimeoutHandler;
                if (handler != null) {
                    handler.post(() -> {
                        clearAutocorrectLearningSession();
                        clearRecentAutocorrections();
                        cancelAutocorrectRequest();
                    });
                }
            };
    private GemmaModelStore autocorrectModelStore;
    private GemmaProofreader autocorrectProofreader;
    private AutocorrectTarget autocorrectTarget;
    private InputConnection autocorrectConnection;
    private EditorInfo autocorrectEditorInfo;
    private AutocorrectEdit autocorrectUndo;
    private RecentAutocorrection autocorrectUndoRecent;
    private String pendingAutocorrectBoundary;
    private int autocorrectOperationId;
    private boolean showingAutocorrectNotice;
    private boolean showingAutocorrectSuggestion;
    private boolean pendingEditorTap;
    private AutocorrectLearningStore autocorrectLearningStore;
    private AutocorrectLearningSession autocorrectLearningSession;
    private InputConnection autocorrectLearningConnection;
    private EditorInfo autocorrectLearningEditorInfo;
    private boolean autocorrectLearningSawTapziqKey;
    private boolean autocorrectLearningFromTappedCorrection;
    private boolean autocorrectLearningRejectionRecorded;
    private String autocorrectLearningLastObservedDocument;
    private String autocorrectLearningPreparedDocument;
    private int autocorrectLearningPreparedSelectionStart = -1;
    private int autocorrectLearningPreparedSelectionEnd = -1;
    private String autocorrectLearningExpectedTapziqDocument;
    private RecentAutocorrection autocorrectLearningRecentCorrection;
    private final ArrayDeque<RecentAutocorrection> recentAutocorrections = new ArrayDeque<>();
    private EditorIdentity recentAutocorrectionEditor;
    private InputConnection recentAutocorrectionConnection;
    private EditorInfo recentAutocorrectionEditorInfo;

    @Override
    public void onCreate() {
        super.onCreate();
        proofreadTimeoutHandler = new Handler(Looper.getMainLooper());
        autocorrectLearningStore = new AutocorrectLearningStore(this);
        autocorrectLearningStore.registerClearListener(learningMemoryListener);
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
                dismissProofreadSuggestionForLearning();
            }

            @Override
            public void onUseAutocorrectOriginal() {
                useAutocorrectOriginalSuggestion();
            }

            @Override
            public void onDismissAutocorrectSuggestion() {
                dismissAutocorrectSuggestionForLearning();
            }
        });
        renderKeyboard();
        return keyboardPanel;
    }

    @Override
    public void onStartInputView(EditorInfo attribute, boolean restarting) {
        super.onStartInputView(attribute, restarting);
        clearAutocorrectLearningSession();
        if (!recentAutocorrectionContextMatches(
                attribute,
                getCurrentInputConnection()
        )) {
            clearRecentAutocorrections();
        }
        cancelAutocorrectRequest();
        clearAutocorrectUndo();
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
        clearAutocorrectLearningSession();
        clearRecentAutocorrections();
        super.onFinishInput();
        cancelAutocorrectRequest();
        closeAutocorrectProofreader();
        clearAutocorrectUndo();
        editorInfo = null;
        mode = KeyboardLayouts.Mode.LETTERS;
        shifted = false;
        if (proofreadSessionId == 0) {
            clearProofreadState();
        }
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        clearAutocorrectLearningSession();
        cancelAutocorrectRequest();
        closeAutocorrectProofreader();
        clearAutocorrectUndo();
        hideAutocorrectNotice();
        super.onFinishInputView(finishingInput);
    }

    @Override
    public void onWindowHidden() {
        clearAutocorrectLearningSession();
        cancelAutocorrectRequest();
        closeAutocorrectProofreader();
        clearAutocorrectUndo();
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
        // A key can arrive during the short selection-settle delay after a word tap. Resolve the
        // tap first so the ensuing Tapziq-owned edit is never lost or mistaken for a partial word.
        settlePendingEditorTapForLearning();
        if (showingAutocorrectSuggestion && changesEditorText(key.action)) {
            hideAutocorrectSuggestion();
        }
        if (proofreadSuggestion != null && changesEditorText(key.action)) {
            // Typing over a reviewed result is the same Tapziq-owned rejection signal as ×.
            dismissProofreadSuggestionForLearning();
        }
        if (key.action == KeyboardLayouts.Action.DELETE && undoLastAutocorrect()) {
            return;
        }
        if (changesEditorText(key.action)) {
            prepareEditorMutationForLearning(getCurrentInputConnection());
        }
        if (isLearningBoundary(key)) {
            observeAutocorrectLearning(true, false);
        }
        cancelAutocorrectRequest();
        clearAutocorrectUndo();
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
        expectTapziqReplacement(connection, text);
        boolean committed = connection != null && connection.commitText(text, 1);
        if (committed) {
            observeEditorMutationForLearning(connection);
        }
        return committed;
    }

    private void deleteBeforeCursor() {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return;
        }

        CharSequence selection = connection.getSelectedText(0);
        if (selection != null && selection.length() > 0) {
            expectTapziqReplacement(connection, "");
            if (connection.commitText("", 1)) {
                observeEditorMutationForLearning(connection);
            }
            return;
        }

        expectTapziqDeleteBeforeCursor(connection);
        boolean deleted = connection.deleteSurroundingTextInCodePoints(1, 0);
        if (!deleted) {
            sendKey(connection, KeyEvent.KEYCODE_DEL);
            // sendKeyEvent is the owned fallback for editors that reject surrounding-text
            // deletion. Observe its resulting document just like the direct path.
            observeEditorMutationForLearning(connection);
        } else {
            observeEditorMutationForLearning(connection);
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
            expectTapziqReplacement(connection, "\n");
            boolean committed = connection.commitText("\n", 1);
            if (committed) {
                observeEditorMutationForLearning(connection);
            }
            return committed;
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

    private static boolean changesEditorText(KeyboardLayouts.Action action) {
        return action == KeyboardLayouts.Action.TEXT
                || action == KeyboardLayouts.Action.SPACE
                || action == KeyboardLayouts.Action.DELETE
                || action == KeyboardLayouts.Action.ENTER;
    }

    private static boolean isLearningBoundary(KeyboardLayouts.KeySpec key) {
        return key.action == KeyboardLayouts.Action.SPACE
                || key.action == KeyboardLayouts.Action.ENTER
                || (key.action == KeyboardLayouts.Action.TEXT
                        && AutocorrectTarget.isSupportedBoundary(key.text));
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
        java.util.List<AutocorrectLearningMemory.Entry> learningPreferences =
                AutocorrectSettings.isLearningEnabled(this)
                        && autocorrectLearningStore != null
                        ? autocorrectLearningStore.relevantTo(capture.target.text())
                        : java.util.Collections.emptyList();
        boolean usedLearningPreferences = !learningPreferences.isEmpty();
        long learningClearGeneration = usedLearningPreferences
                ? autocorrectLearningStore.clearGeneration()
                : 0L;
        int operationId = autocorrectOperationId;
        try {
            autocorrectProofreader.autocorrect(
                    modelFile,
                    capture.target,
                    learningPreferences,
                    new GemmaProofreader.InferenceCallback() {
                        @Override
                        public void onSuggestion(String suggestion) {
                            applyAutocorrectResult(
                                    operationId,
                                    capture.target,
                                    connection,
                                    sourceEditorInfo,
                                    usedLearningPreferences,
                                    learningClearGeneration,
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
            boolean usedLearningPreferences,
            long learningClearGeneration,
            String suggestion
    ) {
        if (operationId != autocorrectOperationId
                || target != autocorrectTarget
                || sourceConnection != autocorrectConnection
                || sourceEditorInfo != autocorrectEditorInfo
                || !isInputViewShown()
                || !AutocorrectSettings.isEnabled(this)
                || (usedLearningPreferences && !AutocorrectSettings.isLearningEnabled(this))
                || (usedLearningPreferences
                        && (autocorrectLearningStore == null
                                || learningClearGeneration
                                        != autocorrectLearningStore.clearGeneration()))
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
        if (AutocorrectSettings.isLearningEnabled(this)
                && autocorrectLearningStore != null
                && autocorrectLearningStore.rejects(edit)) {
            finishAutocorrectRequest(operationId);
            return;
        }
        AutocorrectApplier.Result result = AutocorrectApplier.apply(connection, edit);
        finishAutocorrectRequest(operationId);
        if (result.textApplied()) {
            autocorrectUndo = edit;
            // Tapziq owns the candidate bar, so editor support for CorrectionInfo is optional.
            autocorrectUndoRecent = trackRecentAutocorrection(edit);
            showingAutocorrectNotice = true;
            if (keyboardPanel != null) {
                keyboardPanel.showProofreadMessage(
                        getString(autocorrectUndoRecent == null
                                ? R.string.autocorrect_applied
                                : R.string.autocorrect_applied_review),
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
        RecentAutocorrection undoneRecent = autocorrectUndoRecent;
        cancelAutocorrectRequest();
        InputConnection connection = getCurrentInputConnection();
        AutocorrectApplier.Result result = connection == null
                ? AutocorrectApplier.Result.STALE
                : AutocorrectApplier.undo(connection, edit);
        clearAutocorrectUndo();
        if (!result.textApplied()) {
            hideAutocorrectNotice();
            return false;
        }
        removeRecentAutocorrection(undoneRecent);
        boolean rejectionRemembered = beginAutocorrectLearning(edit, connection);
        showingAutocorrectNotice = true;
        if (keyboardPanel != null) {
            keyboardPanel.showProofreadMessage(
                    getString(rejectionRemembered
                            ? R.string.autocorrect_reverted_learned
                            : R.string.autocorrect_reverted),
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
        clearAutocorrectUndo();
        hideAutocorrectNotice();
    }

    private void dismissProofreadSuggestionForLearning() {
        ProofreadTarget target = proofreadTarget;
        String suggestion = proofreadSuggestion;
        InputConnection connection = getCurrentInputConnection();
        ExtractedText current = completeLearningSnapshot(connection);
        if (target != null
                && suggestion != null
                && AutocorrectSettings.isLearningEnabled(this)
                && EditorBehavior.supportsProofreading(editorInfo)
                && connection != null
                && proofreadEditor != null
                && proofreadEditor.matches(
                        getCurrentInputBinding(),
                        editorInfo,
                        connection
                )
                && current != null
                && target.matchesDocument(current)
                && autocorrectLearningStore != null) {
            AutocorrectLearning.Feedback feedback = AutocorrectLearning.fromSuggestion(
                    target.text,
                    suggestion
            );
            if (feedback != null) {
                autocorrectLearningStore.recordRejection(feedback);
                beginAutocorrectLearningSession(
                        feedback,
                        target.start() + feedback.sourceStart,
                        target.start() + feedback.sourceEnd,
                        current,
                        connection,
                        false,
                        true,
                        null
                );
            }
        }
        clearProofreadState();
    }

    private boolean beginAutocorrectLearning(
            AutocorrectEdit edit,
            InputConnection connection
    ) {
        if (!AutocorrectSettings.isLearningEnabled(this)
                || !EditorBehavior.supportsProofreading(editorInfo)
                || autocorrectLearningStore == null
                || connection == null) {
            return false;
        }
        AutocorrectLearning.Feedback feedback = AutocorrectLearning.fromEdit(edit);
        if (feedback == null) {
            return false;
        }
        autocorrectLearningStore.recordRejection(feedback);
        ExtractedText current = completeLearningSnapshot(connection);
        if (current == null || !edit.matches(current)) {
            return true;
        }
        beginAutocorrectLearningSession(
                feedback,
                feedback.sourceStart,
                feedback.sourceEnd,
                current,
                connection,
                false,
                true,
                null
        );
        return true;
    }

    private void beginAutocorrectLearningSession(
            AutocorrectLearning.Feedback feedback,
            int sourceStart,
            int sourceEnd,
            ExtractedText current,
            InputConnection connection,
            boolean baselineIsRejected,
            boolean rejectionAlreadyRecorded,
            RecentAutocorrection recentCorrection
    ) {
        if (connection == null || editorInfo == null) {
            return;
        }
        AutocorrectLearningSession session = AutocorrectLearningSession.begin(
                current.text.toString(),
                sourceStart,
                sourceEnd,
                feedback,
                baselineIsRejected
        );
        if (session == null) {
            return;
        }
        autocorrectLearningSession = session;
        autocorrectLearningConnection = connection;
        autocorrectLearningEditorInfo = editorInfo;
        autocorrectLearningSawTapziqKey = false;
        autocorrectLearningFromTappedCorrection = baselineIsRejected;
        autocorrectLearningRejectionRecorded = rejectionAlreadyRecorded;
        autocorrectLearningLastObservedDocument = current.text.toString();
        clearPreparedAutocorrectLearningMutation();
        autocorrectLearningRecentCorrection = recentCorrection;
        scheduleAutocorrectLearningExpiry();
    }

    private void prepareEditorMutationForLearning(InputConnection connection) {
        if (autocorrectLearningSession == null) {
            return;
        }
        if (connection == null
                || connection != autocorrectLearningConnection
                || editorInfo == null
                || editorInfo != autocorrectLearningEditorInfo) {
            clearAutocorrectLearningSession();
            return;
        }
        ExtractedText current = completeLearningSnapshot(connection);
        if (current == null
                || autocorrectLearningLastObservedDocument == null
                || !autocorrectLearningLastObservedDocument.contentEquals(current.text)) {
            // An editor-side suggestion or another unobserved mutation breaks the causal link.
            clearAutocorrectLearningSession();
            return;
        }
        autocorrectLearningPreparedDocument = current.text.toString();
        autocorrectLearningPreparedSelectionStart = current.selectionStart;
        autocorrectLearningPreparedSelectionEnd = current.selectionEnd;
        autocorrectLearningExpectedTapziqDocument = null;
    }

    private void expectTapziqReplacement(InputConnection connection, String replacement) {
        if (autocorrectLearningSession == null
                || connection == null
                || connection != autocorrectLearningConnection
                || autocorrectLearningPreparedDocument == null
                || replacement == null) {
            return;
        }
        String expected = AutocorrectLearningMutation.replacement(
                autocorrectLearningPreparedDocument,
                autocorrectLearningPreparedSelectionStart,
                autocorrectLearningPreparedSelectionEnd,
                replacement
        );
        if (expected == null) {
            clearAutocorrectLearningSession();
            return;
        }
        autocorrectLearningExpectedTapziqDocument = expected;
    }

    private void expectTapziqDeleteBeforeCursor(InputConnection connection) {
        if (autocorrectLearningSession == null
                || connection == null
                || connection != autocorrectLearningConnection
                || autocorrectLearningPreparedDocument == null) {
            return;
        }
        String expected = AutocorrectLearningMutation.deleteBeforeCursor(
                autocorrectLearningPreparedDocument,
                autocorrectLearningPreparedSelectionStart,
                autocorrectLearningPreparedSelectionEnd
        );
        if (expected == null) {
            clearAutocorrectLearningSession();
            return;
        }
        autocorrectLearningExpectedTapziqDocument = expected;
    }

    private void clearPreparedAutocorrectLearningMutation() {
        autocorrectLearningPreparedDocument = null;
        autocorrectLearningPreparedSelectionStart = -1;
        autocorrectLearningPreparedSelectionEnd = -1;
        autocorrectLearningExpectedTapziqDocument = null;
    }

    private void observeEditorMutationForLearning(InputConnection connection) {
        observeEditorMutationForRecentAutocorrections(connection);
        if (autocorrectLearningSession == null) {
            return;
        }
        observeAutocorrectLearning(connection, false, false, true);
    }

    private void observeAutocorrectLearning(
            boolean finalize,
            boolean keepWhileSelectionTouchesReplacement
    ) {
        observeAutocorrectLearning(
                getCurrentInputConnection(),
                finalize,
                keepWhileSelectionTouchesReplacement
        );
    }

    private void observeAutocorrectLearning(
            InputConnection connection,
            boolean finalize,
            boolean keepWhileSelectionTouchesReplacement
    ) {
        observeAutocorrectLearning(
                connection,
                finalize,
                keepWhileSelectionTouchesReplacement,
                false
        );
    }

    private void observeAutocorrectLearning(
            InputConnection connection,
            boolean finalize,
            boolean keepWhileSelectionTouchesReplacement,
            boolean afterTapziqMutation
    ) {
        AutocorrectLearningSession session = autocorrectLearningSession;
        if (session == null) {
            return;
        }
        if (!AutocorrectSettings.isLearningEnabled(this)
                || !EditorBehavior.supportsProofreading(editorInfo)
                || connection == null
                || connection != autocorrectLearningConnection
                || editorInfo == null
                || editorInfo != autocorrectLearningEditorInfo) {
            clearAutocorrectLearningSession();
            return;
        }
        ExtractedText current = completeLearningSnapshot(connection);
        if (current == null) {
            clearAutocorrectLearningSession();
            return;
        }
        String currentDocument = current.text.toString();
        if (!afterTapziqMutation
                && autocorrectLearningSawTapziqKey
                && !currentDocument.equals(autocorrectLearningLastObservedDocument)) {
            // The user accepted or triggered an editor-side change after a Tapziq key.
            clearAutocorrectLearningSession();
            return;
        }
        if (afterTapziqMutation) {
            if (autocorrectLearningExpectedTapziqDocument == null
                    || !autocorrectLearningExpectedTapziqDocument.equals(currentDocument)) {
                // A synchronous InputFilter, editor suggestion, or no-op connection changed the
                // operation. Only the exact Tapziq key transformation is attributable to Tapziq.
                clearAutocorrectLearningSession();
                return;
            }
            autocorrectLearningSawTapziqKey = true;
            autocorrectLearningLastObservedDocument = currentDocument;
            clearPreparedAutocorrectLearningMutation();
        }
        AutocorrectLearningSession.Observation observation = session.observe(
                currentDocument,
                current.selectionStart,
                current.selectionEnd,
                finalize,
                keepWhileSelectionTouchesReplacement
        );
        AutocorrectLearningSession.Decision decision = AutocorrectLearningSession.decide(
                observation,
                autocorrectLearningFromTappedCorrection,
                autocorrectLearningRejectionRecorded,
                autocorrectLearningSawTapziqKey
        );
        if (autocorrectLearningStore != null) {
            if (decision.recordRejection) {
                autocorrectLearningStore.recordRejection(session.feedback());
                autocorrectLearningRejectionRecorded = true;
            }
            if (decision.recordReplacement) {
                autocorrectLearningStore.recordReplacement(
                        session.feedback(),
                        observation.replacement
                );
            } else if (decision.forgetRejection) {
                // The user independently typed the former suggestion, so it is no longer rejected.
                autocorrectLearningStore.forget(session.feedback());
            }
        }
        if (decision.keepSession) {
            // The unchanged tap-away is the rejection signal. Keep a short, bounded
            // same-word session so a replacement the user types next can still be learned.
            scheduleAutocorrectLearningExpiry();
            return;
        }
        clearAutocorrectLearningSession();
    }

    private void clearAutocorrectLearningSession() {
        Handler handler = proofreadTimeoutHandler;
        if (handler != null) {
            handler.removeCallbacks(autocorrectLearningTimeout);
        }
        autocorrectLearningSession = null;
        autocorrectLearningConnection = null;
        autocorrectLearningEditorInfo = null;
        autocorrectLearningSawTapziqKey = false;
        autocorrectLearningFromTappedCorrection = false;
        autocorrectLearningRejectionRecorded = false;
        autocorrectLearningLastObservedDocument = null;
        clearPreparedAutocorrectLearningMutation();
        autocorrectLearningRecentCorrection = null;
        hideAutocorrectSuggestion();
    }

    private void scheduleAutocorrectLearningExpiry() {
        Handler handler = proofreadTimeoutHandler;
        if (handler == null) {
            clearAutocorrectLearningSession();
            return;
        }
        handler.removeCallbacks(autocorrectLearningTimeout);
        handler.postDelayed(autocorrectLearningTimeout, AUTOCORRECT_LEARNING_TIMEOUT_MS);
    }

    private ExtractedText completeLearningSnapshot(InputConnection connection) {
        if (connection == null) {
            return null;
        }
        ExtractedText current = connection.getExtractedText(extractedTextRequest(), 0);
        if (current == null
                || current.text == null
                || current.startOffset != 0
                || current.partialStartOffset >= 0
                || current.text.length() > ProofreadTarget.MAX_SNAPSHOT_CHARACTERS
                || AutocorrectTarget.hasNonEphemeralSpans(current.text)) {
            return null;
        }
        return current;
    }

    private RecentAutocorrection trackRecentAutocorrection(AutocorrectEdit edit) {
        if (!AutocorrectSettings.isLearningEnabled(this)
                || !EditorBehavior.supportsProofreading(editorInfo)) {
            return null;
        }
        RecentAutocorrection recent = RecentAutocorrection.from(edit);
        if (recent == null) {
            return null;
        }
        InputConnection connection = getCurrentInputConnection();
        if (connection == null || editorInfo == null) {
            return null;
        }
        if (!recentAutocorrections.isEmpty()
                && !recentAutocorrectionContextMatches(editorInfo, connection)) {
            clearRecentAutocorrections();
        }
        recentAutocorrectionEditor = EditorIdentity.capture(
                getCurrentInputBinding(),
                editorInfo,
                connection
        );
        recentAutocorrectionConnection = connection;
        recentAutocorrectionEditorInfo = editorInfo;
        recentAutocorrections.addFirst(recent);
        while (recentAutocorrections.size() > MAX_RECENT_AUTOCORRECTIONS) {
            recentAutocorrections.removeLast();
        }
        scheduleRecentAutocorrectionExpiry();
        return recent;
    }

    private void clearRecentAutocorrections() {
        Handler handler = proofreadTimeoutHandler;
        if (handler != null) {
            handler.removeCallbacks(recentAutocorrectionExpiry);
            handler.removeCallbacks(editorTapSettled);
        }
        pendingEditorTap = false;
        recentAutocorrections.clear();
        recentAutocorrectionEditor = null;
        recentAutocorrectionConnection = null;
        recentAutocorrectionEditorInfo = null;
        autocorrectUndoRecent = null;
    }

    private void clearAutocorrectUndo() {
        autocorrectUndo = null;
        autocorrectUndoRecent = null;
    }

    private void removeRecentAutocorrection(RecentAutocorrection recent) {
        if (recent == null) {
            return;
        }
        recentAutocorrections.remove(recent);
        if (autocorrectLearningRecentCorrection == recent) {
            clearAutocorrectLearningSession();
        }
        if (recentAutocorrections.isEmpty()) {
            Handler handler = proofreadTimeoutHandler;
            if (handler != null) {
                handler.removeCallbacks(recentAutocorrectionExpiry);
            }
            recentAutocorrectionEditor = null;
            recentAutocorrectionConnection = null;
            recentAutocorrectionEditorInfo = null;
        } else {
            scheduleRecentAutocorrectionExpiry();
        }
    }

    private void expireRecentAutocorrections() {
        long nowNanos = System.nanoTime();
        Iterator<RecentAutocorrection> iterator = recentAutocorrections.iterator();
        while (iterator.hasNext()) {
            RecentAutocorrection recent = iterator.next();
            if (recent.isExpired(nowNanos, RECENT_AUTOCORRECTION_TIMEOUT_NANOS)) {
                iterator.remove();
                // Expiry prevents a new tap from starting a review. A review that the user
                // already opened owns its anchored candidate until its separate 30-second
                // session expires, so Use and replacement learning remain coherent.
                releaseRecentAutocorrectionAliases(recent, true);
            }
        }
        if (recentAutocorrections.isEmpty()) {
            recentAutocorrectionEditor = null;
            recentAutocorrectionConnection = null;
            recentAutocorrectionEditorInfo = null;
        } else {
            scheduleRecentAutocorrectionExpiry();
        }
    }

    private void scheduleRecentAutocorrectionExpiry() {
        Handler handler = proofreadTimeoutHandler;
        RecentAutocorrection oldest = recentAutocorrections.peekLast();
        if (handler == null || oldest == null) {
            return;
        }
        handler.removeCallbacks(recentAutocorrectionExpiry);
        long expiresAtNanos = oldest.createdAtNanos() + RECENT_AUTOCORRECTION_TIMEOUT_NANOS;
        long remainingNanos = expiresAtNanos - System.nanoTime();
        long delayMillis = Math.max(
                1L,
                TimeUnit.NANOSECONDS.toMillis(Math.max(0L, remainingNanos - 1L)) + 1L
        );
        handler.postDelayed(recentAutocorrectionExpiry, delayMillis);
    }

    private boolean recentAutocorrectionContextMatches(
            EditorInfo currentEditorInfo,
            InputConnection currentConnection
    ) {
        if (recentAutocorrections.isEmpty()) {
            return true;
        }
        if (currentEditorInfo == null
                || currentConnection == null
                || recentAutocorrectionConnection == null
                || recentAutocorrectionEditorInfo == null) {
            return false;
        }
        if (recentAutocorrectionEditor != null) {
            return recentAutocorrectionEditor.matches(
                    getCurrentInputBinding(),
                    currentEditorInfo,
                    currentConnection
            );
        }
        // Some native editors expose no stable field ID. Keep their bounded evidence only while
        // Android preserves the exact connection and EditorInfo objects for this input session.
        return currentConnection == recentAutocorrectionConnection
                && currentEditorInfo == recentAutocorrectionEditorInfo;
    }

    private RecentAutocorrection recentAutocorrectionAt(ExtractedText current) {
        if (current == null || current.text == null) {
            return null;
        }
        pruneRecentAutocorrections(current, false);
        for (RecentAutocorrection recent : recentAutocorrections) {
            if (recent.matches(
                    current.text,
                    current.startOffset,
                    current.selectionStart,
                    current.selectionEnd,
                    true
            )) {
                return recent;
            }
        }
        return null;
    }

    private void observeEditorMutationForRecentAutocorrections(InputConnection connection) {
        if (recentAutocorrections.isEmpty()) {
            return;
        }
        if (!recentAutocorrectionContextMatches(editorInfo, connection)) {
            clearRecentAutocorrections();
            return;
        }
        ExtractedText current = completeLearningSnapshot(connection);
        if (current == null) {
            clearRecentAutocorrections();
            return;
        }
        pruneRecentAutocorrections(current, true);
    }

    private void pruneRecentAutocorrections(
            ExtractedText current,
            boolean preserveActiveSessionAfterOwnedMutation
    ) {
        long nowNanos = System.nanoTime();
        Iterator<RecentAutocorrection> iterator = recentAutocorrections.iterator();
        while (iterator.hasNext()) {
            RecentAutocorrection recent = iterator.next();
            boolean expired = recent.isExpired(
                    nowNanos,
                    RECENT_AUTOCORRECTION_TIMEOUT_NANOS
            );
            if (expired || !recent.matchesDocument(current.text, current.startOffset, true)) {
                iterator.remove();
                releaseRecentAutocorrectionAliases(
                        recent,
                        expired || preserveActiveSessionAfterOwnedMutation
                );
            }
        }
        if (recentAutocorrections.isEmpty()) {
            Handler handler = proofreadTimeoutHandler;
            if (handler != null) {
                handler.removeCallbacks(recentAutocorrectionExpiry);
            }
            recentAutocorrectionEditor = null;
            recentAutocorrectionConnection = null;
            recentAutocorrectionEditorInfo = null;
        } else {
            scheduleRecentAutocorrectionExpiry();
        }
    }

    private void releaseRecentAutocorrectionAliases(
            RecentAutocorrection recent,
            boolean preserveActiveSession
    ) {
        if (autocorrectUndoRecent == recent) {
            autocorrectUndoRecent = null;
        }
        if (autocorrectLearningRecentCorrection == recent) {
            if (!preserveActiveSession) {
                clearAutocorrectLearningSession();
            }
            // When preserved, the queue evidence is no longer tappable, but the already opened
            // session keeps this exact anchored candidate until its own bounded timeout. This
            // keeps both Use and the Tapziq-authored replacement path valid.
        }
    }

    private void handleUserEditorTapForLearning() {
        if (proofreadSuggestion != null) {
            // This is a Tapziq-owned reviewed result. An editor tap instead of Apply rejects it.
            dismissProofreadSuggestionForLearning();
            return;
        }
        if (!AutocorrectSettings.isLearningEnabled(this)
                || !EditorBehavior.supportsProofreading(editorInfo)
                || autocorrectLearningStore == null) {
            clearAutocorrectLearningSession();
            clearRecentAutocorrections();
            return;
        }

        InputConnection connection = getCurrentInputConnection();
        if (!recentAutocorrectionContextMatches(editorInfo, connection)) {
            clearAutocorrectLearningSession();
            clearRecentAutocorrections();
            return;
        }
        ExtractedText current = completeLearningSnapshot(connection);
        if (current == null) {
            clearAutocorrectLearningSession();
            clearRecentAutocorrections();
            return;
        }
        RecentAutocorrection tappedCorrection = recentAutocorrectionAt(current);
        if (tappedCorrection == null
                && autocorrectLearningRecentCorrection != null
                && autocorrectLearningRecentCorrection.matches(
                        current.text,
                        current.startOffset,
                        current.selectionStart,
                        current.selectionEnd,
                        true
                )) {
            // The two-minute queue TTL blocks new reviews, but an already opened review owns
            // its candidate for its separate 30-second session. A related tap on that same word
            // should therefore keep/re-show the still-valid Tapziq candidate.
            tappedCorrection = autocorrectLearningRecentCorrection;
        }

        if (autocorrectLearningSession != null) {
            hideAutocorrectSuggestion();
            observeAutocorrectLearning(connection, true, true);
            if (autocorrectLearningSession != null) {
                if (tappedCorrection != null
                        && tappedCorrection != autocorrectLearningRecentCorrection) {
                    // Finish the prior unchanged review, then follow the correction the user
                    // actually tapped. Both remain bounded to this exact editor connection.
                    clearAutocorrectLearningSession();
                    beginTappedAutocorrectionLearning(
                            tappedCorrection,
                            current,
                            connection
                    );
                } else if (tappedCorrection == autocorrectLearningRecentCorrection) {
                    showAutocorrectSuggestion(tappedCorrection);
                }
                return;
            }
        }
        if (tappedCorrection != null) {
            beginTappedAutocorrectionLearning(tappedCorrection, current, connection);
        }
    }

    private void beginTappedAutocorrectionLearning(
            RecentAutocorrection recent,
            ExtractedText current,
            InputConnection connection
    ) {
        beginAutocorrectLearningSession(
                recent.feedback(),
                recent.start(),
                recent.end(),
                current,
                connection,
                true,
                false,
                recent
        );
        if (autocorrectLearningSession != null) {
            showAutocorrectSuggestion(recent);
        }
    }

    private void showAutocorrectSuggestion(RecentAutocorrection recent) {
        if (recent == null || keyboardPanel == null) {
            return;
        }
        showingAutocorrectNotice = false;
        showingAutocorrectSuggestion = true;
        keyboardPanel.showAutocorrectSuggestion(recent.feedback().written);
    }

    private void hideAutocorrectSuggestion() {
        if (!showingAutocorrectSuggestion) {
            return;
        }
        showingAutocorrectSuggestion = false;
        if (keyboardPanel != null) {
            keyboardPanel.hideAutocorrectSuggestion();
        }
    }

    private void dismissAutocorrectSuggestionForLearning() {
        if (!showingAutocorrectSuggestion || autocorrectLearningSession == null) {
            hideAutocorrectSuggestion();
            return;
        }
        hideAutocorrectSuggestion();
        // Explicit × is the same rejection signal as showing the candidate and tapping away.
        observeAutocorrectLearning(true, false);
    }

    private void useAutocorrectOriginalSuggestion() {
        RecentAutocorrection recent = autocorrectLearningRecentCorrection;
        InputConnection connection = getCurrentInputConnection();
        hideAutocorrectSuggestion();
        clearAutocorrectLearningSession();
        if (recent == null || connection == null) {
            return;
        }
        AutocorrectApplier.Result result = AutocorrectApplier.useOriginalSuggestion(
                connection,
                recent
        );
        if (!result.textApplied()) {
            return;
        }
        if (autocorrectLearningStore != null && AutocorrectSettings.isLearningEnabled(this)) {
            autocorrectLearningStore.recordRejection(recent.feedback());
        }
        if (autocorrectUndoRecent == recent) {
            clearAutocorrectUndo();
        }
        removeRecentAutocorrection(recent);
        showingAutocorrectNotice = true;
        if (keyboardPanel != null) {
            keyboardPanel.showProofreadMessage(
                    getString(R.string.autocorrect_original_restored),
                    true
            );
        }
    }

    private void startProofread() {
        if (proofreadSessionId != 0) {
            return;
        }
        clearAutocorrectLearningSession();
        clearRecentAutocorrections();
        cancelAutocorrectRequest();
        clearAutocorrectUndo();
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
        clearAutocorrectLearningSession();
        if (!restarting || !recentAutocorrectionContextMatches(
                attribute,
                getCurrentInputConnection()
        )) {
            clearRecentAutocorrections();
        }
        cancelAutocorrectRequest();
        clearAutocorrectUndo();
        editorInfo = attribute;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onViewClicked(boolean focusChanged) {
        super.onViewClicked(focusChanged);
        Handler handler = proofreadTimeoutHandler;
        if (handler == null) {
            return;
        }
        pendingEditorTap = true;
        handler.removeCallbacks(editorTapSettled);
        // Android can notify the IME just before it publishes the editor's new selection.
        // Read the settled snapshot after a short delay, and only for this genuine view click.
        handler.postDelayed(editorTapSettled, EDITOR_TAP_SETTLE_MS);
    }

    private void settlePendingEditorTapForLearning() {
        if (!pendingEditorTap) {
            return;
        }
        pendingEditorTap = false;
        Handler handler = proofreadTimeoutHandler;
        if (handler != null) {
            handler.removeCallbacks(editorTapSettled);
        }
        if (isInputViewShown()) {
            handleUserEditorTapForLearning();
        }
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
        if (autocorrectLearningStore != null) {
            autocorrectLearningStore.unregisterClearListener(learningMemoryListener);
        }
        clearAutocorrectLearningSession();
        clearRecentAutocorrections();
        cancelAutocorrectRequest();
        closeAutocorrectProofreader();
        if (autocorrectModelStore != null) {
            autocorrectModelStore.close();
            autocorrectModelStore = null;
        }
        clearAutocorrectUndo();
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
