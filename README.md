[![Verify and Release](https://github.com/tapziq/Tapziq/actions/workflows/release.yml/badge.svg)](https://github.com/tapziq/Tapziq/actions/workflows/release.yml)
# Tapziq Keyboard

Tapziq is an Android keyboard with a QWERTY layout, one-shot shift, numbers,
symbols, context-aware enter actions, Android's keyboard switch key, and
user-initiated proofreading plus optional autocorrect powered on-device by
Gemma 4 E2B-it. It can also send an explicitly highlighted passage to the
separately installed Tapziq Translate app and safely return the chosen
English-Spanish translation to the original field.

The repository also contains a separate [browser-only edition](web/README.md).
It stores the web-specific Gemma model in origin-private browser storage and
runs proofreading and optional autocorrect in a local WebGPU worker. Its
virtual keyboard works only inside that page: browser sandboxing does not allow
a PWA to become Android's system-wide keyboard or read another app's text field.

Ordinary key presses go straight to Android's active text field. Gemma reads
editor text only for user-requested proofreading or after the user explicitly
enables Gemma autocorrect. Tapziq has no ads, clipboard access, general typing
history, or Tapziq-owned analytics. Editor passages processed by Tapziq's
app-private model are not saved or sent over the network. An optional, separate
learning switch can save only bounded word-level correction preferences; those
stay app-private and can be cleared at any time. Tapziq uses network access only
when the user explicitly downloads the model. See the full [privacy notice](PRIVACY.md).

## Translate selected text with Tapziq Translate

Install [Tapziq Translate](https://github.com/tapziq/tapziq-translator) from its
[latest GitHub release](https://github.com/tapziq/tapziq-translator/releases/latest).
This integration requires Tapziq Translate v0.3.0 or newer.
It is a deliberately tiny offline English-Spanish translator: a handwritten
dictionary is bundled directly in the separate app, so it downloads no language
packs, uses no model or translation API, and makes no network requests. Unknown
vocabulary stays unchanged. Tapziq Keyboard does not bundle the companion app,
its code, or its dictionary.

In a compatible text field, highlight the exact passage to translate and tap
**Translate** above Tapziq's letter keys. Tapziq Translate opens with that
passage; choose English to Spanish or Spanish to English, translate it, and
return the result. Tapziq then returns to the source editor and displays a
translation preview above the keyboard; the field does not change until you tap
**Apply**.

Tapziq accepts selections up to 500 characters in complete fields up to 2,000
characters long. Tapziq records neither the passage nor the result. The original
app process, field identity, full field snapshot, and selection must still match
before Tapziq shows the preview and again before Apply. A changed field fails
closed. Android may recreate its connection wrapper during the companion round
trip, so Tapziq accepts a reconnected editor only when the app process, stable
field identity and type, complete field snapshot, and exact selection all still
match. On Android 8–15, the reported field identity is normally a view resource
ID rather than Android 16's stable Autofill ID. If an app swaps in a different,
identical editor that deliberately reuses every one of those values, Android's
Process Text return does not expose enough information to distinguish it; check
the visible field before tapping **Apply**. Android 16 uses the stable Autofill ID.

## Proofread with Gemma 4

First open **Tapziq Keyboard** and download the pinned 2.59 GB Gemma 4 E2B-it
model. Tapziq supports interrupted downloads, verifies the exact byte count and
SHA-256 digest, and installs the model only after verification. The model is
stored under Tapziq's private, no-backup app storage and can be removed from the
same setup screen. Once installed, proofreading works offline.

In an ordinary text field, select a passage or leave the cursor in a short
field, then tap **Proofread with Gemma 4**. Tapziq opens a foreground screen
while its local LiteRT-LM runtime checks spelling and grammar, returns to the
editor, and displays the suggestion above the keyboard. Model loading and
generation time depend strongly on the device CPU and available memory. Nothing
changes until you tap **Apply**.

Proofreading currently uses English keyboard input and accepts up to 500
characters from active fields up to 2,000 characters long. It is disabled for
password, email-address, no-suggestions, and non-text fields. Gemma 4 requires a
64-bit Android device with substantial free storage and memory. If the model is
missing or the device cannot load it, proofreading fails closed without changing
the active field.

The model is the Apache-2.0
[`gemma-4-E2B-it.litertlm`](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)
artifact at a pinned repository revision. Inference uses Google's open-source
[LiteRT-LM](https://developers.google.com/edge/litert-lm) Android runtime; Tapziq
does not use Gemini Nano, Android AICore, or ML Kit GenAI.

## Autocorrect with Gemma 4

Gemma autocorrect is a separate opt-in setting and is off by default. After the
verified model is installed, open **Tapziq Keyboard** and turn on **Use local
Gemma 4 autocorrect**. Tapziq then waits briefly after a Tapziq Space,
completion-punctuation, or multiline Enter key before checking the most recent
completed passage with the same app-private model.

An automatic correction is applied only if the active editor, complete field,
and caret are still exactly unchanged. A new Tapziq key cancels pending work,
and Backspace immediately after an applied correction restores the original
text. Autocorrect shares proofreading's 500-character input and 2,000-character
field limits and its secure-field exclusions. Model loading and generation can
take noticeably longer than dictionary-based autocorrect, especially on its
first use after the keyboard opens. Tapziq reuses the initialized engine for
later checks while that keyboard view remains visible, then releases it.

### Learn from rejected corrections

**Learn from rejected Gemma corrections** is a separate setting and is off by
default. When enabled, dismissing a reviewed one-word Gemma change, typing over
that review, or immediately undoing an automatic correction records the exact
written/rejected word pair. In a compatible editor that reports view taps to
Android IMEs, tapping a recent autocorrected word shows Tapziq's original-word
candidate above the keyboard. Using that candidate explicitly reverses and
rejects the Gemma correction; dismissing it or tapping away without using it also
records the rejection. If the user edits that same word with Tapziq, Tapziq
learns the final replacement when the user types a word boundary or moves the
selection away. To recognize a later tap after typing has continued, Tapziq keeps
hashed context for at most eight recent one-word autocorrections in memory and
allows each to start a review for up to two minutes while that editor remains
active. Once opened, the anchored review and same-word replacement session expires
after 30 seconds without related typing or taps. Unrelated typing is never
associated with the rejected result.

Tapziq uses matching entries as local Gemma prompt preferences and also blocks
an exact correction that was previously rejected. This is bounded local
preference memory, not runtime fine-tuning of the Gemma model weights. The app
retains at most 100 exact-case word records—never the surrounding sentence,
app/package name, or a general typing log—in app-private storage excluded from
backup and device transfer. Learning remains disabled in secure, no-suggestions, and
`IME_FLAG_NO_PERSONALIZED_LEARNING` fields. The setup screen shows the number of
stored entries and provides **Clear learned corrections**.

## Browser-owned edition

The isolated `web/` project is a self-contained PWA with its own lockfile. It
downloads the pinned `gemma-4-E2B-it-web.litertlm` artifact only after explicit
confirmation, resumes partial downloads in origin-private file storage, checks
the exact 2,008,432,640-byte length and SHA-256 digest, and exposes only verified
bytes to LiteRT-LM. Runtime JavaScript and WebAssembly are self-hosted. Editor
text and suggestions remain in page/worker memory and are never put into model
download requests or browser persistence. Its separate, session-only Gemma
autocorrect toggle is also off by default and responds only to that page's
virtual-key boundaries.

```bash
cd web
npm ci --ignore-scripts
npm test
npm run dev
```

Use a secure-context Chromium browser with WebGPU, origin-private file storage,
Web Locks, at least 2.52 GB of free browser quota, and enough GPU memory. See the
[browser privacy boundary](web/PRIVACY.md) and [browser build and verification
guide](web/README.md).

## Install

Tapziq requires a 64-bit device running Android 8.0 or newer.

1. Download all four assets from the
   [latest GitHub release](https://github.com/tapziq/Tapziq/releases/latest).
2. In the download directory, run `sha256sum --check SHA256SUMS` on Linux or
   `shasum -a 256 --check SHA256SUMS` on macOS. Install the APK only if all
   three listed files pass verification.
3. Open **Tapziq Keyboard**, tap **Enable Tapziq Keyboard**, and enable it in
   Android's keyboard settings.
4. Return to Tapziq, tap **Choose Tapziq Keyboard**, and select it.
5. To use proofreading or Gemma autocorrect, tap **Download Gemma 4 E2B-it** in
   Tapziq and keep the screen open until the download and checksum verification
   finish. You can explicitly pause and later resume the transfer. Wi-Fi and at
   least 3 GB of free space are recommended.
6. To use translation, install or update Tapziq Translate from Tapziq's setup
   screen. Its tiny English-Spanish dictionary is already bundled in that app.
7. Optional: turn on **Use local Gemma 4 autocorrect**. It remains off unless
   you explicitly enable it.
8. Tap the test field and type.

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
tag. Before changing a remote ref, the bot writes the new `versionName` and
Android `versionCode` into `app/build.gradle.kts`, creates a local candidate
commit, and builds and smoke-tests the signed APK from it. Only a passing
candidate is pushed and tagged, so the tag, APK, and updated `main` branch all
resolve to that generated release commit.
See [`docs/RELEASING.md`](docs/RELEASING.md) for the complete contract.

## License

Tapziq is available under the [Apache License 2.0](LICENSE).
Third-party attributions are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
