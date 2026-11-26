# ANTARES
Temporarily handoff junction for AI

## Native Android development candidate

ANTARES runs directly on Android. The package targets Android 15 and includes the offline Face Landmarker model.

1. Open ANTARES and tap **Add photos**. Select photographs in Android Files.
2. Tap **Deep Scan** for the selected photo or **Deep Scan entire project** for the project.
3. After the result is ready, tap **Save As JSON** or **Save As CSV**. Pick the destination and edit the filename in Android Files. Each format has its own picker, so the two files may use different folders. The suggested name identifies the same scan in both formats.
4. Set **Preferred export folder** to make Android Files open there on future saves when supported. Tap **Open saved file** to open a saved result in a compatible Android app.

Source documents are imported through read-only grants and copied byte-for-byte into the app's private storage. The analysis output contains normalized facial landmarks. Grayscale is a separate preview operation. The next-build tattoo and forensic requirements are tracked in [NEXT_BUILD_PRIORITIES.md](NEXT_BUILD_PRIORITIES.md).

### Build and verify

Use JDK 17, Gradle 8.10.2, Android platform 35, and Build Tools 35.0.0.

```sh
gradle --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
```

Model and test-image downloads are checksum-pinned. The Actions workflow verifies candidate APK checksums, installs the same files on an Android 15 emulator, and runs the acceptance suite. The debug APK is for development testing. A production key and physical Galaxy A14 acceptance are pending.

See the [native specification](docs/NATIVE_ANDROID_SPEC.md), [status and defect ledger](docs/PROJECT_STATUS.md), and [reference fixture](docs/REFERENCE_FIXTURE.md).

For a production build, set `ANTARES_SIGNING_KEYSTORE`, `ANTARES_SIGNING_STORE_PASSWORD`, `ANTARES_SIGNING_KEY_ALIAS`, and `ANTARES_SIGNING_KEY_PASSWORD` through a secure local or CI environment and run `gradle :app:assembleRelease`. Reuse the retained production key for updates. Verify the signed APK certificate with `apksigner verify --print-certs` before distribution. Keep all key material outside Git.
