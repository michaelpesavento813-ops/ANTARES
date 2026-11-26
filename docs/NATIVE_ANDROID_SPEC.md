# ANTARES native Android specification v0.1

Target is the Galaxy A14 5G SM-A146U on Android 15. The existing ANTARES repository is authoritative for native code. The locked vNext specification and existing next-build priority document remain requirements. The native package ID stays com.antares.prototype to preserve the update path for builds with matching signing certificates.

| Requirement | Implementation or acceptance evidence needed |
| --- | --- |
| Native launch and offline processing | Native activity, local processing service, bundled checksum-pinned model, no network permission |
| Project isolation and image registry | SQLite project IDs, stable import IDs, original name, assigned alias, source and hash metadata; exact links and separately qualified perceptual links |
| Background work and recovery | Durable queue, atomic internal files, resumable job by photo, visible failed work and retry, dispatch after relaunch |
| Originals | Read-only provider grants for import, content-addressed private copies, checksum verification and no writes to the input document |
| Exports | Separate native Save As pickers for JSON and CSV, matching analysis identifier, editable name and destination per picker, remembered starting folder, readback validation |
| Geometry and landmarks | Same CPU Face Landmarker model as Phase 1.3; compare native points to historical fixtures built from corresponding inputs, with measured tolerance |
| Restoration | Preserve historical tools. Native candidate must document pixel-level differences and validate expected results before parity is claimed |
| Tattoo requirements | Chirality Lock and forensic verification in NEXT_BUILD_PRIORITIES.md are release blockers. The legacy crop scorer was designed to suggest review only |
| Release qualification | Reproducible signed build, tests, install and workflow on API 35, hardware checks and documented defects |
