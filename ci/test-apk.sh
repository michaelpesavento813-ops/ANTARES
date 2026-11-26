#!/usr/bin/env bash
set -euo pipefail
(
  cd delivery
  sha256sum --check SHA256SUMS.txt
)
adb install -r delivery/ANTARES-development.apk
python3 ci/android-smoke.py
adb install -r -t delivery/ANTARES-test.apk
if ! adb shell am instrument -w -r com.antares.prototype.test/com.antares.prototype.AcceptanceRunner | tee delivery/acceptance.txt ||
   ! grep -F 'PASS: Android 15 import' delivery/acceptance.txt ||
   ! grep -F 'INSTRUMENTATION_CODE: -1' delivery/acceptance.txt; then
    adb shell dumpsys jobscheduler | grep -F -C 4 'com.antares.prototype' || true
    adb logcat -d -t 1500 | grep -E 'JobScheduler|ProcessingService|AndroidRuntime' | tail -100 || true
    exit 1
fi
