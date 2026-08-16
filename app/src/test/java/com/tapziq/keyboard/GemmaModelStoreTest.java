package com.tapziq.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

import org.junit.Test;

public final class GemmaModelStoreTest {
    @Test
    public void modelArtifactIsPinnedByRevisionSizeAndDigest() {
        assertEquals(40, GemmaModelStore.MODEL_REVISION.length());
        assertEquals(64, GemmaModelStore.MODEL_SHA256.length());
        assertEquals(2_588_147_712L, GemmaModelStore.MODEL_SIZE_BYTES);
        assertEquals(
                "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/"
                        + GemmaModelStore.MODEL_REVISION + "/gemma-4-E2B-it.litertlm",
                GemmaModelStore.MODEL_URL
        );
    }

    @Test
    public void sha256UsesTheFullFile() throws Exception {
        File file = File.createTempFile("tapziq-model-test", ".bin");
        try {
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write("Tapziq".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            assertEquals(
                    "84318585935a92a4b668ac1378ccde7d258ad73cdc875cfacbc4141f98dcaf75",
                    GemmaModelStore.sha256(file)
            );
        } finally {
            file.delete();
        }
    }

    @Test
    public void fullDownloadRequiresThePinnedSize() throws Exception {
        FakeConnection correct = new FakeConnection(
                HttpURLConnection.HTTP_OK,
                GemmaModelStore.MODEL_SIZE_BYTES,
                null
        );
        GemmaModelStore.validateResponse(HttpURLConnection.HTTP_OK, correct, 0L);

        FakeConnection wrong = new FakeConnection(
                HttpURLConnection.HTTP_OK,
                GemmaModelStore.MODEL_SIZE_BYTES - 1L,
                null
        );
        assertThrows(
                GemmaModelStore.IntegrityException.class,
                () -> GemmaModelStore.validateResponse(HttpURLConnection.HTTP_OK, wrong, 0L)
        );
    }

    @Test
    public void resumedDownloadRequiresAnExactContentRange() throws Exception {
        long offset = 1_000L;
        FakeConnection correct = new FakeConnection(
                HttpURLConnection.HTTP_PARTIAL,
                GemmaModelStore.MODEL_SIZE_BYTES - offset,
                "bytes " + offset + "-" + (GemmaModelStore.MODEL_SIZE_BYTES - 1L)
                        + "/" + GemmaModelStore.MODEL_SIZE_BYTES
        );
        GemmaModelStore.validateResponse(HttpURLConnection.HTTP_PARTIAL, correct, offset);

        FakeConnection wrong = new FakeConnection(
                HttpURLConnection.HTTP_PARTIAL,
                GemmaModelStore.MODEL_SIZE_BYTES - offset,
                "bytes 0-99/" + GemmaModelStore.MODEL_SIZE_BYTES
        );
        assertThrows(
                GemmaModelStore.IntegrityException.class,
                () -> GemmaModelStore.validateResponse(
                        HttpURLConnection.HTTP_PARTIAL,
                        wrong,
                        offset
                )
        );
    }

    @Test
    public void onlyThePinnedModelsGeneratedRuntimeCacheIsRemovable() {
        assertEquals(
                true,
                GemmaModelStore.isRuntimeCacheName(
                        "gemma-4-E2B-it.litertlm_1786832828_2588147712.xnnpack_cache"
                )
        );
        assertEquals(
                false,
                GemmaModelStore.isRuntimeCacheName("another-model.litertlm_1.xnnpack_cache")
        );
        assertEquals(
                false,
                GemmaModelStore.isRuntimeCacheName("gemma-4-E2B-it.litertlm")
        );
    }

    private static final class FakeConnection extends HttpURLConnection {
        private final long contentLength;
        private final String contentRange;

        FakeConnection(int responseCode, long contentLength, String contentRange)
                throws IOException {
            super(new URL("https://example.test/model"));
            this.responseCode = responseCode;
            this.contentLength = contentLength;
            this.contentRange = contentRange;
        }

        @Override
        public long getContentLengthLong() {
            return contentLength;
        }

        @Override
        public String getHeaderField(String name) {
            return "Content-Range".equalsIgnoreCase(name) ? contentRange : null;
        }

        @Override
        public void disconnect() {
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
        }
    }
}
