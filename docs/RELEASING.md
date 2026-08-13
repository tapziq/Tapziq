# Automated releases

Tapziq publishes production Android releases automatically from `main`. The
workflow determines the next stable version from Conventional Commits, builds
one production-signed APK from that exact source commit, verifies it, and then
publishes an immutable GitHub Release.

## Version rules

The release history starts at `v0.1.0`. Commits after the latest release map to
versions as follows:

| Commit | Release |
| --- | --- |
| `fix:` or `perf:` | Patch |
| `feat:` | Minor |
| `type!:` or a `BREAKING CHANGE:` footer | Major |
| `build:`, `chore:`, `ci:`, `docs:`, `refactor:`, `style:`, `test:` | None |

The Verify job checks every non-merge commit after the latest reachable stable
`vX.Y.Z` tag. Its first line must use `type(scope): description` or
`type!: description`; merge commits are ignored. This keeps squash/rebase commit
titles aligned with the version analyzer instead of silently losing releases.

The Android `versionCode` is deterministic:

```text
major * 1,000,000 + minor * 1,000 + patch
```

The manually published `v0.1.0` baseline used version code `1`. The first
automated patch, `v0.1.1`, uses `1001`, and every subsequent stable SemVer
release remains upgrade-safe. Minor and patch components cannot exceed `999`,
and the result cannot exceed Android's `2,100,000,000` limit.

## Release assets

Every automated release contains exactly:

- `Tapziq-vX.Y.Z.apk`
- `SHA256SUMS`
- `LICENSE.txt`
- `THIRD_PARTY_NOTICES.md`

The APK is built once and frozen before checksums are generated. Verification
checks the application ID, version name/code, minimum and target SDKs,
non-debuggable state, absence of permissions, zip alignment, one production
signer, v2/v3 signatures, permanent certificate fingerprint, and embedded Git
source commit. Before publication, the workflow installs that exact signed APK
on an Android 16 automated test device, discovers/enables/selects its
input-method service, opens its test field, and presses a real Tapziq key through
the IME. The workflow then downloads the public assets and compares them with
the files it packaged.

If Semantic Release is interrupted after pushing its tag and stable-channel Git
note but before completing the GitHub Release, the next run reconciles only that
exact tag on the unchanged `main` commit. It independently recomputes the SemVer
level and notes, rebuilds and smoke-tests the APK, validates or creates one
matching draft, replaces only stale expected assets in that mutable draft,
uploads the exact fresh asset set, and finishes publication. Ambiguous tags,
notes, drafts, assets, or source state fail closed; published releases are never
modified.

## GitHub configuration

The `production` environment is restricted to `main` and stores these encrypted
secrets:

```text
TAPZIQ_RELEASE_STORE_BASE64
TAPZIQ_RELEASE_STORE_PASSWORD
TAPZIQ_RELEASE_KEY_ALIAS
TAPZIQ_RELEASE_KEY_PASSWORD
```

The keystore is decoded only into the runner's temporary directory with mode
`0600`. It must always contain the permanent key whose certificate SHA-256 is
tracked in `release/signing-certificate.sha256`; replacing it would prevent
upgrades from earlier Tapziq releases.

The Publish job receives only `contents: write` and `attestations: read`.
Semantic Release receives the built-in token as `GITHUB_TOKEN`, while
post-publication `gh` checks receive `GH_TOKEN` in a separate step. Pull requests
never receive the production secrets.

Before activating the workflow, a repository administrator must:

1. Enable immutable releases for the repository. This setting applies only to
   releases published after it is enabled; the existing `v0.1.0` baseline does
   not become immutable retroactively.
2. Create the `production` environment, restrict its deployment branches to
   `main`, and add the four signing secrets listed above.
3. Confirm Actions may grant the workflow `contents: write` and
   `attestations: read`.
4. Land the automation with a no-release Conventional Commit such as
   `ci(release): activate automated APK releases`. That activation run verifies
   the bot without publishing a version.

The built-in Actions token cannot read the repository's administration-only
immutable-release setting, so this activation check is an administrator-owned
configuration step rather than a workflow API call. Every product-bearing run
still fails unless the published release is reported as immutable and its
release attestation verifies.

## Local checks

Run the secret-free release tests with Node 24:

```sh
npm ci --ignore-scripts
npm run check:commits
npm run test:release
npm run audit:release
```

To package a signed rehearsal for a new, untagged version, set the four
`TAPZIQ_RELEASE_*` file/credential variables and run:

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
scripts/package-semantic-release.sh 0.1.1 "$(git rev-parse HEAD)"
```

The verified assets are written to `dist/release/`. That rehearsal does not
create a tag or GitHub Release.
