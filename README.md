# ANTARES
Temporarily handoff junction for AI

## Native Android development candidate

ANTARES runs directly on Android. The package targets Android 15 and includes the offline Face Landmarker model.

1. Open ANTARES and tap **Add photos**. Select photographs in Android Files.
2. Choose one of the four permanent buttons. In **Deep Scan/Mapping**, select the intended outline or draw a region, then confirm it. Use **Combine confirmed scans for export** to group the completed scans you select.
3. After a result is ready, tap **Save As JSON**, **Save As CSV**, or **Save As PNG** as appropriate. Select the exact result if more than one is available. Pick the destination and edit the filename in Android Files. JSON and CSV can use different folders and share the same result identifier.
4. Set **Preferred export folder** to make Android Files open there on future saves when supported. Tap **Open saved file** to open a saved result in a compatible Android app.

Source documents are imported through read-only grants and copied byte-for-byte into the app's private storage. The analysis output contains estimated normalized facial landmarks and the confirmed source region. The separate repair and appearance workflows produce region-limited derivatives. Composites use independently confirmed source regions. See the [subject-selection addendum](docs/SUBJECT_SELECTION.md) for workflow behavior and limits and [NEXT_BUILD_PRIORITIES.md](NEXT_BUILD_PRIORITIES.md) for tattoo and forensic requirements.

### Build and verify

Use JDK 17, Gradle 8.10.2, Android platform 35, and Build Tools 35.0.0.

```sh
gradle --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
```

Model and test-image downloads are checksum-pinned. The Actions workflow verifies candidate APK checksums, installs the same files on an Android 15 emulator, and runs the acceptance suite. The debug APK is for development testing. A production key and physical Galaxy A14 acceptance are pending.

See the [native specification](docs/NATIVE_ANDROID_SPEC.md), [status and defect ledger](docs/PROJECT_STATUS.md), and [reference fixture](docs/REFERENCE_FIXTURE.md).

For a production build, set `ANTARES_SIGNING_KEYSTORE`, `ANTARES_SIGNING_STORE_PASSWORD`, `ANTARES_SIGNING_KEY_ALIAS`, and `ANTARES_SIGNING_KEY_PASSWORD` through a secure local or CI environment and run `gradle :app:assembleRelease`. Reuse the retained production key for updates. Verify the signed APK certificate with `apksigner verify --print-certs` before distribution. Keep all key material outside Git.
