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
    | grep -o '&quot;url&quot;' \
    | wc -l \
    | tr -d '[:space:]'
}

dump_ui() {
  adb shell uiautomator dump /sdcard/ric-window.xml >/dev/null
  adb pull /sdcard/ric-window.xml /tmp/ric-window.xml >/dev/null
}

tap_tab_counter() {
  dump_ui
  read -r X Y < <(python3 - <<'PY'
import re
import xml.etree.ElementTree as ET
root = ET.parse('/tmp/ric-window.xml').getroot()
buttons=[]
for node in root.iter('node'):
    if node.attrib.get('class') != 'android.widget.Button':
        continue
    m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.attrib.get('bounds',''))
    if not m:
        continue
    x1,y1,x2,y2=map(int,m.groups())
    cy=(y1+y2)//2
    if cy < 450:
        buttons.append(((x1+x2)//2,cy,node.attrib.get('text','')))
buttons.sort(key=lambda v:v[0])
if len(buttons) < 3:
    raise SystemExit(f'Expected compact toolbar buttons, found {buttons}')
# Compact toolbar order is Back, optional Media, Tab Counter, Menu.
# With no media on startup, the tab counter is the second button from the right.
x,y,text=buttons[-2]
print(x,y)
PY
)
  adb shell input tap "$X" "$Y"
}

tap_new_tab() {
  dump_ui
  read -r X Y < <(python3 - <<'PY'
import re
import xml.etree.ElementTree as ET
root=ET.parse('/tmp/ric-window.xml').getroot()
for node in root.iter('node'):
    text=node.attrib.get('text','')
    if 'New tab' not in text:
        continue
    m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.attrib.get('bounds',''))
    if m:
        x1,y1,x2,y2=map(int,m.groups())
        print((x1+x2)//2,(y1+y2)//2)
        raise SystemExit(0)
raise SystemExit('New tab action not found in tab manager')
PY
)
  adb shell input tap "$X" "$Y"
}

echo '=== INSTALL ==='
test -s "$APK"
adb install -r "$APK"

echo '=== COLD START #1 ==='
adb shell am force-stop "$PACKAGE"
adb logcat -c
adb shell am start -W -n "$ACTIVITY" | tee /tmp/start1.txt
grep -q 'Status: ok' /tmp/start1.txt
sleep 8
PID=$(adb shell pidof "$PACKAGE" | tr -d '\r')
test -n "$PID"

echo '=== LOGCAT #1 ==='
adb logcat -d -v threadtime > /tmp/logcat1.txt
fail_on_runtime_blocker /tmp/logcat1.txt

echo '=== COMPACT TAB MANAGER / MULTI-TAB CREATE ==='
tap_tab_counter
sleep 1
tap_new_tab
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
adb logcat -c
adb shell am start -W -n "$ACTIVITY" | tee /tmp/start2.txt
grep -q 'Status: ok' /tmp/start2.txt
sleep 8
test -n "$(adb shell pidof "$PACKAGE" | tr -d '\r')"
COUNT=$(saved_tab_count)
test "${COUNT:-0}" -ge 2
echo "SAVED_TABS_AFTER_RESTART=$COUNT"

echo '=== LOGCAT #2 ==='
adb logcat -d -v threadtime > /tmp/logcat2.txt
fail_on_runtime_blocker /tmp/logcat2.txt

echo 'RUNTIME_SMOKE_TEST=PASS'
echo 'COMPACT_TAB_MANAGER=PASS'
echo 'MULTI_TAB_PERSISTENCE=PASS'
