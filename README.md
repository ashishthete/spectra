# Spectra Camera

AI-powered camera app for Samsung Galaxy S24 Ultra. Spectra uses on-device machine learning and Camera2 hardware control to deliver professional-quality photos with intelligent scene detection, real-time coaching, and advanced post-processing.

## Features

- **AI Scene Detection** — TFLite scene classifier (15 scene types) with heuristic fallback, temporal hysteresis for smooth transitions
- **Smart Presets** — Constraint-based exposure (not rigid overrides) with per-scene ISO/shutter/EV boundaries
- **HDR Capture** — 3-5 frame exposure bracket with semantic Mertens fusion and tile alignment
- **Portrait Mode** — MiDaS depth estimation, Gaussian bokeh with guided-filter edge refinement, portrait lighting modes
- **Night Mode** — Multi-frame stacking with temporal denoise, gyro-gated long exposure
- **PRO Mode** — Manual ISO/shutter/WB/focus, focus peaking, zebra stripes, false color, waveform monitor
- **Beauty Mode** — Frequency-separation skin smoothing with CbCr skin-tone detection
- **LUT Color Grading** — 5 built-in 3D LUTs (Cinematic, Vivid, Moody, Film, Neutral) with trilinear interpolation
- **Local Tone Mapping** — Base/detail decomposition with filmic curve for post-HDR shadow lift
- **RAW/DNG Output** — Full sensor data capture via Camera2 RAW_SENSOR
- **AI Coaching** — Real-time composition tips, horizon guidance, Claude Vision cloud coaching
- **Video** — Hardware EIS, 4K recording with continuous AF

## Architecture

```
app/          UI layer (Jetpack Compose, ViewModels)
ai-engine/    Scene classification, decision engine, coaching, motion detection
camera/       Camera2 control, capture pipeline, image processing, GPU shaders
core/         Shared models, settings, presets
```

## Tech Stack

- Kotlin, Jetpack Compose, CameraX + Camera2 interop
- TensorFlow Lite (scene classifier, depth estimator) with NNAPI/GPU/CPU delegates
- ML Kit selfie segmentation
- Hilt dependency injection
- OpenGL ES compute shaders (bilateral filter, Mertens fusion)

## Build

```bash
# Debug build
./gradlew :app:assembleDebug

# Run tests
./gradlew testDebugUnitTest

# Release build (requires keystore.properties)
./gradlew :app:bundleRelease
```

## CI/CD

- **CI** — Runs on PR/push to main: unit tests + debug build
- **Release** — Triggered by `v*` tags: builds AAB, deploys to Play Store via Fastlane

## License

Private — All rights reserved.
