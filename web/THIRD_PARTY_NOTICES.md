# Tapziq browser third-party notices

Last updated: August 15, 2026

These notices cover the browser edition and its production runtime. The native
Android application has a separate dependency set and separate notices.

Tapziq's own source is available under the
[Apache License, Version 2.0](../LICENSE). A production browser build also
includes that license as `LICENSE.txt`.

## Gemma 4 E2B-it web model

- Repository:
  [`litert-community/gemma-4-E2B-it-litert-lm`](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)
- Artifact: `gemma-4-E2B-it-web.litertlm`
- Revision: `6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94`
- Size: `2,008,432,640` bytes
- SHA-256: `3a08e8d94e23b814ae5414469c370c503813949acb8ceaa17e4ebf8a35af35b5`
- License reported by the model repository: Apache License 2.0

The model is not bundled in Tapziq's source tree or production web bundle. The
user's browser downloads this separately hosted artifact only after
confirmation, stores it in origin-private browser storage, and verifies its
size and SHA-256 before use. “Gemma” and other third-party names identify their
respective projects and do not imply endorsement or transfer of ownership.

## LiteRT-LM JavaScript runtime

- Package: [`@litert-lm/core` 0.15.0](https://www.npmjs.com/package/@litert-lm/core/v/0.15.0)
- Source: [`google-ai-edge/LiteRT-LM` at `v0.15.0`](https://github.com/google-ai-edge/LiteRT-LM/tree/v0.15.0/js/packages/core)
- npm integrity: `sha512-NWLgsQ7ktO2dCsx2z/KzX9r/1VL2Sfjl31zskCAzGwq8ujPRrBzJXz2IXCQJ1Kj29IaVa4b86SDR0aoAURuWJw==`
- License: Apache License 2.0

Tapziq bundles the JavaScript library and self-hosts the package's WebAssembly
runtime files. LiteRT-LM's web API is an early preview.

The resolved transitive production dependency is:

- [`@litertjs/wasm-utils` 2.5.3](https://www.npmjs.com/package/@litertjs/wasm-utils/v/2.5.3),
  from [`google-ai-edge/LiteRT`](https://github.com/google-ai-edge/LiteRT),
  npm integrity
  `sha512-4fJiw6tBQnIs+0jH/OQ16nYOiSl8/+zZnn5h2ul8aTKHi05sVqZW580Tmk78pC2kT39A9OYBVdFawteZ/sI1kQ==`,
  Apache License 2.0.

The full Apache License 2.0 text is distributed with the production build as
`LICENSE.txt` and is available in the source repository as [`LICENSE`](../LICENSE).

## noble-hashes

- Package: [`@noble/hashes` 2.3.0](https://www.npmjs.com/package/@noble/hashes/v/2.3.0)
- Source: [`paulmillr/noble-hashes` at `2.3.0`](https://github.com/paulmillr/noble-hashes/tree/2.3.0)
- npm integrity: `sha512-oN+QwyX7VSHotibwubG3kpzbwKrfnyR6OOO+3Nk/53ADL7FmgHHz4TgrbaYKvvOw09u6QTx0oiH1cNCIOuN0CQ==`
- License: MIT

Tapziq uses noble-hashes to calculate the model's SHA-256 inside the browser.
The package's license text follows.

> The MIT License (MIT)
>
> Copyright (c) 2022 Paul Miller (https://paulmillr.com)
>
> Permission is hereby granted, free of charge, to any person obtaining a copy
> of this software and associated documentation files (the "Software"), to deal
> in the Software without restriction, including without limitation the rights
> to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
> copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in
> all copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
> FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
> AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
> LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
> THE SOFTWARE.

## Development-only packages

The lockfile also resolves TypeScript, Vite, Vitest, Playwright, type packages,
and their transitive dependencies for building and testing. They are not
application runtime dependencies. Their versions, sources, integrity values,
and SPDX license identifiers are recorded in `package-lock.json` and their
license files are installed by `npm ci`.

See the [standalone HTML copy](./public/THIRD_PARTY_NOTICES.html),
[browser privacy statement](./PRIVACY.md), and
[browser edition overview](./README.md).
