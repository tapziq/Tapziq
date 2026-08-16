package com.tapziq.keyboard;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.MessageCallback;
import com.google.ai.edge.litertlm.ResponseFormat;
import com.google.ai.edge.litertlm.SamplerConfig;
import com.google.ai.edge.litertlm.ThinkingConfig;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Lifecycle-safe adapter for local Gemma 4 inference through LiteRT-LM. */
final class GemmaProofreader implements AutoCloseable {
    interface InferenceCallback {
        void onSuggestion(String suggestion);

        void onFailure(Throwable error);
    }

    static final class UnavailableException extends Exception {
        UnavailableException(Throwable cause) {
            super("Gemma 4 cannot run on this device.", cause);
        }
    }

    static final class InferenceException extends Exception {
        InferenceException(String message) {
            super(message);
        }

        InferenceException(Throwable cause) {
            super("Gemma 4 inference failed.", cause);
        }
    }

    private static final class AsyncResult implements MessageCallback {
        private final CountDownLatch done = new CountDownLatch(1);
        private final StringBuilder response = new StringBuilder();
        private volatile Throwable failure;

        @Override
        public synchronized void onMessage(Message message) {
            response.append(message.toString());
        }

        @Override
        public void onDone() {
            done.countDown();
        }

        @Override
        public void onError(Throwable error) {
            failure = error;
            done.countDown();
        }

        void await() throws InterruptedException {
            done.await();
        }

        synchronized String response() {
            return response.toString();
        }

        Throwable failure() {
            return failure;
        }
    }

    private final Executor callbackExecutor;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tapziq-gemma-inference");
        thread.setDaemon(true);
        return thread;
    });
    private Future<?> activeTask;
    // Guarded by this instance's monitor. Native cancellation and destruction must never race.
    private Conversation activeConversation;
    private int operationId;
    private boolean closed;

    GemmaProofreader(Executor callbackExecutor) {
        this.callbackExecutor = callbackExecutor;
    }

    synchronized void proofread(File modelFile, String text, InferenceCallback callback) {
        if (closed) {
            throw new IllegalStateException("Proofreader is closed.");
        }
        cancelLocked();
        int id = operationId;
        activeTask = worker.submit(() -> runInference(id, modelFile, text, callback));
    }

    synchronized void cancel() {
        cancelLocked();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        cancelLocked();
        worker.shutdownNow();
    }

    private void runInference(
            int id,
            File modelFile,
            String text,
            InferenceCallback callback
    ) {
        Engine engine = null;
        Conversation conversation = null;
        boolean requestStarted = false;
        boolean runtimeLease = false;
        try {
            if (!isWorkerCurrent(id)) {
                return;
            }
            GemmaRuntimeCoordinator.acquireInference();
            runtimeLease = true;
            if (!isWorkerCurrent(id)) {
                return;
            }
            if (!modelFile.isFile() || modelFile.length() != GemmaModelStore.MODEL_SIZE_BYTES) {
                throw new UnavailableException(
                        new IOException("The verified Gemma model is missing.")
                );
            }

            int threadCount = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
            EngineConfig engineConfig = new EngineConfig(
                    modelFile.getAbsolutePath(),
                    new Backend.CPU(threadCount, null),
                    null,
                    null,
                    1_536,
                    null,
                    ":nocache"
            );
            if (!isWorkerCurrent(id)) {
                return;
            }
            engine = new Engine(engineConfig);
            engine.initialize();
            if (!isWorkerCurrent(id)) {
                return;
            }

            ConversationConfig conversationConfig = new ConversationConfig(
                    Contents.Companion.of(GemmaProofreadPrompt.SYSTEM_INSTRUCTION),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    new SamplerConfig(1, 1.0, 0.0, 0),
                    false,
                    Collections.emptyList(),
                    Collections.emptyMap(),
                    null,
                    false,
                    512,
                    new ThinkingConfig(false, 0),
                    true
            );
            conversation = engine.createConversation(conversationConfig);
            AsyncResult result = new AsyncResult();
            requestStarted = true;
            if (!startRequestIfCurrent(id, conversation, text, result)) {
                return;
            }
            result.await();
            if (result.failure() != null) {
                throw result.failure();
            }
            String suggestion = GemmaProofreadPrompt.parse(result.response(), text);
            if (suggestion == null) {
                throw new InferenceException("Gemma 4 returned malformed proofreading output.");
            }
            deliverSuggestion(id, callback, suggestion);
        } catch (OutOfMemoryError | UnsatisfiedLinkError error) {
            deliverFailure(id, callback, new UnavailableException(error));
        } catch (UnavailableException error) {
            deliverFailure(id, callback, error);
        } catch (InferenceException error) {
            deliverFailure(id, callback, error);
        } catch (Throwable error) {
            if (!Thread.currentThread().isInterrupted()) {
                deliverFailure(
                        id,
                        callback,
                        requestStarted
                                ? new InferenceException(error)
                                : new UnavailableException(error)
                );
            }
        } finally {
            try {
                detachAndCloseConversation(conversation);
                if (engine != null && engine.isInitialized()) {
                    try {
                        engine.close();
                    } catch (RuntimeException ignored) {
                        // The native runtime is already unwinding after a failed request.
                    }
                }
            } finally {
                if (runtimeLease) {
                    GemmaRuntimeCoordinator.releaseInference();
                }
            }
        }
    }

    private void deliverSuggestion(int id, InferenceCallback callback, String suggestion) {
        callbackExecutor.execute(() -> {
            if (isCurrent(id)) {
                callback.onSuggestion(suggestion);
            }
        });
    }

    private void deliverFailure(int id, InferenceCallback callback, Throwable error) {
        callbackExecutor.execute(() -> {
            if (isCurrent(id)) {
                callback.onFailure(error);
            }
        });
    }

    private synchronized boolean isCurrent(int id) {
        return !closed && id == operationId;
    }

    private synchronized boolean isWorkerCurrent(int id) {
        return !Thread.currentThread().isInterrupted() && !closed && id == operationId;
    }

    private synchronized boolean startRequestIfCurrent(
            int id,
            Conversation conversation,
            String text,
            MessageCallback callback
    ) {
        if (Thread.currentThread().isInterrupted() || closed || id != operationId) {
            return false;
        }
        activeConversation = conversation;
        // The SDK registers native async inference before returning. Holding this monitor makes
        // registration atomic with cancelLocked(), so cancellation cannot land in a pre-start gap.
        conversation.sendMessageAsync(
                GemmaProofreadPrompt.build(text),
                callback,
                Collections.emptyMap(),
                null,
                null,
                null,
                512,
                new ThinkingConfig(false, 0),
                ResponseFormat.json(GemmaProofreadPrompt.RESPONSE_SCHEMA)
        );
        return true;
    }

    private synchronized void detachAndCloseConversation(Conversation conversation) {
        if (conversation == null) {
            return;
        }
        if (activeConversation == conversation) {
            activeConversation = null;
        }
        if (conversation.isAlive()) {
            try {
                conversation.close();
            } catch (RuntimeException ignored) {
                // The native runtime is already unwinding after a failed request.
            }
        }
    }

    private void cancelLocked() {
        operationId++;
        Conversation conversation = activeConversation;
        if (conversation != null && conversation.isAlive()) {
            try {
                conversation.cancelProcess();
            } catch (RuntimeException ignored) {
                // Cancellation is best effort; callbacks remain operation-scoped.
            }
        }
        if (activeTask != null) {
            activeTask.cancel(true);
            activeTask = null;
        }
    }
}
