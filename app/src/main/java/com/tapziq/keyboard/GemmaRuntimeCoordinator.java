package com.tapziq.keyboard;

import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Process-wide leases that prevent overlapping native loads and model-file mutation. */
final class GemmaRuntimeCoordinator {
    private static final ReentrantLock INFERENCE = new ReentrantLock();
    private static final ReentrantLock DOWNLOAD = new ReentrantLock();
    private static final ReentrantReadWriteLock MODEL_FILE = new ReentrantReadWriteLock();

    private GemmaRuntimeCoordinator() {
    }

    static void acquireInference() throws InterruptedException {
        INFERENCE.lockInterruptibly();
        boolean modelLeaseAcquired = false;
        try {
            MODEL_FILE.readLock().lockInterruptibly();
            modelLeaseAcquired = true;
        } finally {
            if (!modelLeaseAcquired) {
                INFERENCE.unlock();
            }
        }
    }

    static void releaseInference() {
        MODEL_FILE.readLock().unlock();
        INFERENCE.unlock();
    }

    static void acquireDownload() throws InterruptedException {
        DOWNLOAD.lockInterruptibly();
    }

    static void releaseDownload() {
        DOWNLOAD.unlock();
    }

    static boolean tryAcquireModelMutation() {
        if (!DOWNLOAD.tryLock()) {
            return false;
        }
        if (!MODEL_FILE.writeLock().tryLock()) {
            DOWNLOAD.unlock();
            return false;
        }
        return true;
    }

    static void releaseModelMutation() {
        MODEL_FILE.writeLock().unlock();
        DOWNLOAD.unlock();
    }

    static void acquireInstalledFileMutation() {
        MODEL_FILE.writeLock().lock();
    }

    static void releaseInstalledFileMutation() {
        MODEL_FILE.writeLock().unlock();
    }
}
