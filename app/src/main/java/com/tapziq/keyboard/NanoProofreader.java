package com.tapziq.keyboard;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.genai.common.DownloadCallback;
import com.google.mlkit.genai.common.GenAiException;
import com.google.mlkit.genai.proofreading.Proofreader;
import com.google.mlkit.genai.proofreading.ProofreaderOptions;
import com.google.mlkit.genai.proofreading.Proofreading;
import com.google.mlkit.genai.proofreading.ProofreadingRequest;
import com.google.mlkit.genai.proofreading.ProofreadingResult;
import com.google.mlkit.genai.proofreading.ProofreadingSuggestion;

import android.content.Context;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Small lifecycle-safe adapter around ML Kit's on-device Gemini Nano proofreader. */
final class NanoProofreader implements AutoCloseable {
    interface StatusCallback {
        void onStatus(int featureStatus);

        void onFailure(Throwable error);
    }

    interface DownloadListener {
        void onStarted(long bytesToDownload);

        void onProgress(long totalBytesDownloaded);

        void onCompleted();

        void onFailure(Throwable error);
    }

    interface InferenceCallback {
        void onSuggestion(String suggestion);

        void onFailure(Throwable error);
    }

    private final Proofreader client;
    private final Executor callbackExecutor;
    private ListenableFuture<?> activeFuture;
    private int operationId;
    private boolean closed;

    NanoProofreader(Context context, Executor callbackExecutor) {
        ProofreaderOptions options = ProofreaderOptions.builder(context.getApplicationContext())
                .setInputType(ProofreaderOptions.InputType.KEYBOARD)
                .setLanguage(ProofreaderOptions.Language.ENGLISH)
                .build();
        client = Proofreading.getClient(options);
        this.callbackExecutor = callbackExecutor;
    }

    void checkStatus(StatusCallback callback) {
        int id = beginOperation();
        ListenableFuture<Integer> future = client.checkFeatureStatus();
        activeFuture = future;
        future.addListener(() -> {
            if (!isCurrent(id)) {
                return;
            }
            try {
                callback.onStatus(future.get());
            } catch (CancellationException ignored) {
                // A new editor or user action superseded this check.
            } catch (ExecutionException error) {
                callback.onFailure(rootCause(error));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                callback.onFailure(error);
            }
        }, callbackExecutor);
    }

    void download(DownloadListener listener) {
        int id = beginOperation();
        AtomicBoolean terminalCallbackSent = new AtomicBoolean();
        ListenableFuture<Void> future = client.downloadFeature(new DownloadCallback() {
            @Override
            public void onDownloadCompleted() {
                deliver(id, () -> {
                    if (terminalCallbackSent.compareAndSet(false, true)) {
                        listener.onCompleted();
                    }
                });
            }

            @Override
            public void onDownloadFailed(GenAiException error) {
                deliver(id, () -> {
                    if (terminalCallbackSent.compareAndSet(false, true)) {
                        listener.onFailure(error);
                    }
                });
            }

            @Override
            public void onDownloadProgress(long totalBytesDownloaded) {
                deliver(id, () -> listener.onProgress(totalBytesDownloaded));
            }

            @Override
            public void onDownloadStarted(long bytesToDownload) {
                deliver(id, () -> listener.onStarted(bytesToDownload));
            }
        });
        activeFuture = future;
        future.addListener(() -> {
            if (!isCurrent(id) || terminalCallbackSent.get()) {
                return;
            }
            try {
                future.get();
                if (terminalCallbackSent.compareAndSet(false, true)) {
                    listener.onCompleted();
                }
            } catch (CancellationException ignored) {
                // A new editor or user action superseded this download.
            } catch (ExecutionException error) {
                if (terminalCallbackSent.compareAndSet(false, true)) {
                    listener.onFailure(rootCause(error));
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                if (terminalCallbackSent.compareAndSet(false, true)) {
                    listener.onFailure(error);
                }
            }
        }, callbackExecutor);
    }

    void proofread(String text, InferenceCallback callback) {
        int id = beginOperation();
        ProofreadingRequest request = ProofreadingRequest.builder(text).build();
        ListenableFuture<ProofreadingResult> future = client.runInference(request);
        activeFuture = future;
        future.addListener(() -> {
            if (!isCurrent(id)) {
                return;
            }
            try {
                List<ProofreadingSuggestion> suggestions = future.get().getResults();
                if (suggestions.isEmpty()) {
                    callback.onSuggestion(text);
                } else {
                    callback.onSuggestion(suggestions.get(0).getText());
                }
            } catch (CancellationException ignored) {
                // A new editor or user action superseded this inference.
            } catch (ExecutionException error) {
                callback.onFailure(rootCause(error));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                callback.onFailure(error);
            }
        }, callbackExecutor);
    }

    void cancel() {
        operationId++;
        if (activeFuture != null) {
            activeFuture.cancel(true);
            activeFuture = null;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        cancel();
        client.close();
    }

    private int beginOperation() {
        if (closed) {
            throw new IllegalStateException("Proofreader is closed");
        }
        cancel();
        return operationId;
    }

    private boolean isCurrent(int id) {
        return !closed && id == operationId;
    }

    private void deliver(int id, Runnable callback) {
        callbackExecutor.execute(() -> {
            if (isCurrent(id)) {
                callback.run();
            }
        });
    }

    private static Throwable rootCause(ExecutionException error) {
        return error.getCause() != null ? error.getCause() : error;
    }
}
