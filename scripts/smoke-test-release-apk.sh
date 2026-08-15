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
proofread_rows_before_letters="${TAPZIQ_PROOFREAD_ROWS_BEFORE_LETTERS-1}"

case "$proofread_rows_before_letters" in
  0|1)
    ;;
  *)
    fail "TAPZIQ_PROOFREAD_ROWS_BEFORE_LETTERS must be 0 or 1."
    ;;
esac

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

node_text() {
  local attribute="$1"
  local expected="$2"
  python3 - "$ui_dump" "$attribute" "$expected" <<'PY'
import sys
import xml.etree.ElementTree as ET

path, attribute, expected = sys.argv[1:]
matches = [node for node in ET.parse(path).iter("node")
           if node.attrib.get(attribute) == expected]
if len(matches) != 1:
    raise SystemExit(1)
print(matches[0].attrib.get("text", ""))
PY
}

field_coordinates=""
for attempt in 1 2 3 4 5; do
  dump_ui
  if field_coordinates="$(node_bounds resource-id "$test_field_id")"; then
    break
  fi
  field_coordinates=""

  # A just-created headless emulator can briefly show this platform ANR while
  # System UI completes its own cold start. Wait only for that exact system
  # dialog, then relaunch Tapziq and continue to require the real field/IME.
  if grep -Fq 'text="System UI isn'"'"'t responding"' "$ui_dump"; then
    system_wait_coordinates="$(
      node_bounds resource-id android:id/aerr_wait
    )" || fail "The System UI wait action could not be resolved."
    read -r system_wait_x system_wait_y <<< "$system_wait_coordinates"
    adb shell input tap "$system_wait_x" "$system_wait_y"
  fi

  sleep 3
  adb shell am start -W -n "$package_name/.MainActivity" >/dev/null
  sleep 2
done
[[ -n "$field_coordinates" ]] || \
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
display_dimensions="$(
  adb shell dumpsys window displays | tr -d '\r' \
    | sed -n 's/.* cur=\([0-9][0-9]*\)x\([0-9][0-9]*\) .*/\1 \2/p' \
    | head -n 1
)"
[[ "$display_dimensions" =~ ^([1-9][0-9]*)\ ([1-9][0-9]*)$ ]] || \
  fail "Could not determine the current display orientation."
display_width="${BASH_REMATCH[1]}"
display_height="${BASH_REMATCH[2]}"
ime_width=$((ime_right - ime_left))
ime_height=$((ime_bottom - ime_top))
((ime_width > 0 && ime_height > 0)) || \
  fail "Tapziq's input-method window has invalid bounds."
panel_padding=$(((2 * display_density + 80) / 160))
top_padding=$(((4 * display_density + 80) / 160))
if ((display_width > display_height)); then
  row_height=$(((40 * display_density + 80) / 160))
else
  row_height=$(((48 * display_density + 80) / 160))
fi
key_x=$((ime_left + panel_padding + (ime_width - 2 * panel_padding) / 20))
# Ordinary prose fields now have a full-width Proofread row before QWERTY.
# Interrupted-release recovery can explicitly select the older layout with no
# Proofread row. Tap the center of the q key while preserving a real IME tap.
key_y=$((ime_top + top_padding \
  + proofread_rows_before_letters * row_height + row_height / 2))
adb shell input tap "$key_x" "$key_y"
sleep 1

typed_text=""
field_was_read=false
ime_hide_attempted=false
for attempt in 1 2 3 4 5; do
  dump_ui
  if candidate_typed_text="$(node_text resource-id "$test_field_id")"; then
    typed_text="$candidate_typed_text"
    field_was_read=true
    if [[ "$typed_text" == q || "$typed_text" == Q ]]; then
      break
    fi
  elif [[ "$ime_hide_attempted" == false ]]; then
    # UIAutomator can briefly return the IME hierarchy instead of the focused
    # application. Press Back only while Android still reports the IME shown,
    # so the activity remains in front while its field becomes inspectable.
    if current_input_method_dump="$(
      adb shell dumpsys input_method 2>/dev/null | tr -d '\r'
    )" && grep -Fq 'mInputShown=true' <<< "$current_input_method_dump"; then
      adb shell input keyevent KEYCODE_BACK >/dev/null || \
        fail "Could not hide Tapziq's input-method window for field verification."
      ime_hide_attempted=true
    fi
  fi
  sleep 2
done

[[ "$field_was_read" == true ]] || \
  fail "Could not read Tapziq's test text field."
[[ "$typed_text" == q || "$typed_text" == Q ]] || \
  fail "Tapziq displayed its keyboard but did not type through its input connection."

printf 'Verified installed production IME %s %s (%s); typed: %s\n' \
  "$package_name" "$expected_version_name" "$expected_version_code" "$typed_text"
