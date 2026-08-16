package com.tapziq.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.StatFs;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owns Tapziq's resumable, integrity-checked app-private Gemma model file. */
final class GemmaModelStore implements AutoCloseable {
    static final String MODEL_NAME = "Gemma 4 E2B-it";
    static final String MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm";
    static final String MODEL_REVISION = "6e5c4f1e395deb959c494953478fa5cec4b8008f";
    static final long MODEL_SIZE_BYTES = 2_588_147_712L;
    static final String MODEL_SHA256 =
            "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c";
    static final String MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/"
                    + MODEL_REVISION + "/" + MODEL_FILE_NAME;

    private static final String PREFERENCES = "gemma_model";
    private static final String VERIFIED_REVISION = "verified_revision";
    private static final String VERIFIED_SHA256 = "verified_sha256";
    private static final long FREE_SPACE_MARGIN_BYTES = 256L * 1024L * 1024L;
    private static final int BUFFER_BYTES = 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int MAX_REDIRECTS = 8;
    private static final Pattern CONTENT_RANGE = Pattern.compile(
            "bytes ([0-9]+)-([0-9]+)/([0-9]+)"
    );

    enum Phase {
        DOWNLOADING,
        VERIFYING
    }

    interface Listener {
        void onProgress(Phase phase, long downloadedBytes, long totalBytes);

        void onReady(File modelFile);

        void onFailure(Throwable error);
    }

    static final class State {
        final boolean ready;
        final long downloadedBytes;
        final boolean hasStoredData;

        State(boolean ready, long downloadedBytes, boolean hasStoredData) {
            this.ready = ready;
            this.downloadedBytes = downloadedBytes;
            this.hasStoredData = hasStoredData;
        }
    }

    static final class NotEnoughSpaceException extends IOException {
        NotEnoughSpaceException(long requiredBytes, long availableBytes) {
            super("The model needs " + requiredBytes + " free bytes, but only "
                    + availableBytes + " are available.");
        }
    }

    static final class IntegrityException extends IOException {
        IntegrityException(String message) {
            super(message);
        }
    }

    static final class ModelBusyException extends IOException {
        ModelBusyException() {
            super("The Gemma model is still in use.");
        }
    }

    private final Context context;
    private final Executor callbackExecutor;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tapziq-gemma-model");
        thread.setDaemon(true);
        return thread;
    });
    private Future<?> activeTask;
    private volatile HttpURLConnection activeConnection;
    private int operationId;
    private boolean closed;

    GemmaModelStore(Context context, Executor callbackExecutor) {
        this.context = context.getApplicationContext();
        this.callbackExecutor = callbackExecutor;
    }

    synchronized State state() {
        File model = modelFile();
        if (isMarkedVerified(model)) {
            return new State(true, MODEL_SIZE_BYTES, true);
        }
        File partial = partialFile();
        long downloaded = partial.isFile() ? Math.min(partial.length(), MODEL_SIZE_BYTES) : 0L;
        if (model.isFile() && model.length() == MODEL_SIZE_BYTES) {
            downloaded = MODEL_SIZE_BYTES;
        }
        boolean hasStoredData = downloaded > 0L
                || model.exists()
                || partial.exists()
                || cacheDirectory().exists()
                || hasRuntimeCacheFiles();
        return new State(false, downloaded, hasStoredData);
    }

    synchronized File readyModelFile() {
        File model = modelFile();
        return isMarkedVerified(model) ? model : null;
    }

    synchronized void download(Listener listener) {
        ensureOpen();
        cancelLocked();
        int id = operationId;
        activeTask = worker.submit(() -> runDownload(id, listener));
    }

    synchronized void removeModel() throws IOException {
        ensureOpen();
        cancelLocked();
        if (!GemmaRuntimeCoordinator.tryAcquireModelMutation()) {
            throw new ModelBusyException();
        }
        try {
            deleteRuntimeCacheFiles();
            deleteTree(cacheDirectory());
            deleteIfPresent(modelFile());
            deleteIfPresent(partialFile());
            clearVerifiedMarker();
        } finally {
            GemmaRuntimeCoordinator.releaseModelMutation();
        }
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

    private void runDownload(int id, Listener listener) {
        boolean downloadLease = false;
        try {
            GemmaRuntimeCoordinator.acquireDownload();
            downloadLease = true;
            if (Thread.currentThread().isInterrupted() || !isCurrent(id)) {
                return;
            }
            File directory = modelDirectory();
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                throw new IOException("Could not create the private model directory.");
            }

            File model = modelFile();
            if (model.isFile()) {
                if (model.length() == MODEL_SIZE_BYTES) {
                    deliverProgress(id, listener, Phase.VERIFYING, MODEL_SIZE_BYTES);
                    verifyDigest(model);
                    markVerified();
                    deliverReady(id, listener, model);
                    return;
                }
                deleteInstalledModel(model);
            }

            File partial = partialFile();
            if (partial.isFile() && partial.length() > MODEL_SIZE_BYTES) {
                deleteIfPresent(partial);
            }
            long offset = partial.isFile() ? partial.length() : 0L;
            ensureFreeSpace(offset);

            if (offset < MODEL_SIZE_BYTES) {
                offset = transferModel(id, listener, partial, offset);
            }
            if (offset != MODEL_SIZE_BYTES || partial.length() != MODEL_SIZE_BYTES) {
                throw new IntegrityException("The downloaded model has an unexpected size.");
            }

            deliverProgress(id, listener, Phase.VERIFYING, MODEL_SIZE_BYTES);
            verifyDigest(partial);
            installVerifiedModel(partial, model);
            deliverReady(id, listener, model);
        } catch (Throwable error) {
            if (error instanceof IntegrityException) {
                discardIntegrityFailure();
            }
            if (!Thread.currentThread().isInterrupted()) {
                deliverFailure(id, listener, error);
            }
        } finally {
            disconnectActiveConnection();
            if (downloadLease) {
                GemmaRuntimeCoordinator.releaseDownload();
            }
        }
    }

    private void deleteInstalledModel(File model) throws IOException {
        GemmaRuntimeCoordinator.acquireInstalledFileMutation();
        try {
            deleteIfPresent(model);
            clearVerifiedMarker();
        } finally {
            GemmaRuntimeCoordinator.releaseInstalledFileMutation();
        }
    }

    private void installVerifiedModel(File partial, File model) throws IOException {
        GemmaRuntimeCoordinator.acquireInstalledFileMutation();
        try {
            installAtomically(partial, model);
            markVerified();
        } finally {
            GemmaRuntimeCoordinator.releaseInstalledFileMutation();
        }
    }

    private void discardIntegrityFailure() {
        GemmaRuntimeCoordinator.acquireInstalledFileMutation();
        try {
            try {
                deleteIfPresent(partialFile());
                deleteIfPresent(modelFile());
            } catch (IOException ignored) {
                // Preserve the original integrity failure for the user.
            }
            clearVerifiedMarker();
        } finally {
            GemmaRuntimeCoordinator.releaseInstalledFileMutation();
        }
    }

    private long transferModel(
            int id,
            Listener listener,
            File partial,
            long requestedOffset
    ) throws IOException {
        long offset = requestedOffset;
        HttpURLConnection connection = openConnection(id, offset);
        int responseCode = connection.getResponseCode();
        if (offset > 0 && responseCode == HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            clearActiveConnection(connection);
            deleteIfPresent(partial);
            offset = 0L;
            ensureFreeSpace(offset);
            connection = openConnection(id, 0L);
            responseCode = connection.getResponseCode();
        }

        validateResponse(responseCode, connection, offset);
        boolean append = offset > 0;
        long downloaded = offset;
        long lastUpdateAt = 0L;
        deliverProgress(id, listener, Phase.DOWNLOADING, downloaded);
        try (InputStream input = new BufferedInputStream(connection.getInputStream(), BUFFER_BYTES);
             FileOutputStream output = new FileOutputStream(partial, append)) {
            byte[] buffer = new byte[BUFFER_BYTES];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted() || !isCurrent(id)) {
                    throw new IOException("Model download cancelled.");
                }
                if (downloaded + count > MODEL_SIZE_BYTES) {
                    throw new IntegrityException("The server sent more model data than expected.");
                }
                output.write(buffer, 0, count);
                downloaded += count;
                long now = System.currentTimeMillis();
                if (now - lastUpdateAt >= 250L || downloaded == MODEL_SIZE_BYTES) {
                    deliverProgress(id, listener, Phase.DOWNLOADING, downloaded);
                    lastUpdateAt = now;
                }
            }
            output.getFD().sync();
        } finally {
            connection.disconnect();
            clearActiveConnection(connection);
        }
        return downloaded;
    }

    private HttpURLConnection openConnection(int id, long offset) throws IOException {
        URL current = URI.create(MODEL_URL).toURL();
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "Tapziq-Android");
            if (offset > 0) {
                connection.setRequestProperty("Range", "bytes=" + offset + "-");
            }
            if (!registerConnectionIfCurrent(id, connection)) {
                connection.disconnect();
                throw new InterruptedIOException("Model download cancelled.");
            }
            int code = connection.getResponseCode();
            if (code < 300 || code > 399) {
                if (!isCurrent(id) || Thread.currentThread().isInterrupted()) {
                    connection.disconnect();
                    clearActiveConnection(connection);
                    throw new InterruptedIOException("Model download cancelled.");
                }
                return connection;
            }
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            clearActiveConnection(connection);
            if (location == null || location.isBlank()) {
                throw new IOException("The model server returned a redirect without a location.");
            }
            URL next = new URL(current, location);
            if (!"https".equalsIgnoreCase(next.getProtocol()) || !isAllowedHost(next.getHost())) {
                throw new IOException("The model server redirected to an untrusted location.");
            }
            current = next;
        }
        throw new IOException("The model server returned too many redirects.");
    }

    private static boolean isAllowedHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("huggingface.co")
                || normalized.endsWith(".huggingface.co")
                || normalized.equals("hf.co")
                || normalized.endsWith(".hf.co");
    }

    static void validateResponse(int responseCode, HttpURLConnection connection, long offset)
            throws IOException {
        if (offset == 0L) {
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("The model server returned HTTP " + responseCode + ".");
            }
            long length = connection.getContentLengthLong();
            if (length != -1L && length != MODEL_SIZE_BYTES) {
                throw new IntegrityException("The model server reported an unexpected size.");
            }
            return;
        }
        if (responseCode != HttpURLConnection.HTTP_PARTIAL) {
            throw new IOException("The model server could not resume the download (HTTP "
                    + responseCode + ").");
        }
        String header = connection.getHeaderField("Content-Range");
        Matcher matcher = header == null ? null : CONTENT_RANGE.matcher(header);
        if (matcher == null || !matcher.matches()) {
            throw new IntegrityException("The model server returned an invalid content range.");
        }
        long start = Long.parseLong(matcher.group(1));
        long end = Long.parseLong(matcher.group(2));
        long total = Long.parseLong(matcher.group(3));
        if (start != offset || end < start || total != MODEL_SIZE_BYTES) {
            throw new IntegrityException("The model server returned the wrong content range.");
        }
    }

    private void ensureFreeSpace(long downloadedBytes) throws NotEnoughSpaceException {
        long remaining = MODEL_SIZE_BYTES - downloadedBytes;
        long required = Math.max(0L, remaining) + FREE_SPACE_MARGIN_BYTES;
        long available = new StatFs(modelDirectory().getAbsolutePath()).getAvailableBytes();
        if (available < required) {
            throw new NotEnoughSpaceException(required, available);
        }
    }

    private static void verifyDigest(File file) throws IOException {
        String actual = sha256(file);
        if (!MODEL_SHA256.equals(actual)) {
            throw new IntegrityException("The downloaded model failed its SHA-256 check.");
        }
    }

    static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError("SHA-256 is required by Android", error);
        }
        try (InputStream input = new BufferedInputStream(
                new FileInputStream(file), BUFFER_BYTES)) {
            byte[] buffer = new byte[BUFFER_BYTES];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Model verification cancelled.");
                }
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void installAtomically(File partial, File model) throws IOException {
        try {
            Files.move(
                    partial.toPath(),
                    model.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(
                    partial.toPath(),
                    model.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private boolean isMarkedVerified(File file) {
        if (!file.isFile() || file.length() != MODEL_SIZE_BYTES) {
            return false;
        }
        SharedPreferences preferences = preferences();
        return MODEL_REVISION.equals(preferences.getString(VERIFIED_REVISION, null))
                && MODEL_SHA256.equals(preferences.getString(VERIFIED_SHA256, null));
    }

    private void markVerified() throws IOException {
        boolean saved = preferences().edit()
                .putString(VERIFIED_REVISION, MODEL_REVISION)
                .putString(VERIFIED_SHA256, MODEL_SHA256)
                .commit();
        if (!saved) {
            throw new IOException("Could not persist the verified model state.");
        }
    }

    private void clearVerifiedMarker() {
        preferences().edit().clear().commit();
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private File modelDirectory() {
        return new File(context.getNoBackupFilesDir(), "models");
    }

    File cacheDirectory() {
        return new File(modelDirectory(), "cache");
    }

    private File modelFile() {
        return new File(modelDirectory(), MODEL_FILE_NAME);
    }

    private File partialFile() {
        return new File(modelDirectory(), MODEL_FILE_NAME + ".part");
    }

    private boolean hasRuntimeCacheFiles() {
        File[] files = modelDirectory().listFiles();
        if (files == null) {
            return false;
        }
        for (File file : files) {
            if (file.isFile() && isRuntimeCacheName(file.getName())) {
                return true;
            }
        }
        return false;
    }

    private void deleteRuntimeCacheFiles() throws IOException {
        File[] files = modelDirectory().listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isFile() && isRuntimeCacheName(file.getName())) {
                deleteIfPresent(file);
            }
        }
    }

    static boolean isRuntimeCacheName(String name) {
        return name.startsWith(MODEL_FILE_NAME + "_")
                && name.endsWith(".xnnpack_cache");
    }

    private synchronized boolean isCurrent(int id) {
        return !closed && id == operationId;
    }

    private synchronized boolean registerConnectionIfCurrent(
            int id,
            HttpURLConnection connection
    ) {
        if (Thread.currentThread().isInterrupted() || closed || id != operationId) {
            return false;
        }
        activeConnection = connection;
        return true;
    }

    private synchronized void clearActiveConnection(HttpURLConnection connection) {
        if (activeConnection == connection) {
            activeConnection = null;
        }
    }

    private synchronized void disconnectActiveConnection() {
        HttpURLConnection connection = activeConnection;
        if (connection != null) {
            connection.disconnect();
            activeConnection = null;
        }
    }

    private void deliverProgress(int id, Listener listener, Phase phase, long downloadedBytes) {
        callbackExecutor.execute(() -> {
            if (isCurrent(id)) {
                listener.onProgress(phase, downloadedBytes, MODEL_SIZE_BYTES);
            }
        });
    }

    private void deliverReady(int id, Listener listener, File model) {
        callbackExecutor.execute(() -> {
            if (isCurrent(id)) {
                listener.onReady(model);
            }
        });
    }

    private void deliverFailure(int id, Listener listener, Throwable error) {
        callbackExecutor.execute(() -> {
            if (isCurrent(id)) {
                listener.onFailure(error);
            }
        });
    }

    private void cancelLocked() {
        operationId++;
        disconnectActiveConnection();
        if (activeTask != null) {
            activeTask.cancel(true);
            activeTask = null;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Model store is closed.");
        }
    }

    private static void deleteIfPresent(File file) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException("Could not delete " + file.getName() + ".");
        }
    }

    private static void deleteTree(File root) throws IOException {
        if (!root.exists()) {
            return;
        }
        File[] children = root.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteTree(child);
                } else {
                    deleteIfPresent(child);
                }
            }
        }
        deleteIfPresent(root);
    }
}
