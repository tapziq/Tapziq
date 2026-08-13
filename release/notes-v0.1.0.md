This is the first production release of Tapziq, a deliberately small and private
Android keyboard.

## Included

- QWERTY letters with one-shot shift
- Numbers and common symbols
- Backspace, space, and context-aware enter actions
- Android's keyboard-switch key when another input method is available
- A guided setup screen with a local typing field
- No internet permission, analytics, ads, clipboard access, or typing history

## Install

Download `Tapziq-v0.1.0.apk` below. Tapziq requires Android 8.0 or newer.
Android will show its standard warning when any downloaded keyboard is enabled;
Tapziq cannot enable or select itself.

If you previously installed the development debug APK, uninstall it before
installing `v0.1.0`. Android cannot update a debug-signed installation with this
production-signed APK; enable and select Tapziq again after reinstalling.

The APK is a non-debuggable production build signed by the Tapziq release key.
Verify the download with `SHA256SUMS`. `LICENSE.txt` and
`THIRD_PARTY_NOTICES.md` accompany the binary. Its signing-certificate SHA-256
fingerprint is:

```text
83:D8:CB:8D:8C:5E:4C:89:4D:72:B7:94:8A:2C:0A:2E:FB:C0:9B:4A:59:5D:40:BE:52:E1:1D:72:06:C3:2D:7A
```
