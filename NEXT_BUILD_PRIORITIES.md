# ANTARES — NEXT BUILD PRIORITIES

## P0 / Requirement 001 — Tattoo Chirality Lock (RELEASE BLOCKER)

**Status:** Approved for next build; implementation pending.
**Order:** First processing stage, before restoration, upscaling, tattoo mapping, geometry analysis, or 3D reconstruction.

### Required behavior
1. Preserve every source image byte-for-byte; corrections are separate reversible derivatives.
2. Establish a verified per-subject tattoo fingerprint and anatomical laterality map.
3. Compare incoming images in both native and horizontally mirrored orientations using tattoo artwork, text, anatomical landmarks, and other independent cues.
4. Automatically flip the **entire image**, never just the tattoo, only when the mirrored orientation is confirmed by sufficiently strong evidence.
5. Flag ambiguous or contradictory cases as UNKNOWN and require human verification; never silently infer left/right placement.
6. Record orientation decision, evidence, confidence, transformation, and rollback provenance.
7. Exclude unresolved-orientation images from authoritative tattoo placement and 3D reconstruction geometry.
8. Preserve tattoo shape, size, color, text, placement, and orientation in the verified anatomical coordinate system.
9. Test normal, mirrored, symmetric, obscured, low-resolution, and conflicting-reference cases; verify no false confident flips.

### Acceptance gate
No photograph with unresolved left/right orientation may contribute tattoo-placement data to the master subject model. No release without successful tests and auditable corrections.

### Implementation note
A single photo does not always permit reliable mirror detection; do not promise universal certainty. Module name proposed: `tattoo_chirality.py`.

### Next-build related maintenance
Update GitHub Actions ZIP import workflows to accept subject-named top-level directories (e.g. `BRENDAS/`) rather than hardcoded `xfer/`; preserve source originals.
