#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

if [[ $# -ne 3 ]]; then
  fail "Usage: $0 /path/to/Tapziq.apk VERSION VERSION_CODE"
fi

apk_path="$1"
expected_version_name="$2"
expected_version_code="$3"
package_name="com.tapziq.keyboard"
ime_component="$package_name/.TapziqInputMethodService"
test_field_id="$package_name:id/test_field"

[[ -f "$apk_path" ]] || fail "APK does not exist: $apk_path"
command -v adb >/dev/null 2>&1 || fail "adb is required for the emulator smoke test."
command -v python3 >/dev/null 2>&1 || \
  fail "python3 is required for UI bounds parsing."
[[ "$(adb get-state 2>/dev/null)" == device ]] || \
  fail "Exactly one booted Android emulator or device is required."

temporary_directory="$(
  mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/tapziq-smoke.XXXXXX"
)"
ui_dump="$temporary_directory/window.xml"
cleanup() {
  rm -f "$ui_dump"
  rmdir "$temporary_directory" >/dev/null 2>&1 || true
}
trap cleanup EXIT

adb uninstall "$package_name" >/dev/null 2>&1 || true
adb install --no-streaming "$apk_path" >/dev/null
# A freshly installed package is stopped until first launch. Android does not
# add a stopped package's input method to `ime list`, so launch its setup
# activity before asserting service discovery.
adb shell am start -W -n "$package_name/.MainActivity" >/dev/null
package_dump="$(adb shell dumpsys package "$package_name" | tr -d '\r')"
grep -Eq "versionCode=${expected_version_code}([[:space:]]|$)" \
  <<< "$package_dump" || fail "Installed APK has the wrong version code."
grep -Fq "versionName=$expected_version_name" <<< "$package_dump" || \
  fail "Installed APK has the wrong version name."

installed_imes="$(adb shell ime list -s -a | tr -d '\r')"
grep -Fxq "$ime_component" <<< "$installed_imes" || \
  fail "Android did not discover the Tapziq input-method service."
adb shell ime enable "$ime_component" >/dev/null
adb shell ime set "$ime_component" >/dev/null
selected_ime="$(
  adb shell settings get secure default_input_method | tr -d '\r'
)"
[[ "$selected_ime" == "$ime_component" ]] || \
  fail "Tapziq was not selected as the current input method."
adb shell settings put secure show_ime_with_hard_keyboard 1

adb shell am start -W -n "$package_name/.MainActivity" >/dev/null
sleep 2

dump_ui() {
  local attempt
  for attempt in 1 2 3 4 5; do
    adb shell rm -f /sdcard/tapziq-window.xml
    if adb shell uiautomator dump /sdcard/tapziq-window.xml \
        >/dev/null 2>&1 \
        && adb pull /sdcard/tapziq-window.xml "$ui_dump" >/dev/null 2>&1
    then
      adb shell rm -f /sdcard/tapziq-window.xml
      return 0
    fi
    sleep 2
  done
  fail "Android UI automation could not inspect the active window."
}

node_bounds() {
  local attribute="$1"
  local expected="$2"
  python3 - "$ui_dump" "$attribute" "$expected" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

path, attribute, expected = sys.argv[1:]
matches = [node for node in ET.parse(path).iter("node")
           if node.attrib.get(attribute) == expected]
if len(matches) != 1:
    raise SystemExit(1)
match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",
                     matches[0].attrib.get("bounds", ""))
if not match:
    raise SystemExit(1)
left, top, right, bottom = map(int, match.groups())
print(f"{(left + right) // 2} {(top + bottom) // 2}")
PY
}

dump_ui
field_coordinates="$(node_bounds resource-id "$test_field_id")" || \
  fail "Could not find Tapziq's test text field."
read -r field_x field_y <<< "$field_coordinates"
adb shell input tap "$field_x" "$field_y"
sleep 2

# UIAutomator exposes only the application hierarchy on newer Android builds,
# even while the IME window is visible. Confirm the selected Tapziq service is
# bound and the input window is shown, then tap the first key from the known
# runtime keyboard layout geometry.
input_method_dump="$(adb shell dumpsys input_method | tr -d '\r')"
grep -Fq "mSelectedMethodId=$ime_component" <<< "$input_method_dump" || \
  fail "Tapziq is not the input-method service bound to the focused field."
grep -Fq 'mInputShown=true' <<< "$input_method_dump" || \
  fail "Tapziq's input-method window did not appear."
window_dump="$(adb shell dumpsys window windows | tr -d '\r')"
ime_frame="$(awk '
  /mIsImWindow=true/ { in_ime = 1 }
  in_ime && /Frames:/ { print; exit }
' <<< "$window_dump")"
[[ "$ime_frame" =~ frame=\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\] ]] || \
  fail "Could not resolve Tapziq's input-method window bounds."
ime_left="${BASH_REMATCH[1]}"
ime_top="${BASH_REMATCH[2]}"
ime_right="${BASH_REMATCH[3]}"
ime_bottom="${BASH_REMATCH[4]}"
display_density="$(
  adb shell wm density | tr -d '\r' \
    | awk -F': ' '/Physical density:/ { print $2 }'
)"
[[ "$display_density" =~ ^[1-9][0-9]*$ ]] || \
  fail "Could not determine the emulator display density."
ime_width=$((ime_right - ime_left))
ime_height=$((ime_bottom - ime_top))
((ime_width > 0 && ime_height > 0)) || \
  fail "Tapziq's input-method window has invalid bounds."
panel_padding=$(((2 * display_density + 80) / 160))
top_padding=$(((4 * display_density + 80) / 160))
if ((ime_width < ime_height)); then
  row_height=$(((44 * display_density + 80) / 160))
else
  row_height=$(((52 * display_density + 80) / 160))
fi
key_x=$((ime_left + panel_padding + (ime_width - 2 * panel_padding) / 20))
key_y=$((ime_top + top_padding + row_height / 2))
adb shell input tap "$key_x" "$key_y"
sleep 1
dump_ui

typed_text="$(python3 - "$ui_dump" "$test_field_id" <<'PY'
import sys
import xml.etree.ElementTree as ET

path, resource_id = sys.argv[1:]
matches = [node for node in ET.parse(path).iter("node")
           if node.attrib.get("resource-id") == resource_id]
if len(matches) != 1:
    raise SystemExit(1)
print(matches[0].attrib.get("text", ""))
PY
)" || fail "Could not read Tapziq's test text field."
[[ "$typed_text" == q || "$typed_text" == Q ]] || \
  fail "Tapziq displayed its keyboard but did not type through its input connection."

printf 'Verified installed production IME %s %s (%s); typed: %s\n' \
  "$package_name" "$expected_version_name" "$expected_version_code" "$typed_text"
