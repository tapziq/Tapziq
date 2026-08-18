package com.tapziq.keyboard;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** Android interoperability contract exposed by David Ventura's Offline Translator app. */
final class OfflineTranslatorContract {
    static final String PACKAGE_NAME = "dev.davidv.translator";
    static final String DOWNLOAD_URL =
            "https://f-droid.org/packages/dev.davidv.translator/";

    private OfflineTranslatorContract() {
    }

    static Intent processText(String selectedText) {
        return new Intent(Intent.ACTION_PROCESS_TEXT)
                .setPackage(PACKAGE_NAME)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, selectedText)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
                .addCategory(Intent.CATEGORY_DEFAULT);
    }

    @SuppressWarnings("deprecation")
    static boolean isAvailable(Context context) {
        return processText("").resolveActivity(
                context.getPackageManager()
        ) != null;
    }

    static Intent launchIntent(Context context) {
        return context.getPackageManager().getLaunchIntentForPackage(PACKAGE_NAME);
    }

    static Intent downloadIntent() {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL));
    }
}
