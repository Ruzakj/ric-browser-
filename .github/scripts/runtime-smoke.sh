#!/usr/bin/env bash
set -euo pipefail

PACKAGE="com.ruzakj.ricbrowser"
ACTIVITY="com.ruzakj.ricbrowser/.MainActivity"
APK="app/build/outputs/apk/debug/app-debug.apk"

fail_on_runtime_blocker() {
  local logfile="$1"
  if grep -E "FATAL EXCEPTION|ANR in ${PACKAGE}|Process: ${PACKAGE}.*has died|AndroidRuntime.*${PACKAGE}" "$logfile"; then
    echo "Runtime blocker found"
    exit 1
  fi
}

saved_tab_count() {
  adb shell run-as "$PACKAGE" cat shared_prefs/ric_browser_tabs.xml 2>/dev/null \
    | grep -o '&quot;url&quot;' | wc -l | tr -d '[:space:]'
}

dump_ui() {
  local ok=0
  for _ in 1 2 3; do
    if adb shell uiautomator dump /sdcard/ric-window.xml >/dev/null 2>&1 \
      && adb pull /sdcard/ric-window.xml /tmp/ric-window.xml >/dev/null 2>&1 \
      && test -s /tmp/ric-window.xml; then
      ok=1
      break
    fi
    sleep 1
  done
  test "$ok" -eq 1
}

assert_ui_text() {
  local needle="$1"
  dump_ui
  NEEDLE="$needle" python3 - <<'PY'
import os
import xml.etree.ElementTree as ET
needle=os.environ['NEEDLE']
root=ET.parse('/tmp/ric-window.xml').getroot()
texts=[n.attrib.get('text','') for n in root.iter('node')]
if not any(needle in t for t in texts):
    raise SystemExit(f'UI text not found: {needle}; texts={texts}')
PY
}

tap_ui_text() {
  local needle="$1"
  dump_ui
  read -r X Y < <(NEEDLE="$needle" python3 - <<'PY'
import os,re
import xml.etree.ElementTree as ET
needle=os.environ['NEEDLE']
root=ET.parse('/tmp/ric-window.xml').getroot()
for node in root.iter('node'):
    if needle not in node.attrib.get('text',''):
        continue
    m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',node.attrib.get('bounds',''))
    if m:
        x1,y1,x2,y2=map(int,m.groups())
        print((x1+x2)//2,(y1+y2)//2)
        raise SystemExit(0)
raise SystemExit(f'UI text not tappable: {needle}')
PY
)
  adb shell input tap "$X" "$Y"
}

tap_toolbar_exact() {
  local needle="$1"
  dump_ui
  read -r X Y < <(NEEDLE="$needle" python3 - <<'PY'
import os,re
import xml.etree.ElementTree as ET
needle=os.environ['NEEDLE']
root=ET.parse('/tmp/ric-window.xml').getroot()
# Tab count may differ after restore; treat □N as the same toolbar control.
def matches(text):
    if text == needle:
        return True
    if needle.startswith('□') and text.startswith('□'):
        return True
    return False
for node in root.iter('node'):
    text=node.attrib.get('text','')
    if not matches(text):
        continue
    m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',node.attrib.get('bounds',''))
    if not m:
        continue
    x1,y1,x2,y2=map(int,m.groups())
    cy=(y1+y2)//2
    if cy < 450:
        print((x1+x2)//2,cy)
        raise SystemExit(0)
raise SystemExit(f'Toolbar control not found: {needle}')
PY
)
  adb shell input tap "$X" "$Y"
}

echo '=== INSTALL ==='
test -s "$APK"
adb install -r "$APK"

echo '=== COLD START #1 ==='
adb shell am force-stop "$PACKAGE"
adb logcat -c || true
adb shell am start -W -n "$ACTIVITY" | tee /tmp/start1.txt
grep -q 'Status: ok' /tmp/start1.txt
sleep 8
test -n "$(adb shell pidof "$PACKAGE" | tr -d '\r')"

echo '=== LOGCAT #1 ==='
adb logcat -d -v threadtime > /tmp/logcat1.txt 2>/dev/null || true
fail_on_runtime_blocker /tmp/logcat1.txt

echo '=== COMPACT TAB MANAGER ==='
tap_toolbar_exact '□1'
sleep 1
assert_ui_text 'Tabs'
adb shell input keyevent KEYCODE_BACK
sleep 1

echo '=== EXTENSIONS MANAGER ==='
tap_toolbar_exact '⋮'
sleep 1
assert_ui_text 'Extensions ('
tap_ui_text 'Extensions ('
sleep 1
assert_ui_text 'Extensions'
assert_ui_text 'Ric Shortlink Auto Helper'
adb shell run-as "$PACKAGE" cat shared_prefs/ric_extensions.xml | grep -q 'ric.shortlink.auto-helper'
adb shell input keyevent KEYCODE_BACK
sleep 1

echo '=== MULTI-TAB CREATE FROM MENU ==='
tap_toolbar_exact '⋮'
sleep 1
tap_ui_text 'New tab'
sleep 4
COUNT=$(saved_tab_count)
test "${COUNT:-0}" -ge 2
echo "SAVED_TABS_AFTER_CREATE=$COUNT"

echo '=== BACKGROUND / FOREGROUND ==='
adb shell input keyevent KEYCODE_HOME
sleep 2
adb shell am start -W -n "$ACTIVITY" | tee /tmp/resume.txt
grep -q 'Status: ok' /tmp/resume.txt
sleep 3
test -n "$(adb shell pidof "$PACKAGE" | tr -d '\r')"

echo '=== ANDROID BACK ==='
adb shell input keyevent KEYCODE_BACK
sleep 2

echo '=== COLD START #2 / TAB RESTORE ==='
adb shell am force-stop "$PACKAGE"
adb logcat -c || true
adb shell am start -W -n "$ACTIVITY" | tee /tmp/start2.txt
grep -q 'Status: ok' /tmp/start2.txt
sleep 8
test -n "$(adb shell pidof "$PACKAGE" | tr -d '\r')"
COUNT=$(saved_tab_count)
test "${COUNT:-0}" -ge 2
echo "SAVED_TABS_AFTER_RESTART=$COUNT"
adb shell run-as "$PACKAGE" cat shared_prefs/ric_extensions.xml | grep -q 'ric.shortlink.auto-helper'

echo '=== LOGCAT #2 ==='
adb logcat -d -v threadtime > /tmp/logcat2.txt 2>/dev/null || true
fail_on_runtime_blocker /tmp/logcat2.txt

echo 'RUNTIME_SMOKE_TEST=PASS'
echo 'COMPACT_TAB_MANAGER=PASS'
echo 'EXTENSIONS_MANAGER_UI=PASS'
echo 'BUILTIN_SHORTLINK_HELPER=PASS'
echo 'MULTI_TAB_PERSISTENCE=PASS'