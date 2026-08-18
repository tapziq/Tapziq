# Tapziq Privacy Notice

Last updated: August 17, 2026

This notice covers Tapziq's installed Android system keyboard. The separate PWA
is governed by the [Tapziq browser privacy notice](web/PRIVACY.md); its virtual
keyboard is confined to its own browser page and does not replace the Android
system keyboard.

Tapziq is an Android keyboard with optional, user-initiated proofreading and a
separate, explicitly enabled autocorrect feature powered by an app-private
Gemma 4 E2B-it model through the LiteRT-LM runtime.

## Text handling

- Ordinary key presses go directly to the active Android text field. Tapziq does
  not store general typing history or read the clipboard. Gemma autocorrect and
  correction learning are separate features and are both off by default.
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
- Unless correction learning is separately enabled, Tapziq keeps the text and
  suggestion only in memory long enough to show the manual result or safely
  complete an enabled automatic check. Tapziq does not save either one.
- When **Learn from rejected Gemma corrections** is enabled, dismissing or typing
  over a reviewed one-word result or immediately undoing an autocorrection stores
  only the exact written and rejected word. In a compatible editor that reports
  genuine view taps to Android IMEs, tapping a recent corrected word shows a
  Tapziq-owned original-word candidate above the keyboard. Using it explicitly
  reverses and rejects the Gemma correction; dismissing it or tapping away without
  using it also stores the rejection. If the same word is then edited with
  Tapziq, Tapziq can also store that replacement after a word boundary or after
  the selection moves away. It does not store the surrounding sentence, field,
  app/package identity, timestamp, or a general typing log in the persistent word
  record.
- To recognize a tap after typing continues, Tapziq keeps only the word mapping,
  its range, and hashed short context for at most eight recent one-word
  autocorrections plus the exact active-editor identity in memory. Each can start
  a review for up to two minutes while that editor remains active. Once opened,
  the anchored review and replacement session expires after 30 seconds without
  related activity; lifecycle changes abort it rather than saving a partial word.
- Correction memory is limited to 100 exact-case word-level records in
  app-private storage. Relevant records are provided only to the on-device model
  and exact rejected mappings are blocked before application. The setup screen
  reports the record count and can clear the memory at any time. Disabling the
  learning switch stops recording and using it but does not silently delete it.
- Proofreading and Gemma autocorrect are disabled for password fields,
  email-address fields, URI fields, person-name fields, fields marked as not
  accepting suggestions, and non-text fields.
- Correction learning also remains disabled whenever the editor sets Android's
  `IME_FLAG_NO_PERSONALIZED_LEARNING` flag.

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

Learned correction records are stored separately in app-private application
preferences. Application backup and device transfer are disabled for all Tapziq
data, and Android removes the records when Tapziq is uninstalled. Removing the
model does not remove learned records; **Clear learned corrections** does.

Tapziq does not operate its own analytics service, show ads, or sell user data.

## Runtime and availability

Gemma 4 proofreading and autocorrect require a compatible 64-bit Android device,
about 2.59 GB for the model plus download headroom, and enough memory to load the
model. This release configures English correction. A missing, corrupt, or
unsupported model fails closed and leaves ordinary keyboard input available.

## Contact

Report privacy concerns through the Tapziq repository's GitHub issue tracker.
