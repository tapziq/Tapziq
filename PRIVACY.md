# Tapziq Privacy Notice

Last updated: August 13, 2026

Tapziq is an Android keyboard with optional, user-initiated proofreading powered
by Gemini Nano through Android AICore and the Google ML Kit GenAI Proofreading
API.

## Text handling

- Ordinary key presses go directly to the active Android text field. Tapziq does
  not store typing history or read the clipboard.
- Tapziq reads the active field only when you tap **Proofread with Gemini
  Nano**. It briefly hashes up to 2,000 characters in memory so it can verify
  that the field and selection have not changed before showing or applying a
  result. Only the selected passage, or the full field when nothing is
  selected, is sent to Android AICore; that proofreading input is limited to
  500 characters.
- The text and resulting suggestion are processed locally on the device through
  Android AICore. Google states that ML Kit does not send GenAI feature input or
  output content to Google servers.
- Tapziq keeps the text and suggestion only in memory long enough to show the
  result and, if you approve it, replace the original text. Tapziq does not save
  either one.
- Proofreading is disabled for password fields, email-address fields, fields
  marked as not accepting suggestions, and non-text fields.

## Google ML Kit data and network access

The bundled Google ML Kit SDK can use network access to receive model updates,
bug fixes, configuration, and hardware-compatibility information. It also sends
Google diagnostics and usage metrics, including device and app information,
identifiers, performance measurements, API configuration, input/output sizes,
feature events, error codes, feature version, and configured language. Google
states that this data is encrypted in transit and is not transferred to third
parties.

Tapziq does not operate its own analytics service, show ads, or sell user data.

Google's current disclosures are available in its
[ML Kit privacy terms](https://developers.google.com/ml-kit/terms) and
[Android data-disclosure guide](https://developers.google.com/ml-kit/android-data-disclosure).

## Availability and age

Gemini Nano proofreading works only on supported Android devices with a locked
bootloader and a compatible AICore installation. The first use may download
model assets. This release configures English keyboard input.

Google's ML Kit GenAI terms require users of this feature to be at least 18
years old. Do not use Tapziq's proofreading feature if you are under 18.

## Contact

Report privacy concerns through the Tapziq repository's GitHub issue tracker.
