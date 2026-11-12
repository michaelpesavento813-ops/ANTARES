# ANTARES — NEXT BUILD PRIORITIES

## P0 / Requirement 001 — Tattoo Chirality Lock (MANDATORY CORE FEATURE / RELEASE BLOCKER)

**Classification:** Permanent ANTARES core architecture, not a plugin, optional module, or user-toggleable enhancement.
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


## P0 / Requirement 002 — Tattoo Forensic Reconstruction & Verification (CORE FEATURE)

**Classification:** Mandatory core capability of standalone Tattoo Chirality Lock and future ANTARES integration.
**Status:** Approved specification; implementation pending.

### Required behavior
1. Detect and group multiple observations of the same tattoo across a subject's photographs, accounting for perspective, scale, lighting, occlusion, and verified left/right orientation.
2. Preserve immutable source crops; maintain a traceable evidence link from every derived view to original photograph, crop coordinates, and processing settings.
3. Offer non-generative enhancement (contrast, denoising, deblurring where supportable, perspective rectification) with explicit disclosure of limits; never represent invented detail as recovered source evidence.
4. For lettering tattoos, compare multiple OCR hypotheses and show character-level uncertainty, preserving UNKNOWN characters rather than silently completing words.
5. For non-text tattoos, compare geometry, linework, colors, symbols, and spatial layout across independent views; distinguish verified detail from proposed interpretation.
6. Require evidence-backed human approval for ambiguous text, symbols, or fine details; allow user-provided authoritative references while clearly recording their provenance.
7. Once approved, lock verified text, artwork identity, anatomical laterality, and placement as the canonical tattoo fingerprint; reject inconsistent future reconstructions for review.
8. Generate a forensic audit report: source images, evidence crops, confidence/uncertainty, hypotheses, approved interpretation, and checksums.
9. Never modify source images as part of analysis. Under separately authorized Damn the Torpedoes mode, replace only explicitly selected verified corrected cloud images, commit as a batch, and retain Git history where available.

### Acceptance gate
No ambiguous character, symbol, or tattoo geometry may be promoted as verified solely because a generative model produced a plausible reconstruction. UNKNOWN must remain UNKNOWN until independent evidence or explicit user verification resolves it.
