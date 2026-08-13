#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

required_variables=(
  TAPZIQ_RELEASE_STORE_FILE
  TAPZIQ_RELEASE_STORE_PASSWORD
  TAPZIQ_RELEASE_KEY_ALIAS
  TAPZIQ_RELEASE_KEY_PASSWORD
)

for variable_name in "${required_variables[@]}"; do
  if [[ -z "${!variable_name:-}" ]]; then
    printf 'Missing required environment variable: %s\n' "$variable_name" >&2
    exit 1
  fi
done

if [[ -z "${ANDROID_HOME:-}" ]]; then
  printf 'ANDROID_HOME must point to an Android SDK containing build-tools 36.0.0.\n' >&2
  exit 1
fi

cd "$repo_root"

if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
  printf 'Production releases must be built from a clean Git worktree.\n' >&2
  exit 1
fi
source_commit="$(git rev-parse --verify HEAD)"

env \
  -u TAPZIQ_RELEASE_STORE_FILE \
  -u TAPZIQ_RELEASE_STORE_PASSWORD \
  -u TAPZIQ_RELEASE_KEY_ALIAS \
  -u TAPZIQ_RELEASE_KEY_PASSWORD \
  ./gradlew \
  --no-daemon \
  --no-configuration-cache \
  clean \
  :app:checkProductionSigningTaskCoverage \
  :app:testDebugUnitTest \
  :app:lintRelease

./gradlew \
  --no-daemon \
  --no-configuration-cache \
  :app:assembleRelease

if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
  printf 'Production build changed the Git worktree; refusing the artifact.\n' >&2
  exit 1
fi

unset \
  TAPZIQ_RELEASE_STORE_FILE \
  TAPZIQ_RELEASE_STORE_PASSWORD \
  TAPZIQ_RELEASE_KEY_ALIAS \
  TAPZIQ_RELEASE_KEY_PASSWORD

scripts/verify-release-apk.sh \
  app/build/outputs/apk/release/app-release.apk \
  "$source_commit"
