# SPECTRA World-Class Camera Review

**Date:** 2026-05-19  
**Reviewer stance:** professional photographer, cinematographer, computational photography engineer, mobile camera engineer, color scientist, camera UX/product reviewer.  
**Product goal reviewed against:** "Helping ordinary humans consistently capture emotionally powerful, cinematic, professional-quality images effortlessly."

---

## Bottom Line

SPECTRA is ambitious and unusually thoughtful for an AI camera app. It has real architecture: scene analysis, motion source detection, lens recommendations, real sensor metadata, RAW capture, depth estimation, skin tone analysis, HDR fusion code, pro overlays, coaching, and post-capture review. This is not a toy.

But brutally: it still feels more like an impressive research prototype than a camera a professional would trust or a beginner could rely on under pressure.

The biggest weakness is not one missing feature. It is a trust gap. The app often knows photography words and shows photography-looking UI, but the actual capture behavior does not always match the promise. A beginner may think the app is controlling ISO, shutter, HDR, bokeh, color, and cinematic behavior at a professional level. A professional will quickly notice when the capture pipeline, preview, and guidance are not yet coherent.

The app's strongest idea is real-time coaching. Its weakest area is that coaching is not yet tightly coupled to a world-class imaging pipeline. Right now the app can tell a user "try Night mode" or "hold steady," but it cannot always guarantee that the final image receives the computational treatment implied by that suggestion.

**Current global score: 5.8 / 10** against iPhone Camera, Pixel Camera, Samsung Expert RAW, Halide, Blackmagic Camera, Fujifilm color science, and Sony/Leica-style camera trust.

This is a good foundation. It is not yet a best-in-class camera.

---

## Scorecard

| Area | Score | Brutal Read |
|---|---:|---|
| Camera intelligence | 6.2 | Smart ideas, but too rule-based and not enough subject-aware certainty |
| Auto exposure logic | 5.5 | Better than fake settings now, but still not fully deterministic outside semi-auto/pro paths |
| Shutter/ISO decisions | 5.8 | Photographically reasonable recommendations, uneven hardware enforcement |
| White balance | 5.8 | Kelvin logic exists, mixed lighting and skin-aware WB are not fully mature |
| Autofocus behavior | 5.4 | Tap/face focus exists, but no professional subject tracking model |
| Scene detection | 6.0 | TFLite path exists, but model quality and confidence behavior need proof |
| AI coaching | 7.0 | The best differentiator, but too literal and sometimes too chatty |
| Composition guidance | 5.7 | Rule-of-thirds and horizon exist, but composition is more than thirds |
| HDR/dynamic range | 5.0 | Lower-level code is promising; real capture path under-delivers |
| Night mode | 4.8 | Suggestions exist, but not a Pixel/Night Sight style pipeline |
| Portrait/depth | 5.7 | Real depth model path exists, rendering still not premium |
| Skin tones | 6.0 | Monk Skin Tone analysis exists; full Real Tone behavior is incomplete |
| Color science | 5.2 | Styles are useful but still too ColorMatrix/lookup-like, not a true look system |
| Texture/noise/sharpness | 5.0 | Risks waxiness, averaging blur, and mobile-looking processing |
| Video/cinematic | 3.8 | Recording exists, but cinematography intelligence is mostly missing |
| Beginner UX | 6.5 | Approachable, but more controls and overlays than a nervous beginner wants |
| Professional UX | 5.5 | Pro tools exist, but not enough trust, consistency, or manual workflow polish |
| Premium feel | 6.0 | Has ambition; still needs calmer hierarchy and tighter visual discipline |

---

## Code-Grounded Second Pass: What Is Actually Wrong And The Correct Fix

This section is the important correction layer. The review above is the product baseline; this section is grounded in the actual app implementation.

### 1. The App No Longer Merely Shows Fake Settings, But The Control Semantics Are Still Wrong

**What the code does now**

`CameraViewModel.applySettingsToHardware()` sends AI settings into `SpectraCameraController.applySettings()`. In `SpectraCameraController.applySettings()`, every non-manual call currently routes to `settingsApplier.applySemiAuto(...)`, which turns AE off and applies ISO/shutter through Camera2.

That is better than the older "display recommendations only" behavior. The app is not purely faking ISO/shutter anymore.

**The problem**

The code now risks the opposite problem: it applies semi-auto control too broadly. The `manual`, `semiAuto`, and `motionLevel` parameters are not meaningfully respected in `SpectraCameraController.applySettings()`. The `applyAutoWithHints()` path exists, but the controller does not use it for normal smart-auto decisions.

Professionals will appreciate real sensor control, but beginners can suffer if the AI is overconfident. A wrong scene classification can now force a bad shutter/ISO instead of merely suggesting one.

**Correct fix**

Create three explicit exposure modes:

- `AUTO_TRUST_CAMERA`: use Android AE/AWB, show actual values only.
- `SMART_CONSTRAINED`: keep AE active but apply face metering, EV bias, FPS/shutter constraints where possible, WB hints, and ISO ceiling hints if supported.
- `SMART_MANUAL`: turn AE off and apply ISO/shutter only when scene confidence and lighting confidence are high.

Implementation target:

- Update `SpectraCameraController.applySettings()` to use `semiAuto` and a new exposure policy enum.
- Use `DecisionEngine.getExposureStrategy()` instead of ignoring it.
- Use `applyAutoWithHints()` for low/medium confidence scenes.
- Use `applySemiAuto()` only when confidence is high and the preset truly needs deterministic shutter/ISO.
- Reset stale smart settings when `SceneAnalysis.isActionable` becomes false for more than a short timeout.

Done means:

- Auto mode never pretends to control ISO/shutter.
- Smart presets control hardware only when confidence is high.
- The HUD labels clearly separate `Sensor chose` from `AI target`.

### 2. HDR Is Still The Clearest Incorrect Implementation

**What the code does now**

`HdrProcessor` contains correct-looking helpers for:

- 3-frame `[-2, 0, +2 EV]` exposure brackets.
- 5-frame stable-scene brackets.
- DRD-based trigger logic.
- Mertens/pyramid fusion.

But `CameraViewModel.capturePhotoInternal()` does not use that path. It uses:

```kotlin
val evSteps = listOf(-4, 4)
```

Then it captures two AE-compensation-biased frames and calls `mergeAndSaveHdr()`.

**The problem**

This is not professional HDR. It is a weak two-frame bracket with no base exposure. On a device with 1/10 EV steps, `-4, +4` is roughly `-0.4 EV, +0.4 EV`, not `-2 EV, +2 EV`.

Beginners will see "HDR" and expect recovered skies/faces. Professionals will see clipped highlights, flat shadows, or ghosting and immediately distrust the camera.

**Correct fix**

Replace the ViewModel HDR branch with the existing `CaptureManager.captureHdrBracket()` path.

Implementation target:

- Use `cameraController.applyBracketExposure(exposureNs, iso)` for true exposure-time brackets.
- Use `HdrProcessor.computeBracketExposures(baseExposureNs, baseIso)` for normal HDR.
- Use `HdrProcessor.computeBracketExposures5Frame(...)` only when gyro and subject motion are stable.
- Always include the base frame.
- Restore auto/semi-auto state after capture.
- Add unit tests that fail if HDR active capture uses fewer than three frames.

Done means:

- HDR active path captures 3 frames minimum.
- 5-frame HDR is gated by stability.
- The HDR badge means the app actually performed meaningful dynamic range capture.

### 3. The Main AI-Enhanced Capture Path Bypasses Much Of The Rich Processing Pipeline

**What the code does now**

Normal capture calls `captureManager.captureSmartPhoto()`, saves an original, then asynchronously calls `saveProcessedCopy(...)`.

`saveProcessedCopy(...)` currently decodes the JPEG, runs `ImageEnhancer.enhance(...)`, quality-scores it, and saves an `_AI` copy if the score is not worse. It accepts `beautyLevel`, `style`, `isHdr`, `faceRects`, `isPortraitMode`, and `processing`, but the actual body mostly uses `ImageEnhancer` with `preset`.

Meanwhile, the richer `applyPostProcess(...)` path contains:

- Tone curves / 3D LUTs.
- Noise reduction.
- Local tone mapping.
- Shadow recovery.
- Sky GND.
- Lab beauty.
- Highlight rolloff.
- Portrait bokeh.
- Vignette.

But that full path is not the primary normal AI-enhanced copy path.

**The problem**

The UI suggests that style, beauty, HDR, and portrait intelligence are part of the capture experience. In the main AI copy flow, many of those choices may not actually be applied.

This is exactly the kind of trust mismatch that makes an app feel amateur. A user selects Cinematic or Beauty or Portrait, then the final "AI" image may mostly be a guided tone enhancement rather than the full look they expected.

**Correct fix**

Unify `saveProcessedCopy()` and `applyPostProcess()` into one capture processing graph.

Implementation target:

- Create `CaptureProcessingGraph.process(inputUri, CaptureProcessingRequest)`.
- Request fields should include:
  - `photoStyle`
  - `beautyLevel`
  - `isHdr`
  - `isPortraitMode`
  - `faceRects`
  - `processingParams`
  - `sceneContrast`
  - `captureIso`
  - `preset`
  - `activeLens`
  - `actualSensorMetadata`
- The graph decides which stages run and logs which were skipped.
- Both original post-process and AI-enhanced copy should call the same graph.
- If an enhancement loses the quality score, save original but explain "AI enhancement skipped".

Done means:

- PhotoStyle visibly affects final output.
- Beauty affects final output only when enabled and faces are found.
- Portrait mode applies depth bokeh when available.
- Smart Review can honestly say what changed.

### 4. Preview And Final Capture Are Still Not The Same Product

**What the code does now**

`ViewfinderScreen` applies a ColorMatrix preview path and a `PreviewEffect` AGSL path. Final capture uses `ImageEnhancer`, `ToneCurveEngine`, `NoiseReducer`, `HdrProcessor`, and `DepthBokeh` in other paths.

**The problem**

This is a classic camera trust failure. The user composes based on one visual truth and receives another.

Professionals hate this because it breaks previsualization. Beginners hate it because they cannot understand why the saved photo looks different.

**Correct fix**

Create a shared preview/capture look contract.

Implementation target:

- Define `LookParams` generated from style, preset, scene, skin tone, HDR state, and lighting.
- Preview consumes `LookParams`.
- Final processing consumes the same `LookParams`.
- Heavy capture-only stages like multi-frame HDR and depth bokeh should expose a preview approximation with an "estimated final look" flag.

Done means:

- Natural, Vivid, Film, Warm, and Cinematic have the same tonal intent in preview and output.
- HDR preview badge and final HDR behavior agree.
- Portrait preview does not imply bokeh unless bokeh can actually be rendered.

### 5. Burst Mode Is Not A Real Camera Burst Yet

**What the code does now**

`startBurst()` loops `capturePhotoInternal()` every 200ms. That means each burst frame can trigger the same heavy capture, HDR decision, save, and enhancement behavior as a normal still.

**The problem**

That is not how a camera burst should work. A real burst prioritizes low-latency frame acquisition first, then scoring/merging/review after the burst ends.

Beginners expect holding the shutter to capture the peak moment. Professionals expect the camera not to run a full post-processing pipeline between burst frames.

**Correct fix**

Implement a dedicated burst session.

Implementation target:

- `startBurstSession()`: lock exposure/focus, disable heavy post-processing during acquisition.
- Capture in-memory JPEG/YUV frames as fast as the device supports.
- `stopBurstSession()`: run best-frame scoring and optional temporal denoise.
- Score using face sharpness, eyes open, expression, motion blur, exposure, and subject position.
- Show one "Best" frame plus optional filmstrip.

Done means:

- Burst acquisition is fast and predictable.
- The app picks better moments, not just more photos.
- Burst does not trigger multiple asynchronous AI enhancement jobs.

### 6. Defaulting The App To Portrait Is Too Opinionated

**What the code does now**

`HudState` defaults to `CameraPreset.PORTRAIT`.

**The problem**

For an app whose goal is ordinary users taking all kinds of photos, defaulting to Portrait makes the camera feel pre-biased. It can also push portrait settings, hints, and expectations into non-portrait scenes.

Professional camera products usually start from a trustworthy general mode. Specialized modes are selected by intent or detected by confidence.

**Correct fix**

Default to `AUTO`.

Implementation target:

- Set `HudState.preset = CameraPreset.AUTO`.
- Let scene analysis suggest People/Portrait only when faces are stable and framing supports it.
- If the app wants a "people-first" philosophy, call it `Smart` and make it adaptive, not permanently Portrait.

Done means:

- New users open to a general camera.
- Portrait advice appears because people are actually present.

### 7. Skin Tone Intelligence Exists But Is Not Fully Closed Loop

**What the code does now**

`SkinToneClassifier` estimates MST shade, AWB shift, and EV compensation. `CameraViewModel.computeMstEvBias()` applies EV bias from the primary face.

**The problem**

The AWB shift is computed but not applied as a first-class hardware/color-pipeline decision. Group shots are primary-face biased.

This means the app has the vocabulary of inclusive color science, but not yet the complete behavior.

**Correct fix**

Use MST as a stable input to exposure and color.

Implementation target:

- Aggregate all faces by area, center proximity, and sharpness.
- Compute group-safe EV and WB corrections.
- Apply a capped AWB shift to `CameraSettings.whiteBalanceKelvin` before hardware application.
- Preserve mood: do not neutralize golden hour or blue hour aggressively.
- Feed MST into final tone/color processing to avoid ashy darker skin or pink clipped lighter skin.

Done means:

- Skin-tone fields affect capture and output, not just metadata.
- Diverse group shots avoid sacrificing one subject for another.

### 8. Portrait Mode Needs To Fix Geometry Before Blur

**What the code does now**

The app recommends 3x for portraits and has depth-map bokeh with fallback ellipse blur.

**The problem**

Portrait quality is mostly decided before software bokeh:

- Lens choice.
- Subject distance.
- Camera height.
- Face angle.
- Light direction.
- Background distance.

If the user shoots a face too close at 1x, depth blur cannot make it professional.

**Correct fix**

Make portrait coaching geometry-first.

Implementation target:

- Detect large face near wide lens and warn: "Step back and use 3x for a natural face."
- Detect subject too close to background: "Move subject away from background for stronger separation."
- Detect harsh overhead light: "Turn toward window or open shade."
- Use depth bokeh only after geometry and light are acceptable.

Done means:

- Portrait mode improves perspective, not just background blur.

### 9. Composition Coaching Is Too Rule-Of-Thirds Heavy

**What the code does now**

`CompositionAnalyzer` uses faces or saliency peak, scores nearest thirds intersection, and produces movement suggestions.

**The problem**

Rule of thirds is useful for beginners, but professional composition is about visual weight, gesture, gaze direction, negative space, edge discipline, depth, and story.

Also, phrases like "Move subject slightly left" can be ambiguous. Users move the person, the phone, or the frame?

**Correct fix**

Upgrade composition from thirds to intent-aware framing.

Implementation target:

- For people:
  - Protect headroom.
  - Avoid cutting hands/feet.
  - Leave space in direction of gaze/motion.
  - Avoid background objects through head.
- For food/product:
  - Detect plate/product boundaries.
  - Suggest overhead vs 45-degree angle.
  - Warn about clutter or specular glare.
- For landscape:
  - Encourage foreground/midground/background layering.
  - Suggest horizon placement based on sky interest.
- Use direct camera language: "Move camera left" or "Place subject on right third."

Done means:

- Coaching makes better images, not merely more rule-compliant images.

### 10. Video Is Functionally Present But Not Cinematic Yet

**What the code does now**

Video uses CameraX `Recorder` at highest quality, with basic start/stop and timer UI.

**The problem**

Cinematic video is not just resolution. It requires exposure continuity, stable color, focus behavior, motion cadence, composition over time, and audio awareness.

Blackmagic, iPhone, and Sony-like experiences win because they help users maintain control during motion.

**Correct fix**

Add a separate video intelligence model.

Implementation target:

- Lock or smoothly ramp exposure/WB during recording.
- Add tap-to-track subject.
- Add focus transition speed.
- Warn about walking shake.
- Coach "hold 3 seconds before and after movement."
- Add audio level meter if audio is enabled.
- Add optional cinematic guides: 2.39:1 frame, headroom, lead room, center-safe.

Done means:

- Video mode stops feeling like photo mode with a record button.

### 11. RAW Capture Works, But It Can Interrupt The Camera Experience

**What the code does now**

`captureDng()` unbinds CameraX, opens a separate RAW Camera2 session, captures DNG, then reinitializes.

**The problem**

This is acceptable for a prototype, but it can feel clunky or fragile in a professional camera. It also means RAW capture is not truly integrated with the preview/capture session.

**Correct fix**

Make RAW an integrated capability, or be honest about the interruption.

Implementation target:

- Short term: show "Saving RAW..." and block conflicting actions.
- Clearly label fallback JPEG copy as "RAW fallback unavailable", not RAW.
- Long term: build a Camera2 session that supports JPEG/YUV/RAW outputs together where stream combinations allow it.

Done means:

- RAW users trust what was saved.
- The preview does not mysteriously reset without explanation.

### 12. Smart Review Should Explain The Actual Processing, Not Just Offer A Better Copy

**What the code does now**

Normal capture starts AI enhancement in the background, but Smart Review is not consistently shown. HDR path shows Smart Review more explicitly.

**The problem**

If the app creates an AI-enhanced image, users need to know why it exists. Otherwise they see duplicate photos or silently altered photos.

**Correct fix**

Make Smart Review transparent and low-friction.

Implementation target:

- Store a list of applied stages from `CaptureProcessingGraph`.
- Show:
  - "Recovered highlights"
  - "Reduced noise"
  - "Protected skin tone"
  - "Added portrait depth"
  - "Skipped AI because original was better"
- Do not interrupt every capture. Show only after meaningful enhancement or user tap.

Done means:

- Users trust enhanced output because the app explains only what mattered.

### 13. The Correct Product Strategy: Cut Visible Complexity, Increase Invisible Correctness

The app should not win by showing more camera intelligence. It should win by giving normal users fewer chances to fail.

Correct product hierarchy:

1. **Invisible capture correctness**
   - Focus, exposure, WB, shutter, HDR, denoise, skin, and preview/output match.
2. **One decisive live intervention**
   - Only the highest-impact instruction appears.
3. **Post-capture explanation**
   - Tell the user what the AI did after the photo, not while they are anxious.
4. **Pro mode honesty**
   - All manual tools should be exact and predictable.

If a feature is not trustworthy yet, hide it or label it experimental. Premium cameras feel restrained.

---

## What Feels Amateur

### 1. Too many photo concepts are visible before they are truly dependable

The app exposes styles, presets, histograms, HDR, lens hints, AI settings, smart review, beauty, pro panels, RAW, zebra, peaking, grids, and coaching. These are all legitimate camera ideas. The amateur part is that the app sometimes feels like it wants users to see how smart it is.

Professionals do not want a camera that performs intelligence. They want a camera that disappears until a useful intervention is necessary.

Beginners do not need to see every photographic concept. They need one clear next action: move closer, turn toward window light, hold still, tap face, step back, switch to portrait, or shoot now.

Better alternative:

- Create two interaction layers:
  - **Everyday mode:** one main coaching directive, one confidence indicator, one capture button, minimal controls.
  - **Creator/Pro mode:** histograms, peaking, RAW, grids, explicit settings, manual overrides.
- Hide most advanced controls until the user expresses intent.
- Let the AI make invisible decisions by default.

### 2. Preview and final image still risk disagreeing

`ViewfinderScreen` applies a preview ColorMatrix and AGSL effects, while final capture uses a separate processing path through `CaptureManager`, `ImageEnhancer`, `NoiseReducer`, style processing, HDR, beauty, and depth bokeh.

This creates a professional trust problem. If the preview shows one color/contrast/bokeh behavior and the capture saves another, the user learns not to trust the camera.

Photography principle:

- Framing is not only geometry. It is exposure, contrast, color, highlight rolloff, background blur, skin tone, and edge separation.
- A camera that lies in preview destroys composition confidence.

Better alternative:

- Build a shared `ProcessingGraph`.
- Preview and capture must consume the same style, WB, tone, highlight, skin, HDR, and lens parameters.
- Show "final look preview unavailable" only when using a heavy mode like multi-frame HDR or depth render.

### 3. The app sometimes coaches like a tutorial, not like an experienced photographer

Current hints are useful, but many are literal:

- "Try Landscape mode"
- "Try ultrawide"
- "Tap the eyes to lock focus there"
- "Hold shutter for burst"

Good, but not enough. A great photographer says what changes the image emotionally:

- "Turn her toward the window - the face will soften."
- "Step back and use 3x - the background will compress."
- "Lower the phone slightly - the room will feel taller."
- "Wait for the hand to come down - cleaner silhouette."
- "Shoot now - light is good and eyes are open."

Better alternative:

- Add a coaching taxonomy:
  - **Technical rescue:** hold steady, too dark, blown highlights, focus risk.
  - **Composition improvement:** move left, lower camera, add foreground, simplify edge clutter.
  - **Lighting improvement:** face window, find open shade, avoid overhead light.
  - **Moment timing:** smile peak, eyes open, subject still, decisive gesture.
  - **Silence:** scene is already good.

---

## Camera Intelligence Review

### Auto Exposure Logic

The app now has real sensor metadata and semi-auto/pro paths, which is a serious improvement. `Camera2SettingsApplier.applyAutoWithHints()` applies EV bias, WB overrides, AE FPS hints, and Camera2 request options. `DecisionEngine` also defines exposure strategies per preset.

The problem is consistency.

In normal auto-like behavior, the app still often recommends settings while relying on Android AE to choose the actual exposure. That is acceptable only if the UI clearly says "recommended" rather than implying control. Professionals hate fake precision. Beginners misunderstand it.

Photography principle:

- Exposure is a creative and physical decision. If the app says Action, the shutter speed must actually be fast enough. If it says Night, the exposure strategy must actually support clean low-light capture.

Better alternative:

- For Auto: show actual sensor values only.
- For Smart Presets: use semi-auto constraints when confidence is high.
- For low confidence: stay in normal AE and do not pretend precision.
- Use exposure strategy gates:
  - Portrait: minimum 1/125s for adults, 1/250s for children.
  - Action: minimum 1/1000s in daylight, warn when light cannot support it.
  - Food: protect highlights on plates and sauces.
  - Landscape: base ISO, highlight preservation.
  - Night: short burst or long exposure depending on stability.

### Auto ISO Behavior

The `DecisionEngine` settings are often photographically reasonable. Example: landscape daylight ISO 50 and 1/500s, portrait low light ISO 400 and 1/60s, action daylight 1/2000s.

The risk: if the sensor does not actually use those values, the app becomes theater.

Professionals perceive this instantly. They will look at motion blur, noise, and EXIF and know whether the camera did what it claimed. Beginners will blame themselves.

Better alternative:

- Enforce ISO ceilings per mode when using semi-auto.
- In Auto, expose ISO/shutter as "camera chose," not "AI chose."
- Add "quality risk" language:
  - "Action may blur in this light."
  - "Night shot needs 2 seconds still."
  - "Portrait will be noisy unless subject faces light."

### Shutter Speed Decisions

The app understands that motion should increase shutter speed. `MotionDetector` distinguishes camera shake, subject motion, and panning using frame diff plus gyro. That is good.

But the camera should go beyond "fast motion = faster shutter."

Professional shutter logic depends on:

- Subject speed
- Subject distance
- Focal length
- Stabilization
- Desired motion blur
- Whether the user is panning
- Whether there are faces

Better alternative:

- Compute minimum shutter from focal length and motion:
  - Static subject: `1 / equivalent focal length`, adjusted for OIS.
  - People: 1/125s minimum.
  - Kids/pets: 1/250s to 1/500s.
  - Sports: 1/1000s+.
  - Panning: deliberately allow 1/30s to 1/125s with subject tracking.
- Teach the user only when needed: "Track smoothly for motion blur" is better than "panning detected."

### White Balance Intelligence

`LightingAnalyzer` has mixed lighting detection and graduated WB correction logic. `SkinToneClassifier` computes AWB shifts. But the full loop is not yet cohesive.

The app should not simply chase a global Kelvin. That is how mobile cameras make skin look gray under mixed indoor/window light.

Professionals care about skin and mood:

- Golden hour should stay warm.
- Blue hour should stay blue but not corpse-blue on faces.
- Indoor tungsten should not make faces orange.
- Mixed light should prioritize the face, not the wall.

Better alternative:

- Subject-biased AWB:
  - Detect face/skin region.
  - Estimate skin tone and local illuminant.
  - Apply small stable WB correction.
  - Preserve scene mood outside subject.
- Add hysteresis so WB does not flicker.
- For cinematic modes, allow deliberate warmth/coolness but protect skin hue.

### Focus Tracking

The app supports tap focus, long-press AE/AF lock, and face focus movements. This is necessary but not enough for a camera promising professional results.

Missing:

- Eye priority
- Subject tracking box
- Focus confidence
- Rack focus behavior for video
- Macro focus warnings
- Face distance/perspective warnings

Professional issue:

- A technically sharp wall behind a soft face ruins portraits.
- Focus must bias toward eyes, not merely a face rectangle center.

Beginner issue:

- They do not know why a photo looks bad. They say "it looks blurry" without knowing focus missed the eyes.

Better alternative:

- Add eye-priority focus for portrait.
- Add "focus risk" when face is near edge or too close.
- For video, add tap-to-track and focus transition speed.
- In Macro, show a focus breathing/rocking cue: "move phone slowly until highlight locks."

### Lens Selection Logic

This is one of the stronger parts. Portrait -> 3x is right. Architecture -> ultrawide is right. Low-light telephoto penalties are right. S24 focal buckets are fixed.

But a world-class camera would be more context-aware.

Problems:

- Portrait should not blindly prefer 3x if the subject is too close or the 3x module is too dark.
- Landscape should sometimes prefer ultrawide, sometimes main, sometimes telephoto compression.
- Food can look better at 2x/3x to avoid wide-angle plate distortion.
- Video lens switching should avoid sudden jumps.

Better alternative:

- Lens recommendation should consider:
  - Subject distance
  - Face size
  - Light level
  - Current zoom
  - Stabilization risk
  - Minimum focus distance
  - Whether capture is photo or video
- Add "why" only when useful:
  - "Use 3x for flatter face shape."
  - "Stay on 1x - telephoto is too dark."
  - "Use ultrawide, but keep people away from edges."

---

## Photography Quality Review

### Naturalness

The app is trying to avoid dumb filters, but some style paths still risk a "mobile edit" look: vivid saturation, teal-orange cinematic matrix, lifted film blacks, warm shifts, beauty overlays.

Professional images rarely look professional because saturation is higher. They look professional because light, exposure, contrast placement, color separation, and subject isolation are controlled.

Better alternative:

- Treat styles as film/color pipelines, not ColorMatrix presets.
- Build style looks around:
  - Tone curve shape
  - Highlight shoulder
  - Shadow toe
  - Chroma compression in highlights/shadows
  - Skin hue protection
  - Scene-specific contrast

### Sharpness and Texture

Mobile-phone-like images often have:

- Crispy edges
- Plastic skin
- Flat HDR midtones
- Noisy shadows with aggressive smoothing
- Halos around high-contrast edges

SPECTRA has bilateral noise reduction and guided tone mapping, but multi-frame denoise is still too simple. Beauty and bokeh can still create artificial texture behavior.

Better alternative:

- Use texture-aware processing:
  - Preserve hair, pores, fabric, foliage.
  - Smooth chroma noise more than luminance texture.
  - Avoid global sharpening on faces.
  - Sharpen eyes/lashes subtly, not cheeks.

### HDR and Dynamic Range

This is a critical weakness. The codebase contains Mertens/pyramid logic, but the actual capture path still uses a weak two-frame bracket in `CameraViewModel`.

Professionals hate HDR that looks obvious. Beginners hate silhouettes and blown skies. The correct answer is not "make everything bright." It is subject-aware tonal placement:

- Protect face exposure.
- Preserve sky highlight detail.
- Keep shadows believable.
- Avoid gray, flat midtones.
- Preserve local contrast on the subject.

Better alternative:

- Fix capture to 3-frame `[-2, 0, +2 EV]` or stable 5-frame.
- For people against bright backgrounds, use semantic HDR:
  - Sky: lower exposure/contrast recovery.
  - Face: exposure priority and skin hue stability.
  - Background: avoid crunchy local contrast.
- Add HDR confidence: if moving subjects make HDR risky, capture reference-safe.

### Portrait Rendering

The app has moved beyond face-ellipse bokeh by adding a depth estimator and guided upsampling. That is good.

But premium portrait rendering is brutally hard. The current path still risks:

- Hair edge halos
- Flat blur gradients
- Background blur that feels like software
- Specular highlights that do not behave optically
- Wrong lens perspective if using 1x too close

Professional principle:

- Portrait quality starts before blur: focal length, distance, light, pose, background distance.
- Software bokeh cannot rescue a bad perspective.

Better alternative:

- Coach capture geometry first:
  - "Step back and use 3x."
  - "Move subject away from background."
  - "Turn face toward soft light."
- Render depth after:
  - Guided edge refinement.
  - True disc/specular bokeh.
  - Face/hair matting.
  - No blur across foreground hands/objects.

### Skin Tone Realism

Monk Skin Tone classification is a real strength. The app is thinking about fairness and tonal accuracy.

But the system is not yet "Real Tone" quality because:

- AWB shift is not fully applied end-to-end.
- Exposure adjustment is primary-face biased.
- Beauty processing still needs robust Lab frequency separation.
- Mixed lighting can undermine skin even if MST classification is right.

Better alternative:

- Prioritize skin luminance range by MST shade.
- Protect red/brown undertones.
- Avoid desaturating darker skin in HDR.
- Do not lift dark skin into grayness.
- In group shots, optimize for the group, not only the largest face.

---

## User Experience Review

### Beginner Experience

The app is trying to be helpful, and many hints are understandable. That matters. But the beginner experience should be calmer.

Beginners do not need the camera to teach them all of photography at once. They need confidence.

Current risks:

- Too many modes/presets/styles can create decision anxiety.
- The "H" histogram toggle is not beginner-readable.
- Smart review may make users wonder which version is "correct."
- Pro-looking terms can intimidate.
- AI recommendations can feel like scolding if too frequent.

Better alternative:

- Use a single primary coaching pill:
  - "Move closer"
  - "Hold still"
  - "Turn toward light"
  - "Shoot now"
- Only show one action at a time.
- Add a confidence state:
  - "Ready"
  - "Almost"
  - "Needs light"
  - "Motion risk"
- Rename technical toggles in beginner mode:
  - Histogram hidden under "Exposure check"
  - HDR hidden under automatic behavior
  - RAW only in Pro

### Professional Experience

Professionals will appreciate:

- Actual sensor readouts
- RAW
- Zebra
- Focus peaking
- Manual settings
- Lens control
- AE/AF lock

They will dislike:

- Preview/capture mismatch.
- Any fake setting display.
- Unclear processing pipeline.
- Over-aggressive beauty/color/HDR.
- Weak video controls.
- No exposure waveform/false color for video.
- No reliable manual focus/rack focus UX.

Better alternative:

- Add a real Pro workspace:
  - Waveform or RGB histogram.
  - False color for exposure.
  - Manual focus distance scale.
  - Kelvin/tint controls.
  - Log/flat video option if possible.
  - Processing off/neutral mode.
  - RAW + JPEG pair visibility.

### AI Behavior

The AI should intervene less often, but more decisively.

When AI should intervene:

- Focus risk.
- Motion blur risk.
- Backlit face.
- Blown highlights.
- Bad portrait perspective.
- Subject too close to frame edge.
- Horizon unintentionally tilted.
- Moment timing: eyes closed, smile peak, gesture.

When AI should stay silent:

- User is in Pro and actively changing settings.
- Scene is already technically good.
- User dismissed the same advice repeatedly.
- The composition is intentionally centered.
- The app is not confident.

When automation becomes destructive:

- It changes exposure while the user is composing a silhouette.
- It smooths skin texture aggressively.
- It brightens night scenes until they no longer feel like night.
- It switches lenses during video or just before capture.
- It "fixes" color mood that the user wanted.

Better AI design:

- Use confidence thresholds.
- Use intent detection.
- Give fewer but better suggestions.
- Show a tiny reason only after tap/expand.
- Make "AI did this" transparent after capture, not constantly during capture.

---

## Competitor Benchmarking

### iPhone Camera

iPhone wins on trust, latency, preview/capture consistency, skin preservation, video, and silence. It rarely explains itself, but it usually makes a good decision.

SPECTRA can beat iPhone only if its coaching becomes genuinely helpful without becoming noisy.

### Google Pixel Camera

Pixel wins on computational photography: HDR+, Night Sight, Real Tone, Face Unblur, Best Take-like moment intelligence, and semantic processing.

SPECTRA borrows the right ideas but does not yet match the pipeline depth.

### Samsung Expert RAW

Samsung wins on RAW/pro integration and device-native pipeline access. SPECTRA has RAW_SENSOR DNG, but not the multi-frame RAW/Linear DNG sophistication.

### Fujifilm

Fujifilm wins on color personality and restraint. SPECTRA's styles are useful but not yet emotionally coherent film simulations.

### Lightroom Camera

Lightroom wins on edit pipeline trust and RAW workflow. SPECTRA can win for beginners if capture guidance becomes excellent.

### Halide

Halide wins on professional clarity, focus tools, exposure tools, and no-nonsense design. SPECTRA is more helpful, but less disciplined.

### Blackmagic Camera

Blackmagic wins video UX by a wide margin: codecs, monitoring, exposure tools, frame rates, shutter angle, focus pull behavior. SPECTRA video is currently basic.

### Sony Xperia Pro UX

Sony wins on camera-like manual control and photographer familiarity. SPECTRA should not copy Sony for beginners, but Pro mode should learn from it.

---

## Top 20 Critical Improvements

1. Fix HDR capture to use real 3-frame `[-2, 0, +2 EV]` bracketing with base frame.
2. Add stable-scene 5-frame HDR/night capture when gyro and subject motion allow.
3. Replace burst averaging with tile-aligned confidence-weighted multi-frame merge.
4. Create a shared preview/capture processing graph.
5. Apply MST skin-tone AWB shifts end-to-end.
6. Add group-shot skin exposure weighting across all faces.
7. Make portrait coaching geometry-first: step back, use 3x, increase background distance.
8. Add eye-priority autofocus and focus confidence.
9. Add "shoot now" moment detection for smiles, eyes open, low motion, good light.
10. Reduce beginner UI to one primary coaching directive at a time.
11. Move histograms, RAW, peaking, zebra, and technical tools behind Pro/Creator mode.
12. Add subject-aware HDR: face, sky, foreground, background.
13. Upgrade depth bokeh from box/pyramid blur to more optical disc/specular rendering.
14. Build true color pipelines for Natural, Portrait, Film, Cine, Vivid, not simple matrices.
15. Add highlight rolloff that feels camera/film-like rather than flat HDR.
16. Add thermal/performance policy for HDR, bokeh, denoise, and ML inference.
17. Add video-specific AI: exposure continuity, face tracking, stabilization warnings, focus pulls.
18. Add false color/waveform for Pro video/photo exposure review.
19. Add transparent post-capture "AI did this" summaries with before/after control.
20. Add real-world photo test sets: faces, dark skin, mixed light, backlight, kids, pets, food, night street, landscapes.

---

## Missing Killer Features

### 1. Shoot Now

A tiny, calm "Shoot now" cue when:

- Face is sharp.
- Eyes are open.
- Expression is good.
- Motion is low.
- Exposure is safe.
- Composition is stable.

This is the killer feature normal users secretly need. They do not know when the moment is good.

### 2. Light Coach

Instead of only detecting scene type, tell users how to improve light:

- "Turn her toward the window."
- "Move two steps into shade."
- "Keep the sun behind them for silhouette."
- "Avoid overhead light - face will look tired."

### 3. Background Coach

Professional photos often come from background control:

- "Move subject away from wall."
- "Step left to remove clutter behind head."
- "Use 3x for cleaner background."
- "Lower camera to hide the bright sign."

### 4. Perspective Guard

Warn when wide-angle portrait distortion will make faces unflattering:

- "Step back and use 3x for a more natural face."

### 5. Moment Stack

For people/pets/action:

- Capture a short burst invisibly.
- Pick frames with eyes open, smile, low blur, good gesture.
- Let user choose "best moment."

### 6. Mood-Preserving Night

Do not make night look like day. Keep black levels, protect lamps, reduce chroma noise, preserve atmosphere.

### 7. Cinematic Video Coach

Video needs different guidance:

- "Move slower."
- "Hold 3 seconds before cutting."
- "Track subject at same speed."
- "Too much headroom."
- "Face is underexposed."

---

## UX Redesign Suggestions

### Everyday Camera

Default screen:

- Viewfinder.
- Shutter.
- Lens control.
- One coaching pill.
- One mode intent: People, Food, Night, Action, Pro hidden under mode tray.
- Style hidden unless user opens look selector.

The main UI should answer: "Can I shoot now?"

### Creator Camera

For users who want more:

- Styles.
- Aspect ratio.
- Grid.
- Exposure check.
- Smart review.
- AI explanation.

### Pro Camera

For professionals:

- Manual ISO/shutter/WB/focus.
- RAW.
- Zebra.
- Peaking.
- Histogram/waveform.
- Processing strength.
- Neutral look.
- Lens and sensor metadata.

### Smart Review

Current SmartReview idea is good, but it needs clearer language:

- "Original"
- "SPECTRA enhanced"
- "Keep both"
- "Use enhanced"
- "Use original"

Also show what changed:

- "Recovered sky"
- "Reduced noise"
- "Protected skin tone"
- "Straightened horizon"
- "Applied portrait depth"

---

## AI Behavior Redesign

### AI Should Be Invisible First

The best AI camera feels like luck. The user should say, "I always get better photos with this," not "the app keeps telling me things."

Rules:

- One hint at a time.
- Only interrupt for high-impact fixes.
- Never repeat dismissed hints quickly.
- Never suggest a mode switch when the current mode can solve it invisibly.
- Never display low-confidence scene labels as fact.
- Prefer action verbs over labels.

Bad:

- "Landscape detected - try Landscape mode."

Better:

- "Include foreground for depth."

Best:

- Automatically uses landscape processing, stays silent unless composition can improve.

### AI Should Know When The User Is Intentional

If the user:

- Locks AE/AF
- Switches to Pro
- Repeatedly dismisses horizon
- Chooses Film/Cine
- Holds a centered composition

Then the app should reduce intervention.

---

## Camera Pipeline Improvements

### Immediate Pipeline Fixes

- Fix HDR capture path.
- Align preview/capture rendering.
- Apply skin-tone AWB shifts.
- Add tile-aligned burst merge.
- Improve bokeh edge handling.
- Add processing backend reporting.

### Medium-Term Pipeline

- ZSL ring buffer.
- Constant-exposure burst.
- Semantic masks for face/sky/foreground.
- Local tone mapping with subject protection.
- Scene-aware noise reduction.
- Multi-frame super-resolution for zoom.

### Long-Term Pipeline

- Vulkan/NDK zero-copy processing.
- RAW burst merge.
- Linear DNG output.
- NPU-aware segmentation/depth/denoise.
- Thermal-aware quality scaling.
- Multi-lens fusion.

---

## Professional Photography Improvements By Genre

### Portrait

Current: good intent, face detection, 3x recommendation, depth path.

Needs:

- Eye AF.
- Perspective warning.
- Background distance coaching.
- Soft light coaching.
- Skin-tone-aware exposure/WB.
- Hair-safe bokeh.

### Food

Current: mode and hints exist.

Needs:

- Plate highlight protection.
- Window light direction.
- 45-degree vs overhead detection.
- Shadow texture preservation.
- Avoid too-wide lens distortion.

### Landscape

Current: ultrawide suggestions and golden/blue hour hints.

Needs:

- Foreground interest detection.
- Sky highlight protection.
- Horizon/vertical correction.
- Telephoto compression suggestion.
- Avoid over-HDR flattening.

### Action

Current: burst suggestions and high shutter recommendations.

Needs:

- Predictive burst.
- Face/body tracking.
- Shutter feasibility warning in low light.
- Panning mode with intentional blur.

### Night/Street

Current: low-light detection and hold-still hints.

Needs:

- Mood-preserving tone map.
- Highlight protection for lamps/signs.
- Short burst denoise.
- Stabilization confidence.
- "wait for subject to stop" timing.

### Product

Current: no dedicated product intelligence.

Needs:

- Reflection warning.
- Background cleanliness.
- Edge alignment.
- Shadow softness.
- Color accuracy/white card option.

### Cinematic Video

Current: basic recording.

Needs:

- Exposure lock/ramps.
- Focus tracking and rack focus.
- Stabilization feedback.
- Motion cadence coaching.
- Safe shutter angle/frame-rate behavior.
- Audio level monitoring.
- Log/flat or low-sharpening look.

---

## What Makes This Feel Premium

- Real sensor metadata instead of fake confidence.
- Calm, high-confidence coaching.
- Preview matching capture.
- Skin tones that look human in ugly light.
- Highlights that roll off gently.
- Shadows that keep mood.
- Portraits that start with perspective, not blur.
- Automatic silence when the user is doing well.
- "Shoot now" moment timing.
- Pro tools that are accurate, not decorative.
- Fast capture with no obvious waiting.
- Smart review that explains just enough.

---

## What Still Feels Amateur

- Weak real HDR capture despite advanced HDR code existing.
- Too many visible controls for normal users.
- Some style processing still feels filter-like.
- Video intelligence is underdeveloped.
- Composition coaching is too rule-of-thirds-heavy.
- AI hints are useful but not yet deeply photographic.
- RAW/pro tools exist but workflow is not yet polished.
- Beauty and portrait processing risk software-looking results.
- Preview/capture consistency is not guaranteed.
- The app sometimes speaks in camera features instead of image outcomes.

---

## What Would Make Photographers Recommend It

Photographers would recommend SPECTRA if it became the camera they could hand to a non-photographer and trust.

That means:

- It preserves skin tone.
- It does not over-HDR everything.
- It avoids fake bokeh artifacts.
- It knows when to use 3x for portraits.
- It protects highlights.
- It does not lie about settings.
- It makes quiet, correct decisions.
- It gives non-photographers the same advice a photographer would give in the moment.

The app should feel less like "AI camera assistant" and more like "a photographer is gently standing next to me."

---

## Features Users Never Ask For But Secretly Love

- "Shoot now" cue.
- Automatic best-frame selection.
- Quiet eye-open detection.
- Face-friendly exposure in backlight.
- Background clutter warning.
- Wide-angle face distortion warning.
- Window-light direction cue.
- Auto horizon correction preview.
- Lamp/highlight protection at night.
- "Keep night looking like night" mode.
- Smart crop suggestions after capture.
- Gentle haptic when focus/exposure are ideal.
- Before/after AI explanation only after capture.

---

## Next-Generation Concepts

### Invisible Director

The app watches composition, light, expression, and stability, then gives one cinematic direction only when it matters.

### Emotional Moment Detection

Detect:

- Smile peak
- Eye contact
- Gesture completion
- Subject stillness
- Group readiness
- Pet stillness/attention

Then cue capture or auto-pick the best frame.

### Semantic Exposure

Expose differently for:

- Face
- Sky
- Window
- Lamp
- Product
- Food
- Background

Then blend naturally, not flatly.

### Cinematic Continuity

For video:

- Maintain consistent exposure across a pan.
- Avoid sudden WB shifts.
- Smooth focus transitions.
- Detect bad walking shake.
- Coach start/hold/end timing.

### Personal Taste Learning

Learn whether the user likes:

- Warm or cool portraits
- Natural or vivid color
- Shallow or deep bokeh
- Bright or moody shadows
- Centered or rule-of-thirds framing

Then guide less over time.

---

## Final Score

**5.8 / 10 today.**

Breakdown:

- **Foundation:** strong.
- **Ambition:** excellent.
- **Photography intelligence:** promising but not mature.
- **Computational photography:** partial.
- **Beginner usefulness:** good but too busy.
- **Professional trust:** not there yet.
- **Video/cinematic:** early.

With the top pipeline fixes and a calmer AI UX, SPECTRA could realistically move to **7.5 / 10**.

To reach **9 / 10**, it needs:

- Pixel-class multi-frame capture.
- iPhone-class preview/capture trust and video stability.
- Fujifilm-class color restraint.
- Halide-class pro honesty.
- A coaching system that gives fewer, deeper, more photographic interventions.

The core idea is worth pursuing. The differentiator is not "AI settings." It is helping ordinary people make better photographic decisions without making them feel like they are operating a camera.
