#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  printf 'Usage: %s /path/to/Tapziq.apk EXPECTED_SOURCE_COMMIT\n' "$0" >&2
  exit 2
fi

apk_path="$1"
expected_source_commit="$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')"
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
grep -Fq 'Number of signers: 1' <<< "$signature_report"
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
    || "$package_line" != *"versionCode='1'"* \
    || "$package_line" != *"versionName='0.1.0'"* ]]; then
  printf 'Unexpected package metadata: %s\n' "$package_line" >&2
  exit 1
fi
grep -Fq "minSdkVersion:'26'" <<< "$badging"
grep -Fq "targetSdkVersion:'36'" <<< "$badging"

if grep -Fq 'application-debuggable' <<< "$badging"; then
  printf 'Release APK is debuggable.\n' >&2
  exit 1
fi
if grep -Eq '^uses-permission' <<< "$badging"; then
  printf 'Release APK unexpectedly requests an Android permission.\n' >&2
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
apk_sha256="$(shasum -a 256 "$apk_path" | awk '{ print $1 }')"

printf 'Verified package: com.tapziq.keyboard 0.1.0 (1)\n'
printf 'Verified source commit: %s\n' "$embedded_source_commit"
printf 'Verified certificate SHA-256: %s\n' "$actual_certificate"
printf 'Verified APK SHA-256: %s\n' "$apk_sha256"
