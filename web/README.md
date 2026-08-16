# Tapziq Browser Keyboard

Tapziq's browser edition is an installable progressive web app with an on-page
virtual keyboard and a proofreading editor. After an explicit download, it runs
the pinned **Gemma 4 E2B-it** web model locally through LiteRT-LM and WebGPU.

## Scope: a PWA, not a system keyboard

The virtual keyboard edits only the text area inside the Tapziq page. A web app
cannot register itself as Android's `InputMethodService`, replace the selected
Android keyboard, or read and edit fields in other apps, sites, or browser tabs.
The native Android Tapziq keyboard is a separate application and architecture.

This browser edition does not call Gemini Nano, Android AICore, ML Kit, or a
remote inference API. It downloads the web-specific Gemma artifact into storage
managed by the browser for this site and performs proofreading in a dedicated
browser worker.

## What "browser-managed" means

| Component | Location and lifetime |
| --- | --- |
| App and LiteRT-LM runtime | Served by the Tapziq site and cached for this browser origin by the service worker. The runtime files are self-hosted; inference does not load code from a CDN. |
| Gemma model | Downloaded only after confirmation, written to the origin-private file system (OPFS), and accepted only after its size and SHA-256 match the pinned artifact. It remains until the user removes it, clears this site's data, or the browser evicts it. |
| Editor text and suggestions | Kept in the page and worker's volatile memory. Tapziq's application code does not save them to OPFS, Cache Storage, local storage, cookies, or a server. |
| Inference | Runs in the page's dedicated worker with WebGPU. There is no Tapziq inference backend. |

Browser-managed is a technical storage and execution boundary, not a claim that
the browser is an impenetrable vault or that ownership of third-party software
or model weights changes. The browser, operating system, browser extensions or
administrators with sufficient access, developer tools, and anyone controlling
the browser profile may be able to inspect or delete site data. See
[Browser privacy](./PRIVACY.md) for the exact boundary.

## Requirements

- A secure HTTPS origin (or `localhost`). Opening the files through `file://` is
  not supported.
- A current browser that exposes WebGPU, an available GPU adapter, OPFS, and Web
  Locks. LiteRT-LM's web API is currently an early preview.
- At least 2,008,432,640 bytes for the model, plus 512 MiB of free browser quota
  during setup. A published WebGPU benchmark reports roughly 1.8 GB of GPU
  memory on an M4 Max; actual device use can differ.
- Permission from the browser to retain enough site storage. Tapziq requests
  persistent storage, but browsers may decline and may later evict best-effort
  data.

## Use

1. Open the app while online and choose **Download verified model**. The app
   shows the remaining size before any model request begins.
2. Leave the page open while the model downloads. A paused partial download can
   be resumed. Tapziq checks every byte against the pinned SHA-256 before the
   model becomes usable.
3. Type with the page's virtual keys or paste text into the editor.
4. Select up to 500 UTF-16 code units, or leave the selection collapsed to use
   the whole editor when it is no longer than 500 code units. Choose
   **Proofread with browser Gemma 4**.
5. Review the preview. Only **Apply** changes the editor; **Dismiss** and
   **Cancel** leave it unchanged. If the source text or selection changed while
   the model was working, Tapziq rejects the stale suggestion.

After the app shell, LiteRT-LM runtime, and verified model have all loaded once,
proofreading can run without a network connection. An app update, missing
cached runtime file, removed model, cleared data, or browser eviction requires
network access again.

Use **Remove browser model** to delete the complete or partial model and its
verification metadata. To remove the app shell and all remaining origin data,
use the browser's site-data controls. Uninstalling the PWA alone does not remove
site storage in every browser.

## Pinned model and runtime

- Model repository:
  [`litert-community/gemma-4-E2B-it-litert-lm`](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)
- File: `gemma-4-E2B-it-web.litertlm`
- Revision: `6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94`
- Size: `2,008,432,640` bytes
- SHA-256: `3a08e8d94e23b814ae5414469c370c503813949acb8ceaa17e4ebf8a35af35b5`
- Runtime: `@litert-lm/core` `0.15.0`

The model is not bundled in the source tree or production app bundle. The
browser downloads it directly from the immutable Hugging Face revision with
credentials omitted and no referrer, then verifies it locally.

## Development

Use Node.js `>=24.15.0 <25` as declared by `package.json`.

```sh
npm ci
npm run dev
```

The development server listens on `http://127.0.0.1:4173`. Production hosting
must use HTTPS and send the cross-origin isolation and security headers defined
in `public/_headers` (or equivalent headers on another host).

Verification commands:

```sh
npm test
npm run build
npm run test:e2e
npm run audit
```

The opt-in real-browser gate downloads the full 2.01 GB artifact into the
explicit Chrome test profile, performs online proofreading, reloads with the
browser forced offline, performs another proofreading request, and audits
network requests plus the installed OPFS metadata. Start `npm run preview`
after a production build, then run in another terminal:

```sh
TAPZIQ_REAL_MODEL_TEST=1 \
TAPZIQ_CHROME_PROFILE=/absolute/path/to/a/dedicated-test-profile \
npm run verify:real
```

The verifier does not delete that profile or its downloaded model.

The browser's real model path deliberately has no synthetic fallback. The fake
model used by end-to-end tests is compiled only by `npm run build:test` and
requires the explicit test query parameter.

## Privacy and licenses

- [Browser privacy](./PRIVACY.md)
- [Third-party notices](./THIRD_PARTY_NOTICES.md)
- [Standalone browser overview](./public/README.html)
- [Standalone privacy copy](./public/PRIVACY.html)
- [Standalone third-party notices](./public/THIRD_PARTY_NOTICES.html)
- [Tapziq Apache License 2.0](../LICENSE)

The standalone HTML copies contain no scripts, remote fonts, or remote images,
so their text remains readable when saved and opened offline.
