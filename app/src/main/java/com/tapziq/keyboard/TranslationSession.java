package com.tapziq.keyboard;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** In-process, fail-closed handoff between the IME and the translation bridge Activity. */
final class TranslationSession {
    private static final long LAUNCH_TIMEOUT_SECONDS = 30;
    private static final long BRIDGE_TIMEOUT_MINUTES = 15;
    private static final long RESULT_TTL_MINUTES = 2;
    private static final ScheduledExecutorService EXPIRY_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "tapziq-translation-expiry");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    interface Listener {
        void onSessionChanged(int id);
    }

    private static int nextId;
    private static int activeId;
    private static boolean claimed;
    private static boolean deliveryReady;
    private static Pending pending;
    private static Result result;
    private static Listener listener;
    private static long expiryGeneration;
    private static ScheduledFuture<?> expiryFuture;

    private TranslationSession() {
    }

    static synchronized int begin(String text) {
        int id = ++nextId;
        activeId = id;
        claimed = false;
        deliveryReady = false;
        pending = new Pending(id, text);
        result = null;
        scheduleExpiryLocked(id, LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return id;
    }

    static synchronized Pending claim(int id) {
        if (pending == null || pending.id != id || activeId != id || result != null) {
            return null;
        }
        claimed = true;
        scheduleExpiryLocked(id, BRIDGE_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        return pending;
    }

    static synchronized Pending getPending(int id) {
        if (pending == null || pending.id != id || activeId != id) {
            return null;
        }
        return pending;
    }

    static synchronized boolean complete(int id, Result resultValue) {
        if (id != activeId || !claimed || pending == null || result != null) {
            return false;
        }
        result = new Result(id, resultValue.suggestion, resultValue.message);
        scheduleExpiryLocked(id, RESULT_TTL_MINUTES, TimeUnit.MINUTES);
        return true;
    }

    static synchronized Result peekDeliverableResult(int id) {
        if (!deliveryReady || result == null || result.id != id || activeId != id) {
            return null;
        }
        return result;
    }

    static synchronized Result takeDeliverableResult(int id) {
        Result value = peekDeliverableResult(id);
        if (value == null) {
            return null;
        }
        clearLocked();
        return value;
    }

    static synchronized boolean hasResult(int id) {
        return result != null && result.id == id && activeId == id;
    }

    static synchronized boolean isActive(int id) {
        return id != 0 && activeId == id;
    }

    static synchronized void setListener(Listener listenerValue) {
        listener = listenerValue;
    }

    /** Makes a completed result visible only after the foreground bridge has stopped. */
    static void notifyResultReady(int id) {
        Listener listenerValue;
        synchronized (TranslationSession.class) {
            if (!hasResult(id)) {
                return;
            }
            deliveryReady = true;
            listenerValue = listener;
        }
        notifyListener(listenerValue, id);
    }

    static void clear() {
        int id;
        Listener listenerValue;
        synchronized (TranslationSession.class) {
            id = activeId;
            listenerValue = listener;
            clearLocked();
        }
        if (id != 0) {
            notifyListener(listenerValue, id);
        }
    }

    static void cancel(int id) {
        Listener listenerValue = null;
        boolean changed = false;
        synchronized (TranslationSession.class) {
            if (activeId == id) {
                listenerValue = listener;
                clearLocked();
                changed = true;
            }
        }
        if (changed) {
            notifyListener(listenerValue, id);
        }
    }

    static synchronized long expiryGenerationForTest() {
        return expiryGeneration;
    }

    static void expireForTest(int id, long generation) {
        expire(id, generation);
    }

    private static void scheduleExpiryLocked(long id, long delay, TimeUnit unit) {
        invalidateExpiryLocked();
        long generation = expiryGeneration;
        expiryFuture = EXPIRY_EXECUTOR.schedule(
                () -> expire((int) id, generation),
                delay,
                unit
        );
    }

    private static void expire(int id, long generation) {
        Listener listenerValue = null;
        boolean expired = false;
        synchronized (TranslationSession.class) {
            if (activeId == id && expiryGeneration == generation) {
                listenerValue = listener;
                clearLocked();
                expired = true;
            }
        }
        if (expired) {
            notifyListener(listenerValue, id);
        }
    }

    private static void clearLocked() {
        activeId = 0;
        claimed = false;
        deliveryReady = false;
        pending = null;
        result = null;
        invalidateExpiryLocked();
    }

    private static void invalidateExpiryLocked() {
        expiryGeneration++;
        if (expiryFuture != null) {
            expiryFuture.cancel(false);
            expiryFuture = null;
        }
    }

    private static void notifyListener(Listener listenerValue, int id) {
        if (listenerValue != null) {
            listenerValue.onSessionChanged(id);
        }
    }

    static final class Pending {
        final int id;
        final String text;

        private Pending(int id, String text) {
            this.id = id;
            this.text = text;
        }
    }

    static final class Result {
        final int id;
        final String suggestion;
        final String message;

        private Result(int id, String suggestion, String message) {
            this.id = id;
            this.suggestion = suggestion;
            this.message = message;
        }

        static Result suggestion(String suggestion) {
            return new Result(0, suggestion, null);
        }

        static Result message(String message) {
            return new Result(0, null, message);
        }
    }
}
