# Real browser verification

Tapziq's production browser build completed its opt-in, clean-profile model gate
on 2026-08-15 in America/New_York (`verifiedAt`
`2026-08-16T00:07:53.967Z`). This was a real 2.01 GB model run, not the small
fake model used by the routine Playwright suite.

## Environment

- Google Chrome 151.0.7922.138
- macOS 26.5.2 (25F84)
- Mac17,5 with 8 GB memory
- Fresh dedicated Chrome profile: `/tmp/tapziq-web-final.Ug8ehG`
- Production Vite build served from `http://127.0.0.1:4173`

The gate was launched from this `web` directory with:

```sh
TAPZIQ_REAL_MODEL_TEST=1 \
TAPZIQ_CHROME_PROFILE=/tmp/tapziq-web-final.Ug8ehG \
npm run verify:real
```

The profile began without Tapziq model or browser-site data. It was moved to
the macOS Trash after the successful run so the 2.01 GB test copy would not be
left behind; it remains recoverable until the Trash is emptied.

## Verified result

The verifier exited successfully after checking all of these conditions:

- Secure context, WebGPU adapter, Origin Private File System, and Web Locks
  were available.
- The browser downloaded and SHA-256 verified
  `gemma-4-E2B-it-web.litertlm` at exactly 2,008,432,640 bytes.
- Installed metadata used schema 1, revision
  `6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94`, SHA-256
  `3a08e8d94e23b814ae5414469c370c503813949acb8ceaa17e4ebf8a35af35b5`,
  and LiteRT-LM runtime 0.15.0.
- Online local inference changed `thiss is bad grammer.` to
  `This is bad grammar.` and applied it only after Preview and Apply.
- After Chrome was forced offline and the page reloaded, local inference
  changed `teh cats is here.` to `The cats is here.` and applied it locally.
- The service worker controlled the page and used shell cache
  `tapziq-browser-shell-v6`.
- The only observed request origins were the local server,
  `https://huggingface.co`, and `https://us.aws.cdn.hf.co`; every request was
  GET or HEAD, and no editor text appeared in a request URL or body.
- The verifier reported no browser errors. LiteRT-LM's informational GPU/CPU
  registration messages and its unused-NPU warning were classified as expected
  runtime diagnostics.

Chrome did not grant persistent-storage status in the headless verification
profile (`persistentStorage: false`). The application therefore correctly
continues to disclose that the browser may evict the model under storage
pressure; this does not weaken the verified offline behavior during the run.
