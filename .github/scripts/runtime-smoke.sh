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

echo '=== COLD START #2 ==='
adb shell am force-stop "$PACKAGE"
adb logcat -c
adb shell am start -W -n "$ACTIVITY" | tee /tmp/start2.txt
grep -q 'Status: ok' /tmp/start2.txt
sleep 8
test -n "$(adb shell pidof "$PACKAGE" | tr -d '\r')"

echo '=== LOGCAT #2 ==='
adb logcat -d -v threadtime > /tmp/logcat2.txt
fail_on_runtime_blocker /tmp/logcat2.txt

echo 'RUNTIME_SMOKE_TEST=PASS'
