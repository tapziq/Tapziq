[![Verify and Release](https://github.com/tapziq/Tapziq/actions/workflows/release.yml/badge.svg)](https://github.com/tapziq/Tapziq/actions/workflows/release.yml)
# Tapziq Keyboard

Tapziq is a deliberately small Android keyboard. It provides a QWERTY layout,
one-shot shift, numbers, symbols, backspace, space, context-aware enter actions,
and Android's keyboard switch key.

The app has no internet permission, analytics, ads, clipboard access, or typing
history. All key presses go straight to Android's active text field through the
system input-method connection.

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
- JDK 17 or newer

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
passwords are not persisted there. It runs the unit tests and release lint,
builds the signed APK, and checks its package, version, source commit, signature,
signer, permissions, and alignment. The expected production signing-certificate
fingerprint is tracked in
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
