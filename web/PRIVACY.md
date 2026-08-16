# Tapziq browser privacy

Last updated: August 15, 2026

This document describes the browser-only Tapziq PWA. The native Android
keyboard has a different platform boundary and is not covered here.

## Short version

Tapziq's browser application code does not send editor text, selections, or
Gemma suggestions to a Tapziq server or another inference service. Proofreading
runs locally in a dedicated browser worker with the downloaded Gemma 4 E2B-it
model and WebGPU. The on-page virtual keyboard works only in Tapziq's own
editor; it is not an Android system keyboard and cannot access text in other
apps, sites, fields, or tabs.

The site and model still require ordinary network requests for initial setup
and updates. Those requests reveal normal connection metadata to the relevant
host, but the application does not put editor text in them.

## Text and proofreading

- Text entered or pasted into the editor remains in the page and its dedicated
  worker's volatile memory.
- Tapziq sends only the selected passage, or the whole editor when eligible, to
  the local worker after the user chooses **Proofread**. The maximum request is
  500 UTF-16 code units.
- The local model's result stays in memory and appears as a preview. Tapziq
  changes the editor only after the user chooses **Apply**. **Dismiss** and
  **Cancel** do not change it.
- Tapziq rejects a suggestion if the original text or selection has changed.
- Tapziq application code does not persist editor contents, selections,
  prompts, model responses, typing history, or clipboard contents. Native
  browser spellcheck, autocomplete, and autocapitalize are disabled on the
  editor, and Tapziq does not request clipboard permission.

Closing or reloading the page discards the editor state maintained by Tapziq's
code. A browser or operating system may independently retain process memory,
session restoration data, swap, crash reports, or other artifacts; those
systems are outside Tapziq's application-level controls.

## Model and app storage

After explicit confirmation, Tapziq stores these items for the PWA's browser
origin:

- `gemma-4-E2B-it-web.litertlm`, including a partial file while a download is
  paused, in OPFS;
- verification metadata containing the model filename, pinned revision, byte
  count, SHA-256, LiteRT-LM version, file modification time, and verification
  time; and
- the app shell, worker, self-hosted LiteRT-LM WebAssembly/runtime files,
  manifest, styles, and icon in the browser's service-worker cache.

Tapziq asks the browser for persistent storage before downloading. A grant is
not guaranteed. The browser can evict non-persistent data, and a user can clear
site data at any time.

This is browser-managed, origin-scoped storage. It is separate from the native
Android app's private files and does not use Gemini Nano, Android AICore, or ML
Kit. Other websites ordinarily cannot read another origin's OPFS or cache, but
the boundary does not prevent access by the browser itself, the operating
system, sufficiently privileged extensions or administrators, developer tools,
malware, or anyone who controls the browser profile or device.

## Network activity

The browser may connect to:

1. **The Tapziq app origin** to load or update HTML, JavaScript, CSS, the icon,
   manifest, service worker, and self-hosted LiteRT-LM WebAssembly/runtime.
2. **Hugging Face model hosting** only after the user confirms the model
   download, to retrieve the exact pinned model or resume its remaining bytes.
   The request omits credentials, bypasses HTTP cache, and sends no referrer.

The model host and app host can receive ordinary HTTP and transport metadata,
such as IP address, time, requested path, response size, and browser/network
headers. Hosting providers may keep access or security logs according to their
own policies. Tapziq's source does not add editor text, suggestions, or prompts
to request URLs, headers, or bodies.

Once the current app shell, runtime, and verified model are present, inference
does not require a network request. Tapziq contains no account system,
advertising SDK, analytics SDK, telemetry endpoint, or remote inference client,
and its application code does not write cookies.

## Removal and user controls

- Choose **Remove browser model** to remove the complete or partial model and
  its verification metadata from OPFS.
- Use the browser's controls for this site's storage to remove the model, app
  cache, service worker, and other origin data together.
- Uninstalling an installed PWA does not clear site data in every browser; use
  the site-data control when deletion matters.
- Closing the page clears Tapziq's in-memory editor and suggestion, subject to
  the browser and operating-system caveats above.

Browser controls use different names, commonly **Site settings**, **Storage**,
**Clear data**, or **Delete site data**.

## Scope and changes

This statement covers the behavior of the source in this browser edition. A
deployment operator can still observe normal requests to the host it operates,
and browser extensions or modified builds can change the boundary. Review the
deployed origin and its browser permissions when that distinction matters.

Material privacy changes should update this document and its
[standalone HTML copy](./public/PRIVACY.html) with a new date.

See also the [browser edition overview](./README.md) and
[third-party notices](./THIRD_PARTY_NOTICES.md).
