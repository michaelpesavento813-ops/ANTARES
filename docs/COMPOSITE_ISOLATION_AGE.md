# Composite, subject isolation, and age transformation

## Composite background editor

Green Screen is the initial per-subject default. The editor also supports a true-alpha preview and PNG, Original Background, Solid Color, and a custom photograph imported through Android Files. Green and solid colors are editable. The original background uses the complete source plate, which may already contain a person. The editor preserves that plate as supplied and does not synthesize a clean plate behind the person.

Each subject layer has independent position and proportional scale. Custom backgrounds have their own zoom and position. Lighting and warmth adjustments support manual matching, and feathering controls the mask boundary. The development editor produces square, landscape, portrait, or 2048 × 2048 output. Every render requires inspecting the preview and confirming the cutouts.

## Isolate Subject

The operation is available within Restorative Repair and Build Composite Image. Person and face proposals assist selection, and the user confirms the reference region before entering the editor. MagicTouch produces an interactive mask from a user-adjustable point. The editor supports start-from-empty manual masks, add/remove brush strokes and undo/redo for mask changes. Automatic failures show an error and require manual correction or retargeting. A rendered isolation stores a separate alpha-mask PNG, the original photo and selected region references, brush and processing settings, and the rendered PNG. Reopening from Photos and files or Build Composite Image can reuse that mask while changing the background. The subject archive includes and remaps masks and background references.

The point-based model and feathering are development tools. True optical matting for glass and transparent fabrics, full-resolution edge reconstruction, and reliable separation of all overlapping people require additional validated fixtures and model work.

## Simulated Age Transformation

This mode is within Make Me Pretty. Enter a signed year adjustment from −40 to +40 or use the slider. Strength is separate. Face-only automatically limits the filter to one visible face inside the chosen region. Face and visible body uses the user-confirmed region. The preview toggles between source and derivative. Zero adjustment or zero strength leaves the decoded source pixels unchanged.

The renderer adjusts local skin-like texture and tone from original pixels, without moving facial features or generating a historical reconstruction. Year settings control the strength and direction of this simulation. Perceived age has not been calibrated against known age pairs. The derivative and exact settings stay attached to the active SUBJECT and are exportable through Save As PNG.
