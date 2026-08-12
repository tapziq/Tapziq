# Tapziq Keyboard

Tapziq is a deliberately small Android keyboard. It provides a QWERTY layout,
one-shot shift, numbers, symbols, backspace, space, context-aware enter actions,
and Android's keyboard switch key.

The app has no internet permission, analytics, ads, clipboard access, or typing
history. All key presses go straight to Android's active text field through the
system input-method connection.

## Build

Requirements:

- Android SDK 36
- JDK 17 or newer

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Run the automated checks with:

```sh
./gradlew test lint
```

## Install and enable

1. Install the debug APK with Android Studio or `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
2. Open **Tapziq Keyboard** from the app launcher.
3. Tap **Enable Tapziq Keyboard** and enable it in Android's keyboard settings.
4. Return to the app, tap **Choose Tapziq Keyboard**, and select it.
5. Tap the test field and type.

Android intentionally requires the user to enable and select every downloaded
keyboard. The app cannot bypass those system screens.
