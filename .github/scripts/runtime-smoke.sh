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

assert_alive() {
  test -n "$(adb shell pidof "$PACKAGE" | tr -d '\r')"
}

assert_activity_foreground() {
  adb shell dumpsys activity activities > /tmp/ric-activities.txt
  grep -Eq "mResumedActivity:.*${PACKAGE}/\.MainActivity|topResumedActivity=.*${PACKAGE}/\.MainActivity|mCurrentFocus=.*${PACKAGE}/\.MainActivity" /tmp/ric-activities.txt
}

echo '=== INSTALL ==='
test -s "$APK"
adb install -r "$APK"

echo '=== COLD START #1 ==='
adb shell am force-stop "$PACKAGE"
adb logcat -c
adb shell am start -W -n "$ACTIVITY" | tee /tmp/start1.txt
grep -q 'Status: ok' /tmp/start1.txt
sleep 6
assert_alive
assert_activity_foreground
PID1="$(adb shell pidof "$PACKAGE" | tr -d '\r')"

echo '=== LOGCAT #1 ==='
adb logcat -d -v threadtime > /tmp/logcat1.txt
fail_on_runtime_blocker /tmp/logcat1.txt

echo '=== ROTATION SURVIVAL ==='
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 3
assert_alive
assert_activity_foreground
PID2="$(adb shell pidof "$PACKAGE" | tr -d '\r')"
test "$PID1" = "$PID2"
adb shell settings put system user_rotation 0
sleep 2
assert_alive
assert_activity_foreground

echo '=== BACKGROUND / FOREGROUND ==='
adb shell input keyevent KEYCODE_HOME
sleep 2
adb shell am start -W -n "$ACTIVITY" | tee /tmp/resume.txt
grep -q 'Status: ok' /tmp/resume.txt
sleep 2
assert_alive
assert_activity_foreground

echo '=== COLD START #2 ==='
adb shell am force-stop "$PACKAGE"
adb logcat -c
adb shell am start -W -n "$ACTIVITY" | tee /tmp/start2.txt
grep -q 'Status: ok' /tmp/start2.txt
sleep 5
assert_alive
assert_activity_foreground

echo '=== LOGCAT #2 ==='
adb logcat -d -v threadtime > /tmp/logcat2.txt
fail_on_runtime_blocker /tmp/logcat2.txt

echo 'RUNTIME_SMOKE_TEST=PASS'
echo 'ROTATION_SURVIVAL=PASS'
echo 'BACKGROUND_FOREGROUND=PASS'
echo 'COLD_RESTART=PASS'
