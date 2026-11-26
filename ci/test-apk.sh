#!/usr/bin/env bash
set -euo pipefail
(
  cd delivery
  sha256sum --check SHA256SUMS.txt
)
adb install -r delivery/ANTARES-development.apk
python3 ci/android-smoke.py
adb install -r -t delivery/ANTARES-test.apk
adb shell am instrument -w -r com.antares.prototype.test/com.antares.prototype.AcceptanceRunner | tee delivery/acceptance.txt
grep -F 'PASS: Android 15 import' delivery/acceptance.txt
grep -F 'INSTRUMENTATION_CODE: -1' delivery/acceptance.txt
