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

    private static final class InferenceRequest {
        final String text;
        final AutocorrectTarget autocorrectTarget;

        private InferenceRequest(String text, AutocorrectTarget autocorrectTarget) {
            this.text = text;
            this.autocorrectTarget = autocorrectTarget;
        }

        static InferenceRequest proofread(String text) {
            return new InferenceRequest(text, null);
        }

        static InferenceRequest autocorrect(AutocorrectTarget target) {
            return new InferenceRequest(target.text(), target);
        }

        String systemInstruction() {
            return autocorrectTarget == null
                    ? GemmaProofreadPrompt.SYSTEM_INSTRUCTION
                    : GemmaAutocorrectPrompt.SYSTEM_INSTRUCTION;
        }

        String prompt() {
            return autocorrectTarget == null
                    ? GemmaProofreadPrompt.build(text)
                    : GemmaAutocorrectPrompt.build(text);
        }

        String responseSchema() {
            return autocorrectTarget == null
                    ? GemmaProofreadPrompt.RESPONSE_SCHEMA
                    : GemmaAutocorrectPrompt.RESPONSE_SCHEMA;
        }

        String parse(String response) {
            if (autocorrectTarget == null) {
                return GemmaProofreadPrompt.parse(response, text);
            }
            AutocorrectEdit edit = GemmaAutocorrectPrompt.parse(response, autocorrectTarget);
            return edit == null ? null : edit.correctedText();
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
    private final boolean retainEngine;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tapziq-gemma-inference");
        thread.setDaemon(true);
        return thread;
    });
    private Future<?> activeTask;
    // Guarded by this instance's monitor. Native cancellation and destruction must never race.
    private Conversation activeConversation;
    // Accessed only by the single inference worker. Autocorrect retains this between requests.
    private Engine cachedEngine;
    private File cachedModelFile;
    private boolean cachedEngineOwnsRuntimeLease;
    private int operationId;
    private boolean closed;

    GemmaProofreader(Executor callbackExecutor) {
        this(callbackExecutor, false);
    }

    GemmaProofreader(Executor callbackExecutor, boolean retainEngine) {
        this.callbackExecutor = callbackExecutor;
        this.retainEngine = retainEngine;
    }

    synchronized void proofread(File modelFile, String text, InferenceCallback callback) {
        submit(modelFile, InferenceRequest.proofread(text), callback);
    }

    synchronized void autocorrect(
            File modelFile,
            AutocorrectTarget target,
            InferenceCallback callback
    ) {
        submit(modelFile, InferenceRequest.autocorrect(target), callback);
    }

    private void submit(
            File modelFile,
            InferenceRequest request,
            InferenceCallback callback
    ) {
        if (closed) {
            throw new IllegalStateException("Proofreader is closed.");
        }
        cancelLocked();
        int id = operationId;
        activeTask = worker.submit(() -> runInference(id, modelFile, request, callback));
    }

    synchronized void cancel() {
        cancelLocked();
    }

    @Override
    public void close() {
        close(null);
    }

    synchronized void close(Runnable afterClose) {
        if (closed) {
            if (afterClose != null) {
                callbackExecutor.execute(afterClose);
            }
            return;
        }
        closed = true;
        cancelLocked();
        worker.execute(() -> {
            try {
                disposeCachedEngineWithLease();
            } finally {
                if (afterClose != null) {
                    callbackExecutor.execute(afterClose);
                }
            }
        });
        worker.shutdown();
    }

    private void runInference(
            int id,
            File modelFile,
            InferenceRequest request,
            InferenceCallback callback
    ) {
        Engine engine = null;
        Conversation conversation = null;
        boolean requestStarted = false;
        boolean runtimeLease = false;
        boolean discardRetainedEngine = false;
        try {
            if (!isWorkerCurrent(id)) {
                return;
            }
            if (!retainEngine || !cachedEngineOwnsRuntimeLease) {
                GemmaRuntimeCoordinator.acquireInference();
            }
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
            engine = obtainEngine(modelFile, threadCount);
            if (!isWorkerCurrent(id)) {
                return;
            }

            ConversationConfig conversationConfig = new ConversationConfig(
                    Contents.Companion.of(request.systemInstruction()),
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
            if (!startRequestIfCurrent(id, conversation, request, result)) {
                return;
            }
            result.await();
            if (result.failure() != null) {
                throw result.failure();
            }
            String suggestion = request.parse(result.response());
            if (suggestion == null) {
                throw new InferenceException("Gemma 4 returned malformed correction output.");
            }
            deliverSuggestion(id, callback, suggestion);
        } catch (OutOfMemoryError | UnsatisfiedLinkError error) {
            discardRetainedEngine = true;
            deliverFailure(id, callback, new UnavailableException(error));
        } catch (UnavailableException error) {
            deliverFailure(id, callback, error);
        } catch (InferenceException error) {
            deliverFailure(id, callback, error);
        } catch (InterruptedException canceled) {
            // A newer boundary or lifecycle event canceled this request. Keep a healthy cached
            // engine warm for the next request and preserve the worker's interrupt status.
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            // Native cancellation can arrive as a runtime exception without an interrupt. The
            // operation id is the authoritative cancellation signal.
            if (isCurrent(id)) {
                discardRetainedEngine = true;
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
                if (retainEngine && discardRetainedEngine) {
                    disposeCachedEngine();
                } else if (!retainEngine && engine != null) {
                    closeEngine(engine);
                }
            } finally {
                if (runtimeLease) {
                    if (retainEngine && cachedEngine != null) {
                        cachedEngineOwnsRuntimeLease = true;
                    } else {
                        cachedEngineOwnsRuntimeLease = false;
                        GemmaRuntimeCoordinator.releaseInference();
                    }
                }
            }
        }
    }

    private Engine obtainEngine(File modelFile, int threadCount) throws Throwable {
        if (retainEngine && cachedEngine != null) {
            if (cachedModelFile != null
                    && cachedModelFile.getAbsolutePath().equals(modelFile.getAbsolutePath())
                    && cachedEngine.isInitialized()) {
                return cachedEngine;
            }
            disposeCachedEngine();
        }

        EngineConfig engineConfig = new EngineConfig(
                modelFile.getAbsolutePath(),
                new Backend.CPU(threadCount, null),
                null,
                null,
                1_536,
                null,
                ":nocache"
        );
        Engine engine = new Engine(engineConfig);
        try {
            engine.initialize();
        } catch (Throwable error) {
            closeEngine(engine);
            throw error;
        }
        if (retainEngine) {
            cachedEngine = engine;
            cachedModelFile = modelFile;
        }
        return engine;
    }

    private void disposeCachedEngineWithLease() {
        if (cachedEngine == null && !cachedEngineOwnsRuntimeLease) {
            return;
        }
        boolean runtimeLease = cachedEngineOwnsRuntimeLease;
        boolean interrupted = false;
        try {
            while (!runtimeLease) {
                try {
                    GemmaRuntimeCoordinator.acquireInference();
                    runtimeLease = true;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            disposeCachedEngine();
        } finally {
            if (runtimeLease) {
                cachedEngineOwnsRuntimeLease = false;
                GemmaRuntimeCoordinator.releaseInference();
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void disposeCachedEngine() {
        Engine engine = cachedEngine;
        cachedEngine = null;
        cachedModelFile = null;
        closeEngine(engine);
    }

    private static void closeEngine(Engine engine) {
        if (engine == null) {
            return;
        }
        try {
            if (engine.isInitialized()) {
                engine.close();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // The native runtime is already unwinding after a failed request.
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
            InferenceRequest request,
            MessageCallback callback
    ) {
        if (Thread.currentThread().isInterrupted() || closed || id != operationId) {
            return false;
        }
        activeConversation = conversation;
        // The SDK registers native async inference before returning. Holding this monitor makes
        // registration atomic with cancelLocked(), so cancellation cannot land in a pre-start gap.
        conversation.sendMessageAsync(
                request.prompt(),
                callback,
                Collections.emptyMap(),
                null,
                null,
                null,
                512,
                new ThinkingConfig(false, 0),
                ResponseFormat.json(request.responseSchema())
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
        try {
            if (conversation.isAlive()) {
                conversation.close();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // The native runtime is already unwinding after a failed request.
        }
    }

    private void cancelLocked() {
        operationId++;
        Conversation conversation = activeConversation;
        if (conversation != null) {
            try {
                if (conversation.isAlive()) {
                    conversation.cancelProcess();
                }
            } catch (RuntimeException | LinkageError ignored) {
                // Cancellation is best effort; callbacks remain operation-scoped.
            }
        }
        if (activeTask != null) {
            activeTask.cancel(true);
            activeTask = null;
        }
    }
}
