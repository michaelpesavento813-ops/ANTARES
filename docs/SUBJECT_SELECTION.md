# Subject selection and workflow addendum

The four required actions occupy two fixed rows above the scrolling area. Each opens a dedicated screen with its own settings and explicit confirmation. Cancel returns to the project without starting processing. Composite references are confirmed individually and can be reviewed and reselected before building.

| Workflow | Development implementation |
| --- | --- |
| Restorative Repair | Select a region and apply adjustable tonal correction, noise reduction, or a conservative local median filter for thin defects. The filter derives output only from neighboring sampled pixels. Extensive damage reconstruction remains a separate release gate. |
| Make Me Pretty | Select the treatment region and adjust hair tint, makeup tint, or complexion softening. Pixels outside the confirmed rectangle remain unchanged in the upright decoded derivative. This tool applies local tint or smoothing without geometry changes. Automatic treatment segmentation remains a release gate. |
| Deep Scan/Mapping | Confirm a subject, then scan the selected area. If the region still yields two faces, it fails visibly and requires reselection. JSON records the original dimensions and orientation, selected region, estimated normalized upright x/y, model-relative z, inference settings, and explicitly unavailable point confidences. Body and full geometry mapping are pending. |
| Build Composite Image | Confirm two to four different images and choose a side-by-side or stacked arrangement. Output comprises the independently confirmed crops. Fused or generated compositing remains a release gate. |

Candidate outlines use up to 16 visible facial meshes, sorted left to right. Tap an outline or draw a region, pinch to zoom and pan in outline mode. Selections intersecting more than one visible outline cannot be confirmed. Manual selection is available when detection misses a subject. In crowded or obscured scenes, rectangle selection and facial proposals have limited visibility into overlapping people. Avoid validating bystander isolation until representative crowd tests pass. Face detection provides no identity assertion, and each cross-photo association is manually confirmed.

Acceptance coverage is tracked per addendum. The test suite includes four entry-point and cancellation checks, positive and negative landmark fixtures, a two-face image, overlap rejection, out-of-region pixel integrity, and two-reference compositing. Large crowds, occlusion, profiles, poor lighting, manual gestures, and the SM-A146U still require documented verification.
