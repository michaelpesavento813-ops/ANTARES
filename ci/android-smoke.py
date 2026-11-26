#!/usr/bin/env python3
"""Smoke-test the exact APK previously installed on an Android 15 device."""
import subprocess
import time

PACKAGE = "com.antares.prototype"


def adb(*args: str) -> str:
    return subprocess.check_output(["adb", *args], text=True, timeout=60).strip()


def main() -> None:
    assert adb("shell", "getprop", "ro.build.version.sdk") == "35"
    response = adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
    assert "Status: ok" in response, response
    for _ in range(8):
        state = adb("shell", "dumpsys", "activity", "activities")
        if any(PACKAGE in line and "ResumedActivity" in line for line in state.splitlines()):
            break
        time.sleep(2)
    else:
        raise RuntimeError("ANTARES did not remain in the foreground")
    adb("shell", "am", "force-stop", PACKAGE)
    assert "Status: ok" in adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
    print("PASS: actual APK launched and relaunched on Android 15.")


if __name__ == "__main__":
    main()
