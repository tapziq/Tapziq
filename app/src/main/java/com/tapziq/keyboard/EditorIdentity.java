package com.tapziq.keyboard;

import android.annotation.SuppressLint;
import android.os.Build;
import android.view.autofill.AutofillId;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputBinding;
import android.view.inputmethod.InputConnection;

import java.util.Objects;

/** Identifies the exact editor that supplied a proofreading request. */
final class EditorIdentity {
    private final int uid;
    private final int pid;
    private final String packageName;
    private final int fieldId;
    private final int inputType;
    private final AutofillId autofillId;
    private final InputConnection inputConnection;

    private EditorIdentity(
            int uid,
            int pid,
            String packageName,
            int fieldId,
            int inputType,
            AutofillId autofillId,
            InputConnection inputConnection
    ) {
        this.uid = uid;
        this.pid = pid;
        this.packageName = packageName;
        this.fieldId = fieldId;
        this.inputType = inputType;
        this.autofillId = autofillId;
        this.inputConnection = inputConnection;
    }

    static EditorIdentity capture(
            InputBinding binding,
            EditorInfo info,
            InputConnection inputConnection
    ) {
        if (binding == null || info == null || inputConnection == null) {
            return null;
        }
        AutofillId autofillId = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA
                ? Api36Impl.getAutofillId(info)
                : null;
        boolean hasStableFieldId = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA
                && Api36Impl.isVirtual(autofillId))
                || info.fieldId > 0;
        if (!hasStableFieldId) {
            return null;
        }
        return new EditorIdentity(
                binding.getUid(),
                binding.getPid(),
                info.packageName,
                info.fieldId,
                info.inputType,
                autofillId,
                inputConnection
        );
    }

    boolean matches(InputBinding binding, EditorInfo info, InputConnection currentConnection) {
        if (binding == null || info == null || currentConnection == null) {
            return false;
        }
        if (uid != binding.getUid()
                || pid != binding.getPid()
                || !Objects.equals(packageName, info.packageName)
                || inputType != info.inputType) {
            return false;
        }
        // Android 16 exposes the edited view's stable AutofillId. A virtual ID
        // identifies its child editor directly; a native-view ID is corroborated
        // with fieldId. Do not require wrapper identity here because Android can
        // recreate the IME's RemoteInputConnection while returning to the same view.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && autofillId != null) {
            if (!autofillId.equals(Api36Impl.getAutofillId(info))) {
                return false;
            }
            if (Api36Impl.isVirtual(autofillId)) {
                return true;
            }
            return fieldId > 0 && fieldId == info.fieldId;
        }
        // Older releases have no framework field identifier that survives a
        // reconnect, so retain the stricter connection-object requirement.
        return inputConnection == currentConnection
                && fieldId > 0
                && fieldId == info.fieldId;
    }

    @SuppressLint("NewApi")
    private static final class Api36Impl {
        private Api36Impl() {
        }

        static AutofillId getAutofillId(EditorInfo info) {
            return info.getAutofillId();
        }

        static boolean isVirtual(AutofillId autofillId) {
            return autofillId != null && autofillId.isVirtual();
        }
    }
}
