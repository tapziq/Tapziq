#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf 'ERROR: %s\n' "$1" >&2
  exit 1
}

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

[[ -n "${RUNNER_TEMP:-}" ]] || fail "RUNNER_TEMP is required."
reconciliation_log="$RUNNER_TEMP/tapziq-reconciliation.txt"

node scripts/reconcile-interrupted-release.cjs | tee "$reconciliation_log"

[[ "$(grep -Ec '^handled=(true|false)$' "$reconciliation_log")" == 1 ]] \
  || fail "Reconciliation returned an ambiguous result."

if grep -Fxq 'handled=false' "$reconciliation_log"; then
  npm run release
else
  printf 'The interrupted or completed release was reconciled.\n'
fi
