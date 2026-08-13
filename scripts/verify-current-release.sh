#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
expected_commit="${GITHUB_SHA:-$(git -C "$repo_root" rev-parse HEAD)}"
[[ "$expected_commit" =~ ^[0-9a-f]{40}$ ]] || \
  fail "The current release commit must be a full Git SHA."

release_tags="$(
  git -C "$repo_root" tag --points-at "$expected_commit" \
    | grep -E '^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$' \
    || true
)"
if [[ -z "$release_tags" ]]; then
  printf 'No semantic release was created for commit %s.\n' "$expected_commit"
  exit 0
fi
if [[ "$(wc -l <<< "$release_tags" | tr -d '[:space:]')" != 1 ]]; then
  fail "Expected exactly one semantic-version tag on the release commit."
fi

release_tag="$release_tags"
"$script_dir/verify-published-release.sh" \
  "${release_tag#v}" \
  "$expected_commit"
