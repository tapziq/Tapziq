#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

[[ "${GITHUB_REF:-}" == refs/heads/main ]] || \
  fail "Production releases are restricted to main."
[[ "${GITHUB_REPOSITORY_ID:-}" == 1332440403 ]] || \
  fail "Production releases are restricted to the trusted Tapziq repository."
[[ "${GITHUB_SHA:-}" =~ ^[0-9a-f]{40}$ ]] || \
  fail "GITHUB_SHA must identify the release source commit."

for required_variable in \
  TAPZIQ_RELEASE_STORE_BASE64 \
  TAPZIQ_RELEASE_STORE_PASSWORD \
  TAPZIQ_RELEASE_KEY_ALIAS \
  TAPZIQ_RELEASE_KEY_PASSWORD
do
  [[ -n "${!required_variable:-}" ]] || \
    fail "The production environment is missing $required_variable."
done

printf 'Verified production release controls for repository %s.\n' \
  "$GITHUB_REPOSITORY_ID"
