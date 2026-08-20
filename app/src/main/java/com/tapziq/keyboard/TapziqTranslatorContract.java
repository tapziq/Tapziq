package com.tapziq.keyboard;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** Android interoperability contract exposed by the separately installed Tapziq Translate app. */
final class TapziqTranslatorContract {
    static final String PACKAGE_NAME = "com.tapziq.translator";
    static final String DOWNLOAD_URL =
            "https://github.com/tapziq/tapziq-translator/releases/latest";
    static final String PROCESS_TEXT_ACTION = Intent.ACTION_PROCESS_TEXT;
    static final String PROCESS_TEXT_CATEGORY = Intent.CATEGORY_DEFAULT;
    static final String PROCESS_TEXT_TYPE = "text/plain";
    static final String PROCESS_TEXT_EXTRA = Intent.EXTRA_PROCESS_TEXT;
    static final String PROCESS_TEXT_READ_ONLY_EXTRA = Intent.EXTRA_PROCESS_TEXT_READONLY;
    static final boolean PROCESS_TEXT_READ_ONLY = false;

    private TapziqTranslatorContract() {
    }

    static Intent processText(String selectedText) {
        return new Intent(PROCESS_TEXT_ACTION)
                .setPackage(PACKAGE_NAME)
                .setType(PROCESS_TEXT_TYPE)
                .putExtra(PROCESS_TEXT_EXTRA, selectedText)
                .putExtra(PROCESS_TEXT_READ_ONLY_EXTRA, PROCESS_TEXT_READ_ONLY)
                .addCategory(PROCESS_TEXT_CATEGORY);
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
