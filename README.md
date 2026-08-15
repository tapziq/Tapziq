# Tapziq Keyboard

Tapziq is an Android keyboard with a QWERTY layout, one-shot shift, numbers,
symbols, context-aware enter actions, Android's keyboard switch key, and
user-initiated proofreading powered on-device by Gemini Nano.

Ordinary key presses go straight to Android's active text field. Tapziq has no
ads, clipboard access, typing history, or Tapziq-owned analytics. Text submitted
for proofreading is processed locally through Android AICore and is not saved.
The Google ML Kit SDK can use network access for model/configuration updates and
sends Google non-content diagnostics and usage metrics. See the full
[privacy notice](PRIVACY.md).

## Proofread with Gemini Nano

In an ordinary text field, select a passage or leave the cursor in a short field,
then tap **Proofread with Gemini Nano**. Tapziq opens a brief foreground screen
while AICore checks spelling and grammar, returns to the editor, and displays the
best suggestion above the keyboard. Nothing changes until you tap **Apply**.

Proofreading currently uses English keyboard input and accepts up to 500
characters from active fields up to 2,000 characters long. It is disabled for
password, email-address, no-suggestions, and
non-text fields. It requires a supported device, compatible AICore, a locked
bootloader, and a user age of 18 or older. The first request may download model
assets. Unsupported devices keep the core keyboard fully usable and show a
clear unavailable message.

Tapziq does not hard-code a manufacturer or model. It asks AICore whether the
Proofreading feature is available at runtime, so the same APK works across
Google's current [supported-device list](https://developers.google.com/ml-kit/genai)
and fails closed elsewhere. The complete cross-app flow has been physically
verified on a locked US Galaxy S25 Ultra (SM-S938U) running Android 16.

## Install

Tapziq requires Android 8.0 or newer.

1. Download all four assets from the
   [latest GitHub release](https://github.com/tapziq/Tapziq/releases/latest).
2. In the download directory, run `sha256sum --check SHA256SUMS` on Linux or
   `shasum -a 256 --check SHA256SUMS` on macOS. Install the APK only if all
   three listed files pass verification.
3. Open **Tapziq Keyboard**, tap **Enable Tapziq Keyboard**, and enable it in
   Android's keyboard settings.
4. Return to Tapziq, tap **Choose Tapziq Keyboard**, and select it.
5. Tap the test field and type.

Android intentionally requires the user to enable and select every downloaded
keyboard. The app cannot bypass those system screens.

If you previously installed Tapziq's development debug APK, uninstall it before
installing the production release. Android cannot update a debug-signed
installation with the production-signed release, and you will need to
enable/select Tapziq again.

## Developer build

Requirements:

- Android SDK 36
- JDK 21 or newer

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:assembleDebug
```

The development-only debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Run the automated checks with:

```sh
./gradlew test lint
```

The debug APK is signed with Android's debug identity and must not be distributed
as a production release.

## Production build

Production packaging fails closed unless these four environment variables point
to a durable release keystore and its credentials:

```text
TAPZIQ_RELEASE_STORE_FILE
TAPZIQ_RELEASE_STORE_PASSWORD
TAPZIQ_RELEASE_KEY_ALIAS
TAPZIQ_RELEASE_KEY_PASSWORD
```

With those variables set, package a new, untagged stable version with:

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
scripts/package-semantic-release.sh 0.1.1 "$(git rev-parse HEAD)"
```

The clean commit used for a rehearsal must already declare the same
`tapziqSourceVersionName` and `tapziqSourceVersionCode` in
`app/build.gradle.kts`; release-time Gradle properties cannot mask stale source
metadata. The packaging path disables Gradle's configuration cache so signing
metadata. The packaging path disables Gradle's configuration cache so signing
passwords are not persisted there. It requires a clean Git worktree, runs the
unit tests and release lint, builds the signed APK, and checks its package,
version, source commit, signature, signer, exact permission allowlist, and
alignment. The expected production signing-certificate fingerprint is tracked
in
[`release/signing-certificate.sha256`](release/signing-certificate.sha256).
Verified assets are written to `dist/release/`.

## Automatic releases

Pushes to `main` are evaluated with Semantic Release. `fix:` and `perf:` commits
produce patch releases, `feat:` commits produce minor releases, and breaking
changes produce major releases. A release includes a brand-new production APK,
checksums, the license, third-party notices, generated notes, and a locked Git
tag. Before tagging, the bot writes the new `versionName` and Android
`versionCode` into `app/build.gradle.kts` and commits that source metadata. The
tag, APK, and updated `main` branch all resolve to that generated release commit.
See [`docs/RELEASING.md`](docs/RELEASING.md) for the complete contract.

## License

Tapziq is available under the [Apache License 2.0](LICENSE).
Third-party attributions are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
