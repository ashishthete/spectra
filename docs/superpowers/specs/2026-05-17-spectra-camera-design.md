# SPECTRA — AI-Powered Camera App for Samsung S24 Ultra

**Tagline:** *"Intelligence in every frame."*

## Overview

SPECTRA is a full camera replacement app for the Samsung Galaxy S24 Ultra that uses on-device ML and cloud AI to automatically optimize every aspect of photography. Instead of manual mode switching, the AI detects what the user is shooting and silently adjusts lens selection, exposure, white balance, and focus — while providing visible tactical-style composition coaching through a sci-fi heads-up display.

## Target Device

Samsung Galaxy S24 Ultra only. Four-lens system:

| Lens | Sensor | Focal Length | Zoom |
|---|---|---|---|
| Ultrawide | 12MP | 13mm | 0.6x |
| Main | 200MP | 23mm | 1x |
| Telephoto | 10MP | 69mm | 3x |
| Telephoto | 50MP | 115mm | 5x |

Min SDK: API 34 (Android 14).

## Camera Modes

Four modes, all sharing the same HUD. The AI adapts its behavior per mode:

### PHOTO (default)
- Full auto — scene detection, lens selection, all settings optimized
- Composition coaching active
- Full HUD visible

### PORT (Portrait)
- Forces subject detection, prefers 3x telephoto
- Applies bokeh estimation
- Pose coaching prioritized over composition coaching
- HUD adds subject tracking box, pose directives replace composition hints

### NIGHT
- Long-exposure strategy, motion detection becomes critical (warns if not steady)
- Suggests tripod when needed
- HUD dims to deep green, adds stability indicator and exposure countdown

### PRO (Manual)
- AI provides recommendations but does NOT auto-apply
- User has full manual control via sliders for ISO, shutter speed, WB, focus distance, EV
- Each slider shows the AI's recommended value as a ghost marker
- User can tap the ghost marker to snap to AI recommendation
- HUD shows `MANUAL OVERRIDE` when user deviates from AI suggestion

## AI Recommendation UX

Hybrid approach:

- **Silent auto-adjust:** Technical settings (lens selection, ISO, shutter speed, white balance, focus, EV) are applied automatically and instantly. The user sees the current values in the HUD but doesn't need to approve changes.
- **Visible coaching:** Composition recommendations (tilt angle, reframing, pose adjustments) appear as HUD directives that the user must physically act on. These are displayed prominently but can be dismissed with a tap.

## Visual Style — Neon HUD

Military/sci-fi tactical heads-up display aesthetic:

- Pure black background, neon green (#00ff88) as the primary accent color
- Monospace font (Courier New) for all readouts
- Corner brackets framing the viewfinder
- Scan line overlay effect for the tactical feel
- Crosshair at center with rule-of-thirds grid (subtle)
- Animated arrow indicators for composition coaching directives

### HUD Zones

| Zone | Content |
|---|---|
| Top-left | Scene type + confidence + lighting condition |
| Top-right | Active lens + camera settings (ISO, shutter, aperture, WB, EV) |
| Left edge | Motion level bars + subject distance readout |
| Right edge | Lens match confidence bars (shows AI's scoring for each lens) |
| Center | Crosshair + subtle rule-of-thirds grid |
| Bottom-center (above controls) | Composition coaching directive with animated arrow |
| Bottom bar | Mode selector (NIGHT / PORT / PHOTO / PRO) |
| Bottom | Capture controls (gallery thumbnail, shutter, lens toggle) |

### Interactions

| Gesture | Action |
|---|---|
| Tap shutter | Capture photo |
| Long-press shutter | Burst mode (captures at ~10fps while held, AI locks settings at first frame to avoid flicker between shots) |
| Tap lens toggle | Cycle lenses (overrides AI) |
| Swipe mode bar | Switch camera mode |
| Tap coaching directive | Dismiss hint |
| Double-tap viewfinder | Toggle HUD on/off |

## Architecture — Layered Modules

Four Gradle modules with clean boundaries:

### :core
- Shared data models: `SceneType`, `LensConfig`, `CameraSettings`, `SceneAnalysis`, `CoachingDirective`
- Interfaces/contracts between modules
- Dependency injection setup (Hilt)
- User settings/preferences (DataStore)

### :camera
- CameraX for preview lifecycle and capture
- Camera2 interop for manual parameter control (ISO, shutter speed, WB, focus, EV)
- Physical lens management — enumerate and switch between 4 cameras
- Capture pipeline: capture at full resolution with current settings, save to MediaStore
- Provides preview frames to :ai-engine for analysis (every 5th frame, ~200ms cycle)

### :ai-engine
Three subsystems:

**On-Device ML Pipeline (<50ms per frame):**

| Model | Purpose | Input | Output |
|---|---|---|---|
| Scene Classifier | Detect scene type | Camera frame (224x224) | Scene type + confidence (e.g., LANDSCAPE 94.2%) |
| Lighting Analyzer | Detect lighting conditions | Camera frame + exposure metadata | Lighting type (golden hour, backlit, harsh, low-light, studio) |
| Motion Detector | Detect subject movement | 3 consecutive frames | Motion level (static, slow, fast) + direction |
| Subject Distance | Estimate depth | Camera frame + AF data | Near/mid/far + estimated distance |

All models run on a shared TFLite interpreter using GPU delegate.

**Decision Engine (pure Kotlin logic):**

Takes combined signals and produces:
- `LensRecommendation` — which lens + confidence scores for all 4 lenses
- `SettingsProfile` — optimal ISO, shutter speed, WB, focus, EV for the scene
- `CoachingHints` — composition suggestions derived from on-device analysis

Example decision rules:
- Landscape + golden hour + static → 200MP main, ISO 100, warm WB (5500K)
- Portrait + indoor + static → 3x telephoto, ISO 400, auto WB
- Pet + fast motion → Main lens, ISO 800, shutter 1/1000, burst mode suggested
- Food + near distance → Main lens (crop to macro), ISO 200, warm WB

**Cloud Coaching (on-demand, when user pauses 2+ seconds):**
- Sends current frame to Claude Vision API
- Receives advanced composition analysis: rule of thirds alignment, leading lines, symmetry, suggested angle changes
- Parses response into HUD directives: "TILT UP 10° · GOLDEN RATIO ALIGN", "STEP LEFT 0.5M · LEADING LINES"
- Cached per scene — won't re-query if frame similarity is above 70% (measured by pixel-level comparison of downscaled thumbnails). Cache expires after 10 seconds regardless.

### :app
- Jetpack Compose UI for the entire HUD viewfinder
- Custom Canvas drawing for overlay guides (grid, arrows, tracking boxes)
- Mode switching logic
- PRO mode manual control sliders
- Wires :camera and :ai-engine together via ViewModels

## Data Flow

```
Camera Preview Frames (:camera)
       │
       ▼ (every 5th frame)
  On-Device ML Pipeline (:ai-engine)
       │
       ├─→ SceneAnalysis {sceneType, confidence, lighting, motion, distance}
       │
       ▼
  Decision Engine (:ai-engine)
       │
       ├─→ LensRecommendation → :camera auto-switches lens (silent)
       ├─→ SettingsProfile → :camera auto-applies ISO/shutter/WB/EV (silent)
       ├─→ CoachingHints → :app HUD displays directives (visible)
       │
  [User pauses 2+ seconds]
       │
       ▼
  Cloud Coaching (:ai-engine → Claude Vision API)
       │
       └─→ Advanced directives → :app HUD displays pose/angle coaching (visible)
```

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose |
| Camera | CameraX + Camera2 interop |
| DI | Hilt |
| On-device ML | TensorFlow Lite + GPU Delegate |
| Pose Detection | MediaPipe Pose |
| Cloud AI | Claude Vision API |
| Networking | Ktor Client |
| Settings | DataStore (Preferences) |
| Image Save | MediaStore API |
| Build | Gradle KTS, multi-module |

## Post-Capture

Photos save directly to the standard Android gallery via MediaStore. No built-in gallery or photo editor in this version.

## Project Phases

| Phase | Scope | Milestone |
|---|---|---|
| Phase 1 | Camera engine + HUD UI + lens switching. No AI — tactical viewfinder with manual lens selection, capture, save. | "It looks like SPECTRA and takes photos" |
| Phase 2 | On-device AI — scene detection, lighting analysis, auto-settings, auto-lens selection. HUD shows real AI data. | "It thinks and auto-adjusts" |
| Phase 3 | Composition coaching — overlay guides, arrows, rule-of-thirds alignment, motion + distance analysis. | "It coaches you in real-time" |
| Phase 4 | Cloud AI integration — Claude Vision for advanced pose/angle coaching. PRO mode with AI ghost values. | "Full SPECTRA experience" |

## Photo Tips & Reference Cards

When the AI detects a scene, the app can show a **reference card** — a sample photo demonstrating what a great version of that shot type looks like, paired with 2-3 actionable tips. This feature helps users learn photography techniques in context.

**How it works:**
- Bundled reference images (one per scene type) ship with the app (~15 images, ~5MB total)
- When scene detection stabilizes (same scene for 3+ seconds), a small thumbnail appears in the HUD
- User taps the thumbnail to expand a full reference card overlay
- Card shows: reference photo, scene-specific tips (e.g., "Lower your angle for a more dramatic landscape"), and the recommended settings

**Reference card content per scene type:**
- Landscape: horizon placement, golden hour timing, leading lines
- Portrait: eye-level framing, background separation, natural light direction
- Food: overhead vs 45° angle, natural light, props/context
- Night: stability tips, long exposure technique, light sources
- Architecture: vertical lines, symmetry, perspective correction
- Macro: focus stacking, steady hands, diffused light
- Pet/Animal: eye-level with subject, burst mode, patience tips
- Action/Sports: panning technique, shutter speed priority, anticipation

**Caching:** Reference images are bundled in the APK assets. Tips text is hardcoded per scene type. No network needed.

## Out of Scope (v1)

- Video recording
- Built-in gallery
- Photo editing
- Devices other than S24 Ultra
- Widgets or quick settings tiles
