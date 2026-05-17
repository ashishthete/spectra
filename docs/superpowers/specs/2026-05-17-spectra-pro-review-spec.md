# SPECTRA Professional Review — Implementation Spec

## Critical Fixes (Tier 1 — Foundation)

### 1. Remove Auto Lens Switching
- Show lens recommendations as visual scores only
- Never call `cameraController.switchLens()` automatically
- Let user tap to accept recommendation

### 2. Tap-to-Focus and Tap-to-Expose
- Use CameraX `MeteringPointFactory` + `FocusMeteringAction`
- Show animated focus ring at tap point
- Auto-cancel after 3 seconds
- Visual feedback: ring shrinks when focused, turns green on success

### 3. Read Actual Camera2 Metadata
- Use Camera2Interop session capture callback on ImageAnalysis
- Read: `SENSOR_SENSITIVITY`, `SENSOR_EXPOSURE_TIME`, `LENS_FOCUS_DISTANCE`
- Feed real values into FrameAnalysisPipeline and LightingAnalyzer
- Display actual sensor values on HUD, not AI recommendations

### 4. Raise Classification Thresholds
- `isStable` (display-only): confidence >= 0.35 (keep)
- `isActionable` (auto actions): confidence >= 0.70 (new)
- Only trigger lens recommendations and settings optimization on `isActionable`
- Show scene label on `isStable`, but don't act on it

### 5. Strip HUD to Essentials
- Remove: scan lines, corner brackets, center crosshair, confidence percentage
- Keep: scene label, coaching hint, settings readout (simplified)
- Add: level indicator, zoom bar, focus ring, top control bar

### 6. Redesign Color Palette
- Replace neon green tactical aesthetic with warm professional palette
- Primary text: clean white (#E0E0E0)
- Accent: warm amber (#F0A830)
- AI indicators: soft cyan (#70C0C0)
- Background: deep translucent dark (#0A0A0AE0)
- Remove all scan-line and military visual metaphors

## Important Features (Tier 2 — Good Camera App)

### 7. Pinch-to-Zoom
- Use CameraX `setZoomRatio()` with gesture detector
- Show zoom level indicator bar
- Smooth transitions between lens positions

### 8. Flash Control
- Toggle: AUTO / ON / OFF
- Button in top control bar
- Use `ImageCapture.flashMode`

### 9. Self-Timer
- Options: OFF / 3s / 10s
- Countdown overlay on viewfinder
- Haptic tick on each second

### 10. Rewrite Coaching in Plain English
- No photography jargon
- Single clear physical action per hint
- Examples:
  - "FIND LEADING LINES" → "Look for a road or path pointing into the scene"
  - "HORIZON ON LOWER THIRD" → "Tilt your phone down slightly to show more sky"
  - "HOLD STEADY" → "Hold very still for 3 seconds"
  - "FILL FRAME WITH SUBJECT" → "Take two steps closer"
  - "RULE OF THIRDS" → "Place your subject slightly off-center"

### 11. Level/Horizon Indicator
- Use accelerometer (TYPE_ACCELEROMETER)
- Show horizontal line across center that tilts with phone
- Turns green when within 1 degree of level
- Subtle, always visible when HUD is on

### 12. Remove Confidence Percentage
- Never show "55%" or any classification confidence
- Show scene label only when confident enough to display
- Show nothing when unsure (no "READY" placeholder)

## Quality Features (Tier 3)

### 13. Aspect Ratio Toggle
- 4:3 / 16:9 / 1:1 / Full
- Visual crop overlay on viewfinder

### 14. Post-Capture Flash
- Brief white flash overlay on viewfinder when photo captured
- Confirms capture happened

### 15. Simplified Scene Readout
- Remove lighting label (non-actionable information)
- Show scene type only when confident: "Portrait" not "SCENE: PORTRAIT"
- Small, unobtrusive, top-left

### 16. Actual Settings Display
- In PHOTO mode: show "Auto" or actual sensor readback
- In PRO mode: show manual settings being applied
- Never display values the camera isn't using
