package com.tapziq.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.content.Intent;

import org.junit.Test;

public final class TapziqTranslatorContractTest {
    @Test
    public void pinsCompanionPackageAndOfficialDownloadPage() {
        assertEquals("com.tapziq.translator", TapziqTranslatorContract.PACKAGE_NAME);
        assertEquals(
                "https://github.com/tapziq/tapziq-translator/releases/latest",
                TapziqTranslatorContract.DOWNLOAD_URL
        );
    }

    @Test
    public void pinsMutablePlainTextInteropContract() {
        assertEquals(Intent.ACTION_PROCESS_TEXT, TapziqTranslatorContract.PROCESS_TEXT_ACTION);
        assertEquals(Intent.CATEGORY_DEFAULT, TapziqTranslatorContract.PROCESS_TEXT_CATEGORY);
        assertEquals("text/plain", TapziqTranslatorContract.PROCESS_TEXT_TYPE);
        assertEquals(Intent.EXTRA_PROCESS_TEXT, TapziqTranslatorContract.PROCESS_TEXT_EXTRA);
        assertEquals(
                Intent.EXTRA_PROCESS_TEXT_READONLY,
                TapziqTranslatorContract.PROCESS_TEXT_READ_ONLY_EXTRA
        );
        assertFalse(TapziqTranslatorContract.PROCESS_TEXT_READ_ONLY);
    }
}
