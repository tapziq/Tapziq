package com.tapziq.keyboard;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Foreground bridge that asks the separately installed Offline Translator app for a result. */
public final class TranslateBridgeActivity extends Activity {
    static final String EXTRA_SESSION_ID = "com.tapziq.keyboard.TRANSLATION_SESSION_ID";
    private static final String STATE_LAUNCHED = "translator_launched";
    private static final int REQUEST_TRANSLATION = 1;

    private int sessionId;
    private TranslationSession.Pending pending;
    private boolean launched;
    private boolean finished;
    private boolean notifyResultOnStop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sessionId = getIntent().getIntExtra(EXTRA_SESSION_ID, 0);
        if (sessionId == 0) {
            finish();
            return;
        }
        if (TranslationSession.hasResult(sessionId)) {
            finished = true;
            notifyResultOnStop = true;
            finishAndRemoveTask();
            return;
        }
        pending = TranslationSession.claim(sessionId);
        if (pending == null) {
            finish();
            return;
        }

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        setContentView(createContentView());
        launched = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_LAUNCHED, false);
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        if (!launched && !finished) {
            launchTranslator();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_LAUNCHED, launched);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_TRANSLATION || finished) {
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            cancelAndFinish();
            return;
        }

        String normalized;
        try {
            CharSequence translatedText = data.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
            normalized = TranslationTarget.normalizeResult(translatedText);
        } catch (RuntimeException malformedResult) {
            completeWithMessage(getString(R.string.translation_invalid_result));
            return;
        }
        if (normalized == null) {
            completeWithMessage(getString(R.string.translation_invalid_result));
            return;
        }
        finishSuccessfully(TranslationSession.complete(
                sessionId,
                TranslationSession.Result.suggestion(normalized)
        ));
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (notifyResultOnStop) {
            notifyResultOnStop = false;
            TranslationSession.notifyResultReady(sessionId);
        }
    }

    @Override
    protected void onDestroy() {
        if (TranslationBridgeLifecycle.shouldCancelOnDestroy(
                finished,
                isFinishing(),
                isChangingConfigurations()
        ) && sessionId != 0) {
            TranslationSession.cancel(sessionId);
        }
        super.onDestroy();
    }

    private void launchTranslator() {
        launched = true;
        Intent intent = OfflineTranslatorContract.processText(pending.text);
        if (intent.resolveActivity(getPackageManager()) == null) {
            completeWithMessage(getString(R.string.translation_app_missing));
            return;
        }
        try {
            startActivityForResult(intent, REQUEST_TRANSLATION);
        } catch (ActivityNotFoundException | SecurityException error) {
            completeWithMessage(getString(R.string.translation_app_missing));
        } catch (RuntimeException error) {
            completeWithMessage(getString(R.string.translation_failed));
        }
    }

    private void completeWithMessage(String message) {
        finishSuccessfully(TranslationSession.complete(
                sessionId,
                TranslationSession.Result.message(message)
        ));
    }

    private void cancelAndFinish() {
        if (finished) {
            return;
        }
        finished = true;
        TranslationSession.cancel(sessionId);
        finishAndRemoveTask();
        overridePendingTransition(0, 0);
    }

    private void finishSuccessfully(boolean completed) {
        if (finished) {
            return;
        }
        finished = true;
        notifyResultOnStop = completed;
        if (!completed) {
            TranslationSession.cancel(sessionId);
        }
        finishAndRemoveTask();
        overridePendingTransition(0, 0);
    }

    private View createContentView() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(28), dp(26), dp(28), dp(26));
        content.setBackgroundColor(getColor(R.color.screen_background));

        TextView title = new TextView(this);
        title.setText(R.string.translation_activity_title);
        title.setTextColor(getColor(R.color.primary_text));
        title.setTextSize(20f);
        title.setTypeface(Typeface.create("sans", Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        content.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        ProgressBar progress = new ProgressBar(this);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        progressParams.topMargin = dp(18);
        content.addView(progress, progressParams);

        TextView status = new TextView(this);
        status.setText(R.string.translation_opening);
        status.setTextColor(getColor(R.color.secondary_text));
        status.setTextSize(15f);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        statusParams.topMargin = dp(14);
        content.addView(status, statusParams);
        return content;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
