#!/usr/bin/env bash
set -euo pipefail
mkdir -p report
# Set ANDROID_SERIAL when more than one device/emulator is attached.
cleanup() {
  adb logcat -d > report/android-logcat.log
  adb shell am force-stop com.posthog.compliance.android
  adb forward --remove tcp:18292
  adb reverse --remove tcp:19292
}
trap cleanup EXIT
adb install -r sdk_compliance_adapter/android/build/outputs/apk/debug/android-debug.apk
adb forward tcp:18292 tcp:18292
adb reverse tcp:19292 tcp:19292
adb shell am start -n com.posthog.compliance.android/.AdapterActivity --ei port 18292
bash sdk_compliance_adapter/run-harness.sh 18292 19292
