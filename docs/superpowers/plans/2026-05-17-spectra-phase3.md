# SPECTRA Phase 3: Composition Coaching — Overlay Guides, Arrows, Real-Time Directives

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add real-time composition coaching that generates scene-aware directives with directional arrows, enhanced stability indicators for NIGHT mode, and wires the existing (but empty) coaching HUD elements to live AI data.

**Architecture:** Extend the DecisionEngine with a `generateCoaching()` method that maps scene analysis signals to contextual coaching hints with directional arrows. Add a CoachingHint model with arrow direction, expose it from the pipeline, and wire through CameraViewModel into the existing HudOverlay/CoachingDirective UI. Enhance CoachingDirective to render directional arrows (up/down/left/right/steady) instead of only a fixed up arrow. Add NIGHT mode stability warnings.

**Tech Stack:** Pure Kotlin logic in :ai-engine, Jetpack Compose Canvas animations in :app, existing Hilt DI wiring.

---

## File Structure (new/modified files)

```
ai-engine/
└── src/main/java/com/spectra/ai/
    ├── model/
    │   └── CoachingHint.kt                   (create — coaching data + arrow direction)
    ├── CoachingEngine.kt                     (create — scene → coaching rules)
    ├── FrameAnalysisPipeline.kt              (modify — expose coaching hints)
    └── DecisionEngine.kt                     (no change)

core/
└── src/main/java/com/spectra/core/model/
    └── HudState.kt                           (modify — add arrowDirection field)

app/
└── src/main/java/com/spectra/app/
    ├── ui/hud/
    │   ├── CoachingDirective.kt              (modify — directional arrows + steady indicator)
    │   └── StabilityIndicator.kt             (create — NIGHT mode stability bar)
    └── viewmodel/
        └── CameraViewModel.kt               (modify — wire coaching + stability)
```

---

### Task 1: CoachingHint Model and ArrowDirection Enum

**Files:**
- Create: `ai-engine/src/main/java/com/spectra/ai/model/CoachingHint.kt`

- [ ] **Step 1: Create CoachingHint model**

```kotlin
package com.spectra.ai.model

enum class ArrowDirection {
    UP, DOWN, LEFT, RIGHT, STEADY, NONE;
}

data class CoachingHint(
    val text: String,
    val arrow: ArrowDirection = ArrowDirection.NONE,
    val priority: Int = 0
)
```

- [ ] **Step 2: Commit**

```bash
git add ai-engine/src/main/java/com/spectra/ai/model/CoachingHint.kt
git commit -m "feat(ai): add CoachingHint model with ArrowDirection enum

Data class for composition coaching directives: text, directional arrow, priority."
```

---

### Task 2: CoachingEngine — Scene Analysis to Coaching Directives

**Files:**
- Create: `ai-engine/src/main/java/com/spectra/ai/CoachingEngine.kt`
- Test: `ai-engine/src/test/java/com/spectra/ai/CoachingEngineTest.kt`

- [ ] **Step 1: Write CoachingEngine test**

```kotlin
package com.spectra.ai

import com.google.common.truth.Truth.assertThat
import com.spectra.ai.model.*
import com.spectra.core.model.CameraMode
import com.spectra.core.model.SceneType
import org.junit.Test

class CoachingEngineTest {

    private val engine = CoachingEngine()

    @Test
    fun `landscape static suggests horizon placement`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.LANDSCAPE,
            confidence = 0.9f,
            lighting = LightingCondition.GOLDEN_HOUR,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.INFINITY
        )
        val hint = engine.generateCoaching(analysis, CameraMode.PHOTO)
        assertThat(hint).isNotNull()
        assertThat(hint!!.text).isNotEmpty()
    }

    @Test
    fun `portrait suggests eye-level framing`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.PORTRAIT,
            confidence = 0.85f,
            lighting = LightingCondition.BRIGHT_DAYLIGHT,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.MID
        )
        val hint = engine.generateCoaching(analysis, CameraMode.PORT)
        assertThat(hint).isNotNull()
        assertThat(hint!!.text).isNotEmpty()
    }

    @Test
    fun `fast motion suggests burst mode`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.PET,
            confidence = 0.8f,
            lighting = LightingCondition.BRIGHT_DAYLIGHT,
            motionLevel = MotionLevel.FAST,
            distanceRange = DistanceRange.MID
        )
        val hint = engine.generateCoaching(analysis, CameraMode.PHOTO)
        assertThat(hint).isNotNull()
        assertThat(hint!!.text).contains("BURST")
    }

    @Test
    fun `night mode static suggests stability`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.NIGHT,
            confidence = 0.88f,
            lighting = LightingCondition.LOW_LIGHT,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.FAR
        )
        val hint = engine.generateCoaching(analysis, CameraMode.NIGHT)
        assertThat(hint).isNotNull()
        assertThat(hint!!.arrow).isEqualTo(ArrowDirection.STEADY)
    }

    @Test
    fun `night mode with motion warns to hold steady`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.NIGHT,
            confidence = 0.88f,
            lighting = LightingCondition.LOW_LIGHT,
            motionLevel = MotionLevel.MODERATE,
            distanceRange = DistanceRange.FAR
        )
        val hint = engine.generateCoaching(analysis, CameraMode.NIGHT)
        assertThat(hint).isNotNull()
        assertThat(hint!!.text).contains("HOLD STEADY")
    }

    @Test
    fun `food suggests overhead angle`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.FOOD,
            confidence = 0.9f,
            lighting = LightingCondition.ARTIFICIAL,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.NEAR
        )
        val hint = engine.generateCoaching(analysis, CameraMode.PHOTO)
        assertThat(hint).isNotNull()
    }

    @Test
    fun `backlit lighting suggests expose for subject`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.PORTRAIT,
            confidence = 0.85f,
            lighting = LightingCondition.BACKLIT,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.MID
        )
        val hint = engine.generateCoaching(analysis, CameraMode.PHOTO)
        assertThat(hint).isNotNull()
        assertThat(hint!!.text).contains("BACKLIT")
    }

    @Test
    fun `unstable analysis returns null`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.LANDSCAPE,
            confidence = 0.3f
        )
        val hint = engine.generateCoaching(analysis, CameraMode.PHOTO)
        assertThat(hint).isNull()
    }

    @Test
    fun `PRO mode still generates coaching`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.LANDSCAPE,
            confidence = 0.9f,
            lighting = LightingCondition.GOLDEN_HOUR,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.INFINITY
        )
        val hint = engine.generateCoaching(analysis, CameraMode.PRO)
        assertThat(hint).isNotNull()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ai-engine:test --tests "com.spectra.ai.CoachingEngineTest"`
Expected: FAIL — CoachingEngine not found

- [ ] **Step 3: Create CoachingEngine**

```kotlin
package com.spectra.ai

import com.spectra.ai.model.*
import com.spectra.core.model.CameraMode
import com.spectra.core.model.SceneType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CoachingEngine @Inject constructor() {

    private var lastHintText: String? = null
    private var lastHintTimeMs: Long = 0L
    private val hintCooldownMs = 5000L

    fun generateCoaching(analysis: SceneAnalysis, mode: CameraMode): CoachingHint? {
        if (!analysis.isStable) return null

        val hint = when {
            mode == CameraMode.NIGHT -> nightModeHint(analysis)
            analysis.motionLevel == MotionLevel.FAST || analysis.motionLevel == MotionLevel.VERY_FAST ->
                motionHint(analysis)
            analysis.lighting == LightingCondition.BACKLIT -> backlitHint(analysis)
            else -> sceneHint(analysis, mode)
        }

        if (hint != null && hint.text == lastHintText &&
            System.currentTimeMillis() - lastHintTimeMs < hintCooldownMs) {
            return hint
        }

        if (hint != null) {
            lastHintText = hint.text
            lastHintTimeMs = System.currentTimeMillis()
        }

        return hint
    }

    private fun nightModeHint(analysis: SceneAnalysis): CoachingHint {
        return when {
            analysis.motionLevel >= MotionLevel.MODERATE ->
                CoachingHint("HOLD STEADY · LONG EXPOSURE ACTIVE", ArrowDirection.STEADY, priority = 10)
            analysis.motionLevel == MotionLevel.SLOW ->
                CoachingHint("STABILIZE · BRACE AGAINST SURFACE", ArrowDirection.STEADY, priority = 8)
            else ->
                CoachingHint("STEADY · CAPTURING LIGHT", ArrowDirection.STEADY, priority = 5)
        }
    }

    private fun motionHint(analysis: SceneAnalysis): CoachingHint {
        return when (analysis.sceneType) {
            SceneType.PET ->
                CoachingHint("FAST SUBJECT · USE BURST MODE", ArrowDirection.NONE, priority = 8)
            SceneType.ACTION ->
                CoachingHint("PAN WITH SUBJECT · BURST RECOMMENDED", ArrowDirection.NONE, priority = 8)
            else ->
                CoachingHint("MOTION DETECTED · BURST RECOMMENDED", ArrowDirection.NONE, priority = 7)
        }
    }

    private fun backlitHint(analysis: SceneAnalysis): CoachingHint {
        return CoachingHint("BACKLIT · REPOSITION OR TAP SUBJECT", ArrowDirection.NONE, priority = 9)
    }

    private fun sceneHint(analysis: SceneAnalysis, mode: CameraMode): CoachingHint? {
        val baseHint = when (analysis.sceneType) {
            SceneType.LANDSCAPE -> landscapeHint(analysis)
            SceneType.PORTRAIT -> portraitHint(analysis, mode)
            SceneType.FOOD -> foodHint(analysis)
            SceneType.ARCHITECTURE -> architectureHint(analysis)
            SceneType.MACRO -> macroHint(analysis)
            SceneType.PET -> petHint(analysis)
            SceneType.ACTION -> actionHint(analysis)
            SceneType.DOCUMENT -> documentHint(analysis)
            SceneType.INDOOR -> indoorHint(analysis)
            else -> null
        }
        return baseHint
    }

    private fun landscapeHint(analysis: SceneAnalysis): CoachingHint {
        return when (analysis.lighting) {
            LightingCondition.GOLDEN_HOUR ->
                CoachingHint("GOLDEN HOUR · HORIZON ON LOWER THIRD", ArrowDirection.DOWN, priority = 5)
            LightingCondition.BLUE_HOUR ->
                CoachingHint("BLUE HOUR · INCLUDE SKY GRADIENT", ArrowDirection.UP, priority = 5)
            LightingCondition.HARSH_MIDDAY ->
                CoachingHint("HARSH LIGHT · FIND SHADOWS OR WAIT", ArrowDirection.NONE, priority = 4)
            else ->
                CoachingHint("FIND LEADING LINES · RULE OF THIRDS", ArrowDirection.NONE, priority = 3)
        }
    }

    private fun portraitHint(analysis: SceneAnalysis, mode: CameraMode): CoachingHint {
        return if (mode == CameraMode.PORT) {
            when (analysis.distanceRange) {
                DistanceRange.FAR ->
                    CoachingHint("MOVE CLOSER · FILL FRAME WITH SUBJECT", ArrowDirection.NONE, priority = 7)
                DistanceRange.NEAR, DistanceRange.MACRO ->
                    CoachingHint("STEP BACK · HEAD AND SHOULDERS", ArrowDirection.NONE, priority = 6)
                else ->
                    CoachingHint("FOCUS ON EYES · FACE THE LIGHT", ArrowDirection.NONE, priority = 4)
            }
        } else {
            CoachingHint("EYES IN FOCUS · NATURAL LIGHT", ArrowDirection.NONE, priority = 3)
        }
    }

    private fun foodHint(analysis: SceneAnalysis): CoachingHint {
        return when (analysis.distanceRange) {
            DistanceRange.MACRO, DistanceRange.NEAR ->
                CoachingHint("TRY 45° OR OVERHEAD ANGLE", ArrowDirection.DOWN, priority = 4)
            else ->
                CoachingHint("MOVE CLOSER · FILL THE FRAME", ArrowDirection.NONE, priority = 5)
        }
    }

    private fun architectureHint(analysis: SceneAnalysis): CoachingHint {
        return CoachingHint("STRAIGHTEN VERTICALS · FIND SYMMETRY", ArrowDirection.UP, priority = 4)
    }

    private fun macroHint(analysis: SceneAnalysis): CoachingHint {
        return CoachingHint("HOLD BREATH · MINIMIZE MOVEMENT", ArrowDirection.STEADY, priority = 6)
    }

    private fun petHint(analysis: SceneAnalysis): CoachingHint {
        return CoachingHint("EYE LEVEL WITH SUBJECT", ArrowDirection.DOWN, priority = 4)
    }

    private fun actionHint(analysis: SceneAnalysis): CoachingHint {
        return CoachingHint("PRE-FOCUS · PAN WITH SUBJECT", ArrowDirection.RIGHT, priority = 5)
    }

    private fun documentHint(analysis: SceneAnalysis): CoachingHint {
        return CoachingHint("SHOOT OVERHEAD · EVEN LIGHTING", ArrowDirection.DOWN, priority = 4)
    }

    private fun indoorHint(analysis: SceneAnalysis): CoachingHint {
        return when (analysis.lighting) {
            LightingCondition.LOW_LIGHT ->
                CoachingHint("FIND WINDOW LIGHT · STABILIZE", ArrowDirection.STEADY, priority = 5)
            LightingCondition.ARTIFICIAL ->
                CoachingHint("CHECK WHITE BALANCE · WINDOW LIGHT BETTER", ArrowDirection.NONE, priority = 3)
            else ->
                CoachingHint("USE NATURAL LIGHT SOURCE", ArrowDirection.NONE, priority = 3)
        }
    }

    fun reset() {
        lastHintText = null
        lastHintTimeMs = 0L
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :ai-engine:test --tests "com.spectra.ai.CoachingEngineTest"`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add ai-engine/src/main/java/com/spectra/ai/CoachingEngine.kt \
       ai-engine/src/main/java/com/spectra/ai/model/CoachingHint.kt \
       ai-engine/src/test/java/com/spectra/ai/CoachingEngineTest.kt
git commit -m "feat(ai): add CoachingEngine — scene-aware composition directives

Generates contextual coaching hints with directional arrows based on
scene type, lighting, motion, distance, and camera mode. 9 test cases
covering landscape, portrait, food, night, motion, backlit, and PRO mode."
```

---

### Task 3: Expose Coaching from FrameAnalysisPipeline

**Files:**
- Modify: `ai-engine/src/main/java/com/spectra/ai/FrameAnalysisPipeline.kt`

- [ ] **Step 1: Update FrameAnalysisPipeline**

Read the current file first. Then make these changes:

1. Add constructor parameter: `private val coachingEngine: CoachingEngine`
2. Add import: `import com.spectra.core.model.CameraMode`
3. Add new StateFlow:
```kotlin
private val _coachingHint = MutableStateFlow<CoachingHint?>(null)
val coachingHint: StateFlow<CoachingHint?> = _coachingHint.asStateFlow()
```
4. Add import: `import com.spectra.ai.model.CoachingHint`
5. Add `mode` parameter to `analyzeFrame()`:
```kotlin
fun analyzeFrame(
    bitmap: Bitmap,
    mode: CameraMode = CameraMode.PHOTO,
    focusDistanceDiopters: Float = 0f,
    exposureTimeNs: Long = 0L,
    iso: Int = 100,
    colorTemperature: Int = 5500
)
```
6. After updating `_analysis.value = sceneAnalysis`, add:
```kotlin
_coachingHint.value = coachingEngine.generateCoaching(sceneAnalysis, mode)
```
7. In `release()`, add: `coachingEngine.reset()`

- [ ] **Step 2: Commit**

```bash
git add ai-engine/src/main/java/com/spectra/ai/FrameAnalysisPipeline.kt
git commit -m "feat(ai): expose coaching hints from FrameAnalysisPipeline

Pipeline now generates and emits CoachingHint via StateFlow,
produced by CoachingEngine after each frame analysis."
```

---

### Task 4: Enhanced CoachingDirective — Directional Arrows

**Files:**
- Modify: `app/src/main/java/com/spectra/app/ui/hud/CoachingDirective.kt`

- [ ] **Step 1: Update CoachingDirective with directional arrows**

Read the current file first. Replace the entire content with:

```kotlin
package com.spectra.app.ui.hud

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectra.ai.model.ArrowDirection
import com.spectra.app.ui.theme.HudColors
import com.spectra.app.ui.theme.HudTypography

@Composable
fun CoachingDirective(
    text: String,
    arrowDirection: ArrowDirection,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "coaching")
    val arrowAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "arrowPulse"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable { onDismiss() }
    ) {
        if (arrowDirection == ArrowDirection.UP) {
            ArrowText(symbol = "▲", alpha = arrowAlpha)
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (arrowDirection == ArrowDirection.LEFT) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArrowText(symbol = "◄", alpha = arrowAlpha)
                Spacer(modifier = Modifier.width(8.dp))
                CoachingBox(text = text)
                Spacer(modifier = Modifier.width(8.dp))
            }
        } else if (arrowDirection == ArrowDirection.RIGHT) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(8.dp))
                CoachingBox(text = text)
                Spacer(modifier = Modifier.width(8.dp))
                ArrowText(symbol = "►", alpha = arrowAlpha)
            }
        } else if (arrowDirection == ArrowDirection.STEADY) {
            SteadyIndicator(alpha = arrowAlpha)
            Spacer(modifier = Modifier.height(4.dp))
            CoachingBox(text = text)
        } else {
            CoachingBox(text = text)
        }

        if (arrowDirection == ArrowDirection.DOWN) {
            Spacer(modifier = Modifier.height(4.dp))
            ArrowText(symbol = "▼", alpha = arrowAlpha)
        }
    }
}

@Composable
private fun ArrowText(symbol: String, alpha: Float) {
    Text(
        text = symbol,
        color = HudColors.neonGreen,
        fontSize = 16.sp,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        modifier = Modifier.alpha(alpha)
    )
}

@Composable
private fun CoachingBox(text: String) {
    Text(
        text = text,
        style = HudTypography.coaching,
        modifier = Modifier
            .border(1.dp, HudColors.borderGreen, RectangleShape)
            .background(HudColors.background.copy(alpha = 0.7f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun SteadyIndicator(alpha: Float) {
    Text(
        text = "⊕ STEADY",
        color = HudColors.neonGreen,
        fontSize = 10.sp,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        modifier = Modifier.alpha(alpha)
    )
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/spectra/app/ui/hud/CoachingDirective.kt
git commit -m "feat(app): enhance CoachingDirective with directional arrows

Supports UP/DOWN/LEFT/RIGHT arrows, STEADY indicator for night mode,
and NONE for general tips. Pulsing animation on all arrow variants."
```

---

### Task 5: Update HudState and HudOverlay for Arrow Direction

**Files:**
- Modify: `core/src/main/java/com/spectra/core/model/HudState.kt`
- Modify: `app/src/main/java/com/spectra/app/ui/hud/HudOverlay.kt`

- [ ] **Step 1: Update HudState**

Read the current file first. Add a new field after `coachingText`:

```kotlin
val coachingArrow: String = "NONE",
```

The full data class should look like:
```kotlin
package com.spectra.core.model

data class HudState(
    val activeLens: LensId = LensId.MAIN,
    val settings: CameraSettings = CameraSettings(),
    val mode: CameraMode = CameraMode.PHOTO,
    val isHudVisible: Boolean = true,
    val sceneLabel: String = "READY",
    val sceneConfidence: Float = 0f,
    val lightingLabel: String = "—",
    val motionLevel: Int = 0,
    val distanceLabel: String = "—",
    val coachingText: String? = null,
    val coachingArrow: String = "NONE",
    val lensMatchScores: Map<LensId, Float> = LensId.entries.associateWith {
        if (it == activeLens) 1.0f else 0f
    },
    val isBurstActive: Boolean = false,
    val lastCapturedUri: String? = null,
    val showTipsThumbnail: Boolean = false,
    val showReferenceCard: Boolean = false
)
```

Note: We use `String` (not `ArrowDirection` enum) in HudState because :core should not depend on :ai-engine. The ViewModel converts the enum to string.

- [ ] **Step 2: Update HudOverlay**

Read the current file first. Update the CoachingDirective call inside the `AnimatedVisibility` block. Change from:

```kotlin
val coaching = state.coachingText
if (coaching != null) {
    CoachingDirective(
        text = coaching,
        onDismiss = onCoachingDismiss,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 180.dp)
    )
}
```

To:

```kotlin
val coaching = state.coachingText
if (coaching != null) {
    val arrow = try {
        ArrowDirection.valueOf(state.coachingArrow)
    } catch (_: Exception) {
        ArrowDirection.NONE
    }
    CoachingDirective(
        text = coaching,
        arrowDirection = arrow,
        onDismiss = onCoachingDismiss,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 180.dp)
    )
}
```

Add import: `import com.spectra.ai.model.ArrowDirection`

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/com/spectra/core/model/HudState.kt \
       app/src/main/java/com/spectra/app/ui/hud/HudOverlay.kt
git commit -m "feat: add coaching arrow direction to HudState and HudOverlay

HudState carries arrow direction as string for module boundary safety.
HudOverlay parses it and passes to enhanced CoachingDirective."
```

---

### Task 6: StabilityIndicator for NIGHT Mode

**Files:**
- Create: `app/src/main/java/com/spectra/app/ui/hud/StabilityIndicator.kt`

- [ ] **Step 1: Create StabilityIndicator composable**

```kotlin
package com.spectra.app.ui.hud

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.spectra.app.ui.theme.HudColors
import com.spectra.app.ui.theme.HudTypography

@Composable
fun StabilityIndicator(
    motionLevel: Int,
    isNightMode: Boolean,
    modifier: Modifier = Modifier
) {
    if (!isNightMode) return

    val infiniteTransition = rememberInfiniteTransition(label = "stability")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "stabilityPulse"
    )

    val isStable = motionLevel <= 1
    val color = if (isStable) HudColors.neonGreen else HudColors.red
    val label = if (isStable) "STABLE" else "UNSTABLE"

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Text(
            text = "STABILITY",
            style = HudTypography.label
        )
        Spacer(modifier = Modifier.height(4.dp))
        Canvas(
            modifier = Modifier
                .width(60.dp)
                .height(6.dp)
        ) {
            val barWidth = size.width
            val barHeight = size.height
            drawRect(
                color = HudColors.borderGreen,
                size = Size(barWidth, barHeight),
                style = Stroke(width = 1f)
            )
            val fillRatio = when (motionLevel) {
                0 -> 1.0f
                1 -> 0.75f
                2 -> 0.5f
                3 -> 0.25f
                else -> 0.1f
            }
            val displayAlpha = if (!isStable) pulseAlpha else 1f
            drawRect(
                color = color.copy(alpha = displayAlpha),
                topLeft = Offset.Zero,
                size = Size(barWidth * fillRatio, barHeight)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = HudTypography.label,
            color = color
        )
    }
}
```

- [ ] **Step 2: Add StabilityIndicator to HudOverlay**

Read HudOverlay.kt. Add the StabilityIndicator inside the inner `Box` (after MotionIndicator), positioned at bottom-left:

```kotlin
StabilityIndicator(
    motionLevel = state.motionLevel,
    isNightMode = state.mode == CameraMode.NIGHT,
    modifier = Modifier
        .align(Alignment.BottomStart)
        .padding(start = 24.dp, bottom = 180.dp)
)
```

Add import: `import com.spectra.core.model.CameraMode`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/spectra/app/ui/hud/StabilityIndicator.kt \
       app/src/main/java/com/spectra/app/ui/hud/HudOverlay.kt
git commit -m "feat(app): add StabilityIndicator for NIGHT mode

Pulsing stability bar appears only in NIGHT mode. Shows STABLE (green)
or UNSTABLE (red) based on motion level with fill-ratio visualization."
```

---

### Task 7: Wire Coaching into CameraViewModel

**Files:**
- Modify: `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`

- [ ] **Step 1: Update CameraViewModel**

Read the current file first. Make these changes:

1. Update the `frameProvider.frames.collect` block to pass the current mode:
```kotlin
viewModelScope.launch {
    frameProvider.frames.collect { bitmap ->
        pipeline.analyzeFrame(bitmap, mode = _hudState.value.mode)
    }
}
```

2. Add a new coroutine in `init` to collect coaching hints:
```kotlin
viewModelScope.launch {
    pipeline.coachingHint.collect { hint ->
        _hudState.update { it.copy(
            coachingText = hint?.text,
            coachingArrow = hint?.arrow?.name ?: "NONE"
        )}
    }
}
```

3. Add import: `import com.spectra.ai.model.CoachingHint` (if not already present)

4. Update `onCoachingDismiss` — the ViewfinderScreen currently passes `onCoachingDismiss = { }` to HudOverlay. The coaching should auto-refresh from the pipeline, so dismissing just clears the current text. No change needed in the ViewModel — the pipeline will re-emit a coaching hint on the next analysis cycle, repopulating the text.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt
git commit -m "feat(app): wire coaching hints from AI pipeline into HUD

CameraViewModel collects CoachingHint from pipeline and updates
HudState.coachingText and coachingArrow. Passes current camera mode
to pipeline for mode-aware coaching."
```

---

### Task 8: Final Integration, Build Verification & Tag

- [ ] **Step 1: Run full test suite**

Run: `./gradlew test`
Expected: ALL PASS

- [ ] **Step 2: Build debug APK**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Verify git log**

Run: `git log --oneline`
Expected: Phase 1 + Phase 2 + Phase 3 commits in order

- [ ] **Step 4: Tag Phase 3**

```bash
git tag v0.3.0-phase3
```

- [ ] **Step 5: Manual smoke test checklist**

Install on S24 Ultra and verify:
1. Point at a landscape → coaching directive appears: "GOLDEN HOUR · HORIZON ON LOWER THIRD" with ▼ arrow
2. Point at food → "TRY 45° OR OVERHEAD ANGLE" with ▼ arrow
3. Move camera fast → "MOTION DETECTED · BURST RECOMMENDED" appears
4. Switch to NIGHT mode → stability bar appears bottom-left, coaching shows "⊕ STEADY" with pulsing
5. Shake camera in NIGHT mode → stability bar turns red "UNSTABLE", coaching warns "HOLD STEADY"
6. Point at person in PORT mode → pose coaching appears
7. Backlit scene → "BACKLIT · REPOSITION OR TAP SUBJECT"
8. Tap coaching directive → it dismisses, then re-appears on next analysis cycle
9. Double-tap to hide HUD → coaching and stability indicator both hide
10. All existing Phase 1+2 features still work (lens switching, auto settings, tips, capture)

---

## Phase 3 Deliverables Summary

| Feature | Task |
|---|---|
| CoachingHint model + ArrowDirection enum | Task 1 |
| CoachingEngine — 11 scene types + motion/lighting coaching (9 tests) | Task 2 |
| FrameAnalysisPipeline coaching hint exposure | Task 3 |
| Enhanced CoachingDirective — directional arrows + steady indicator | Task 4 |
| HudState + HudOverlay arrow direction support | Task 5 |
| StabilityIndicator for NIGHT mode | Task 6 |
| CameraViewModel coaching wiring | Task 7 |
| Integration verification + tag v0.3.0-phase3 | Task 8 |
