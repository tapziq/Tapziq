package com.tapziq.keyboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.mlkit.genai.common.FeatureStatus;

/** Brief foreground host required by AICore while Gemini Nano performs inference. */
public final class ProofreadActivity extends Activity {
    static final String EXTRA_SESSION_ID = "com.tapziq.keyboard.PROOFREAD_SESSION_ID";

    private NanoProofreader proofreader;
    private TextView statusView;
    private ProgressBar progressView;
    private int sessionId;
    private ProofreadSession.Pending pending;
    private boolean finished;
    private boolean started;
    private boolean notifyResultOnStop;
    private static final String PREFERENCES = "proofread_preferences";
    private static final String AGE_CONFIRMED = "age_confirmed";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sessionId = getIntent().getIntExtra(EXTRA_SESSION_ID, 0);
        if (sessionId == 0) {
            finish();
            return;
        }
        if (ProofreadSession.hasResult(sessionId)) {
            finished = true;
            notifyResultOnStop = true;
            finishAndRemoveTask();
            return;
        }
        pending = ProofreadSession.claim(sessionId);
        if (pending == null) {
            finish();
            return;
        }

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        // Keep the source editor's IME connection alive while this Activity is
        // top-resumed for AICore. Without this flag, some Android variants
        // replace the connection wrapper during the round trip, making secure
        // editor identity revalidation fail even when the field is unchanged.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        setContentView(createContentView());
        Handler mainHandler = new Handler(Looper.getMainLooper());
        proofreader = new NanoProofreader(this, mainHandler::post);
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        if (!started && proofreader != null) {
            started = true;
            if (getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                    .getBoolean(AGE_CONFIRMED, false)) {
                checkAvailability();
            } else {
                showAgeConfirmation();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (proofreader != null) {
            proofreader.close();
            proofreader = null;
        }
        if (!finished && !isChangingConfigurations() && sessionId != 0) {
            ProofreadSession.cancel(sessionId);
        }
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (notifyResultOnStop) {
            notifyResultOnStop = false;
            ProofreadSession.notifyResultReady(sessionId);
        } else if (!finished && !isChangingConfigurations()) {
            ProofreadSession.cancel(sessionId);
            finished = true;
            finishAndRemoveTask();
        }
    }

    private View createContentView() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(28), dp(26), dp(28), dp(26));
        content.setBackgroundColor(getColor(R.color.screen_background));

        TextView title = new TextView(this);
        title.setText(R.string.proofread_activity_title);
        title.setTextColor(getColor(R.color.primary_text));
        title.setTextSize(20f);
        title.setTypeface(Typeface.create("sans", Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        content.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        progressView = new ProgressBar(this);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        progressParams.topMargin = dp(18);
        content.addView(progressView, progressParams);

        statusView = new TextView(this);
        statusView.setText(R.string.proofread_checking);
        statusView.setTextColor(getColor(R.color.secondary_text));
        statusView.setTextSize(15f);
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        statusParams.topMargin = dp(14);
        content.addView(statusView, statusParams);
        return content;
    }

    private void checkAvailability() {
        proofreader.checkStatus(new NanoProofreader.StatusCallback() {
            @Override
            public void onStatus(int featureStatus) {
                if (featureStatus == FeatureStatus.AVAILABLE
                        || featureStatus == FeatureStatus.DOWNLOADING) {
                    runProofread();
                } else if (featureStatus == FeatureStatus.DOWNLOADABLE) {
                    downloadFeature();
                } else {
                    completeWithMessage(getString(R.string.proofread_unavailable));
                }
            }

            @Override
            public void onFailure(Throwable error) {
                completeWithMessage(messageFor(error));
            }
        });
    }

    private void showAgeConfirmation() {
        progressView.setVisibility(View.INVISIBLE);
        statusView.setText(R.string.proofread_age_prompt);
        new AlertDialog.Builder(this)
                .setTitle(R.string.proofread_age_title)
                .setMessage(R.string.proofread_age_prompt)
                .setCancelable(false)
                .setPositiveButton(R.string.proofread_age_confirm, (dialog, which) -> {
                    SharedPreferences preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
                    preferences.edit().putBoolean(AGE_CONFIRMED, true).apply();
                    progressView.setVisibility(View.VISIBLE);
                    checkAvailability();
                })
                .setNegativeButton(R.string.proofread_age_cancel, (dialog, which) -> {
                    completeWithMessage(getString(R.string.proofread_age_required));
                })
                .show();
    }

    private void downloadFeature() {
        statusView.setText(R.string.proofread_downloading);
        proofreader.download(new NanoProofreader.DownloadListener() {
            @Override
            public void onStarted(long bytesToDownload) {
                statusView.setText(R.string.proofread_downloading);
            }

            @Override
            public void onProgress(long totalBytesDownloaded) {
                // AICore does not expose enough information for a dependable percentage here.
            }

            @Override
            public void onCompleted() {
                runProofread();
            }

            @Override
            public void onFailure(Throwable error) {
                completeWithMessage(messageFor(error));
            }
        });
    }

    private void runProofread() {
        statusView.setText(R.string.proofread_working);
        proofreader.proofread(pending.text, new NanoProofreader.InferenceCallback() {
            @Override
            public void onSuggestion(String suggestion) {
                String normalized = ProofreadTarget.normalizeSuggestion(suggestion);
                if (normalized == null || normalized.isEmpty()) {
                    completeWithMessage(getString(R.string.proofread_failed));
                    return;
                }
                boolean completed = ProofreadSession.complete(
                        sessionId,
                        ProofreadSession.Result.suggestion(normalized)
                );
                finishSuccessfully(completed);
            }

            @Override
            public void onFailure(Throwable error) {
                completeWithMessage(messageFor(error));
            }
        });
    }

    private void completeWithMessage(String message) {
        boolean completed = ProofreadSession.complete(
                sessionId,
                ProofreadSession.Result.message(message)
        );
        finishSuccessfully(completed);
    }

    private void finishSuccessfully(boolean completed) {
        if (finished) {
            return;
        }
        finished = true;
        notifyResultOnStop = completed;
        finishAndRemoveTask();
        overridePendingTransition(0, 0);
    }

    private String messageFor(Throwable error) {
        return ProofreadErrors.userMessage(this, error);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
