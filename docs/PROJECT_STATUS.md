# ANTARES project status

## Recovery baseline

- Origin: michaelpesavento813-ops/ANTARES, main at 4b6723cbee89ce07d8db3099cd45be1652ad361b. Native Kotlin source is prototype 0.1. The separately recovered prototype 0.2 expanded EXIF handling and interface features but still describes grayscale as a test, with no native landmarks, durable SQLite queue, or full restoration.
- Six existing Android Actions runs failed. Run 44788258243 stopped at android-actions/setup-android before compilation.
- Recovered and listed Android prototypes 0.1/0.2, Termux workstation 2.0/2.1/2.2, Phases 2/3/3.1/3.2/3.2.1, staged 10C/10D tools, Phase 1.3 Face Engine browser code, G-Core tests 0.1/0.2, R0, and revision audits 0.3/0.4/0.5. Original archives remain at their existing locations. The historical asex.json output was recovered and retained privately.
- The Phase 1.3 browser code requests one face in CPU IMAGE mode with blendshapes and matrices off. The original model checksum matches the published float16 version 1 artifact: 64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff.
- Legacy Phase 2 is bounded tone/color correction and review sheets. The v2.2 workstation offers distinct Pillow autocontrast and sharpening. Phase 3 tattoo code compares user-selected crops and labels orientation suggestions for review. Phase 3.2.1 historical diagnostics counted 505 crop references, zero coordinate rows, and no learning DB. R0 is a synthetic renderer. Design images and example metrics carry no runtime acceptance evidence.
- Phone-only project database and complete reference-input pairing are pending. The public repository contains historical originals already in Git. New commits should carry source, public dependency material, and synthetic tests only.

## Defect ledger

| ID | Severity | Reproduction and cause | Correction and verification |
| --- | --- | --- | --- |
| A-001 | Release blocker | Existing Android workflow fails at setup-android before compilation | Replace with runner SDK checks and an Android 15 test stage; verify build and workflow |
| A-002 | Release blocker | Import then process distinct photographs across interruption. Prototype shares two replaceable singleton files and recompresses the imported copy | Replace with immutable byte copies and SQLite-backed work |
| A-003 | Release blocker | Open Deep Scan in the prototype. No runtime model integration | Port the checksum-matched model and verify outputs |
| A-004 | Release blocker | Feed mirrored, obscured, and contradictory tattoo references. Legacy code provides review hints only | Implement and validate locked chirality and forensic gates before release |
| A-005 | Release blocker | Save JSON and CSV to different locations. Prototype only exports PNG | Separate native Save As entries, durable export records, readback verification |

## First Android 15 acceptance run

- GitHub Actions run 45738941968 built the candidate, passed the two JVM tests and lint, and verified the APK signature and checksums. The Android 15 emulator installed, launched, force-stopped, and relaunched the same APK successfully.
- Acceptance then stopped on first JSON destination read. The test created a MediaStore entry without materializing an empty backing file. The corrected fixture now opens and closes every destination's output stream at creation time. The export routine still rejects occupied destinations and verifies every completed result by reading it back.
- The follow-up test compares all 478 Android landmarks against the pinned desktop CPU fixture and uses raw instrumentation output so CI can check the result code.
- Historical landmark JSON has 68 rows: 62 detections with 478 points and 6 no-face outcomes. It contains no original SHA-256 hashes, and none of its filename-and-size pairs match the repository's original photographs. This prevents a direct comparison against that historical export with the presently paired files.
- A-006, privacy blocker. The first packaged APK inherited INTERNET and ACCESS_NETWORK_STATE from its runtime dependencies. The manifest now removes both permissions and CI checks the actual APK. Private database and app data are excluded from automatic Android cloud backups and device transfers.
- The production signing configuration accepts a retained external keystore via secure environment variables. Current APKs use debug certificates. Release qualification still requires a production key and acceptance of the production-signed APK.
