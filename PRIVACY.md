# Tapziq Privacy Notice

Last updated: August 16, 2026

This notice covers Tapziq's installed Android system keyboard. The separate PWA
is governed by the [Tapziq browser privacy notice](web/PRIVACY.md); its virtual
keyboard is confined to its own browser page and does not replace the Android
system keyboard.

Tapziq is an Android keyboard with optional, user-initiated proofreading and a
separate, explicitly enabled autocorrect feature powered by an app-private
Gemma 4 E2B-it model through the LiteRT-LM runtime.

## Text handling

- Ordinary key presses go directly to the active Android text field. Tapziq does
  not store typing history or read the clipboard. Gemma autocorrect is off by
  default.
- When you tap **Proofread with Gemma 4**, Tapziq reads the active field and
  briefly hashes up to 2,000 characters in memory so it can verify that the
  field and selection have not changed before showing or applying a result.
  Only the selected passage, or the full field when nothing is selected, is
  given to the local model; that proofreading input is limited to 500
  characters.
- When you explicitly enable **Use local Gemma 4 autocorrect**, a Tapziq Space,
  completion-punctuation, or multiline Enter key can schedule a local check
  after a short pause. Tapziq briefly retains and compares the exact active-field
  snapshot, up to 2,000 characters, and gives only the most recent completed
  passage, up to 500 characters, to the local model.
- An automatic result is applied only if autocorrect is still enabled and the
  editor identity, complete field, selection, and caret remain exactly
  unchanged. A new Tapziq key cancels pending work. Backspace immediately after
  an applied correction can restore the original text.
- The text and resulting suggestion are processed locally in Tapziq's process.
  Tapziq does not put editor text into a network request, URL, log, model
  download, analytics event, or crash report.
- Tapziq keeps the text and suggestion only in memory long enough to show the
  manual result or safely complete an enabled automatic check. Tapziq does not
  save either one.
- Proofreading and Gemma autocorrect are disabled for password fields,
  email-address fields, URI fields, person-name fields, fields marked as not
  accepting suggestions, and non-text fields.

## Model storage and network access

Tapziq accesses the network only after the user confirms the model download in
the setup screen. It downloads one pinned Gemma 4 E2B-it LiteRT-LM file over
HTTPS from Hugging Face. The downloader supports resuming a partial file and
does not install it until its exact size and SHA-256 checksum match the values
pinned in Tapziq's source. Download requests contain ordinary HTTP metadata such
as Tapziq's user agent and the device's IP address; they never contain keyboard
or proofreading text.

The verified model is stored in Tapziq's app-private, no-backup directory.
Tapziq does not enable LiteRT-LM's persistent optimized-weight cache. Other
ordinary apps and browsers cannot access the model directory. Android removes
it when Tapziq is uninstalled, and the user can remove the model or a partial
download from Tapziq's setup screen. The model is not included in Android cloud
backup.

Tapziq does not operate its own analytics service, show ads, or sell user data.

## Runtime and availability

Gemma 4 proofreading and autocorrect require a compatible 64-bit Android device,
about 2.59 GB for the model plus download headroom, and enough memory to load the
model. This release configures English correction. A missing, corrupt, or
unsupported model fails closed and leaves ordinary keyboard input available.

## Contact

Report privacy concerns through the Tapziq repository's GitHub issue tracker.
