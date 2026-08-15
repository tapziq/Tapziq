#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
  printf '%s\n' \
    "Usage: $0 /path/to/Tapziq.apk EXPECTED_SOURCE_COMMIT VERSION VERSION_CODE" >&2
  exit 2
fi

apk_path="$1"
expected_source_commit="$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')"
expected_version_name="$3"
expected_version_code="$4"
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
expected_certificate="$(tr -d '[:space:]' < \
  "$repo_root/release/signing-certificate.sha256")"

if [[ ! -f "$apk_path" ]]; then
  printf 'APK does not exist: %s\n' "$apk_path" >&2
  exit 1
fi
if [[ ! "$expected_source_commit" =~ ^[0-9a-f]{40}$ ]]; then
  printf 'Expected source commit must be a full 40-character Git SHA.\n' >&2
  exit 1
fi
if ! command -v unzip >/dev/null 2>&1; then
  printf 'unzip is required to inspect the APK source metadata.\n' >&2
  exit 1
fi
if ! command -v sha256sum >/dev/null 2>&1 \
    && ! command -v shasum >/dev/null 2>&1; then
  printf 'sha256sum or shasum is required to hash the APK.\n' >&2
  exit 1
fi
calculated_version_code="$(
  "$repo_root/scripts/semantic-version-code.sh" "$expected_version_name"
)"
if [[ "$expected_version_code" != "$calculated_version_code" ]]; then
  printf 'VERSION_CODE must be %s for version %s.\n' \
    "$calculated_version_code" "$expected_version_name" >&2
  exit 1
fi

if [[ -z "${ANDROID_HOME:-}" ]]; then
  printf 'ANDROID_HOME must point to the Android SDK.\n' >&2
  exit 1
fi

build_tools="${ANDROID_HOME}/build-tools/36.0.0"
apksigner="$build_tools/apksigner"
aapt2="$build_tools/aapt2"
zipalign="$build_tools/zipalign"

for tool in "$apksigner" "$aapt2" "$zipalign"; do
  if [[ ! -x "$tool" ]]; then
    printf 'Required Android build tool is missing: %s\n' "$tool" >&2
    exit 1
  fi
done

signature_report="$($apksigner verify --verbose --print-certs "$apk_path")"
printf '%s\n' "$signature_report"

grep -Fq 'Verified using v2 scheme (APK Signature Scheme v2): true' \
  <<< "$signature_report"
grep -Fq 'Verified using v3 scheme (APK Signature Scheme v3): true' \
  <<< "$signature_report"
grep -Fq 'Verified using v1 scheme (JAR signing): false' \
  <<< "$signature_report"
grep -Fxq 'Number of signers: 1' <<< "$signature_report"
if grep -Fqi 'Android Debug' <<< "$signature_report"; then
  printf 'Release APK uses an Android debug certificate.\n' >&2
  exit 1
fi

actual_certificate="$(awk -F': ' \
  '/Signer #1 certificate SHA-256 digest:/ { print tolower($2) }' \
  <<< "$signature_report")"
if [[ "$actual_certificate" != "$expected_certificate" ]]; then
  printf 'Unexpected signing certificate: %s\n' "$actual_certificate" >&2
  exit 1
fi

badging="$($aapt2 dump badging "$apk_path")"
package_line="$(grep -m1 '^package:' <<< "$badging")"
if [[ "$package_line" != *"name='com.tapziq.keyboard'"* \
    || "$package_line" != *"versionCode='$expected_version_code'"* \
    || "$package_line" != *"versionName='$expected_version_name'"* ]]; then
  printf 'Unexpected package metadata: %s\n' "$package_line" >&2
  exit 1
fi
grep -Fq "minSdkVersion:'26'" <<< "$badging"
grep -Fq "targetSdkVersion:'36'" <<< "$badging"

if grep -Fq 'application-debuggable' <<< "$badging"; then
  printf 'Release APK is debuggable.\n' >&2
  exit 1
fi
actual_permissions="$(sed -n \
  "s/^uses-permission: name='\\([^']*\\)'.*/\\1/p" <<< "$badging" | sort -u)"
expected_permissions="$(printf '%s\n' \
  android.permission.ACCESS_NETWORK_STATE \
  android.permission.INTERNET \
  com.google.android.apps.aicore.service.BIND_SERVICE \
  com.tapziq.keyboard.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION | sort)"
if [[ "$actual_permissions" != "$expected_permissions" ]]; then
  printf 'Unexpected Android permission set.\nExpected:\n%s\nActual:\n%s\n' \
    "$expected_permissions" "$actual_permissions" >&2
  exit 1
fi

embedded_source_commit="$(unzip -p "$apk_path" \
  META-INF/version-control-info.textproto \
  | awk -F'"' '/revision:/ { print tolower($2); exit }')"
if [[ "$embedded_source_commit" != "$expected_source_commit" ]]; then
  printf 'Unexpected embedded source commit: %s\n' "$embedded_source_commit" >&2
  exit 1
fi

"$zipalign" -c -P 16 4 "$apk_path"
if command -v sha256sum >/dev/null 2>&1; then
  apk_sha256="$(sha256sum "$apk_path" | awk '{ print $1 }')"
else
  apk_sha256="$(shasum -a 256 "$apk_path" | awk '{ print $1 }')"
fi

printf 'Verified package: com.tapziq.keyboard %s (%s)\n' \
  "$expected_version_name" "$expected_version_code"
printf 'Verified source commit: %s\n' "$embedded_source_commit"
printf 'Verified certificate SHA-256: %s\n' "$actual_certificate"
printf 'Verified APK SHA-256: %s\n' "$apk_sha256"
