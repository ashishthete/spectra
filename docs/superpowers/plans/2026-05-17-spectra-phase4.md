# SPECTRA Phase 4: Cloud AI + PRO Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Claude Vision cloud coaching (advanced pose/angle guidance when user pauses 2+ seconds) and PRO mode with manual control sliders showing AI ghost markers.

**Architecture:** Cloud coaching lives in a new `ai-engine/cloud/` package — `CloudCoachingClient` calls the Claude Vision API, `FrameSimilarityCache` prevents redundant calls (70% pixel threshold, 10s expiry), and `CloudCoachingManager` orchestrates pause detection. PRO mode adds `aiRecommendedSettings` to HudState and a `ProModePanel` composable with ghost-marker sliders. All wired through existing ViewModel/StateFlow patterns.

**Tech Stack:** Ktor Client (CIO engine) for HTTP, kotlinx.serialization for JSON, Jetpack Compose for PRO mode sliders.

---

### Task 1: Add Networking Dependencies

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `ai-engine/build.gradle.kts`

- [ ] **Step 1: Add Ktor and serialization versions to version catalog**

In `gradle/libs.versions.toml`, add these entries:

```toml
# In [versions] section, add:
ktor = "2.3.12"
serialization = "1.7.3"

# In [libraries] section, add:
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-cio = { module = "io.ktor:ktor-client-cio", version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }

# In [plugins] section, add:
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 2: Add dependencies to ai-engine module**

In `ai-engine/build.gradle.kts`, add the serialization plugin and dependencies:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}
```

Add to the `dependencies` block:

```kotlin
implementation(libs.ktor.client.core)
implementation(libs.ktor.client.cio)
implementation(libs.ktor.client.content.negotiation)
implementation(libs.ktor.serialization.json)
implementation(libs.serialization.json)
```

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew :ai-engine:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml ai-engine/build.gradle.kts
git commit -m "build: add Ktor and kotlinx.serialization deps for cloud AI"
```

---

### Task 2: Cloud Coaching Data Models

**Files:**
- Create: `ai-engine/src/main/java/com/spectra/ai/cloud/CloudCoachingModels.kt`
- Test: `ai-engine/src/test/java/com/spectra/ai/cloud/CloudCoachingModelsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.spectra.ai.cloud

import com.google.common.truth.Truth.assertThat
import com.spectra.ai.model.ArrowDirection
import org.junit.Test

class CloudCoachingModelsTest {

    @Test
    fun parseDirective_validLine_parsesTextAndArrow() {
        val line = "TILT UP 10° · GOLDEN RATIO ALIGN"
        val directive = CloudDirectiveParser.parse(line)
        assertThat(directive.text).isEqualTo("TILT UP 10° · GOLDEN RATIO ALIGN")
        assertThat(directive.arrow).isEqualTo(ArrowDirection.UP)
    }

    @Test
    fun parseDirective_downKeyword_returnsDown() {
        val line = "LOWER ANGLE · DRAMATIC FOREGROUND"
        val directive = CloudDirectiveParser.parse(line)
        assertThat(directive.arrow).isEqualTo(ArrowDirection.DOWN)
    }

    @Test
    fun parseDirective_leftKeyword_returnsLeft() {
        val line = "STEP LEFT 0.5M · LEADING LINES"
        val directive = CloudDirectiveParser.parse(line)
        assertThat(directive.arrow).isEqualTo(ArrowDirection.LEFT)
    }

    @Test
    fun parseDirective_rightKeyword_returnsRight() {
        val line = "MOVE RIGHT · BALANCE COMPOSITION"
        val directive = CloudDirectiveParser.parse(line)
        assertThat(directive.arrow).isEqualTo(ArrowDirection.RIGHT)
    }

    @Test
    fun parseDirective_steadyKeyword_returnsSteady() {
        val line = "HOLD STEADY · PERFECT FRAMING"
        val directive = CloudDirectiveParser.parse(line)
        assertThat(directive.arrow).isEqualTo(ArrowDirection.STEADY)
    }

    @Test
    fun parseDirective_noDirectionKeyword_returnsNone() {
        val line = "NICE COMPOSITION · SHOOT NOW"
        val directive = CloudDirectiveParser.parse(line)
        assertThat(directive.arrow).isEqualTo(ArrowDirection.NONE)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ai-engine:testDebugUnitTest --tests "com.spectra.ai.cloud.CloudCoachingModelsTest"`
Expected: FAIL — `CloudDirectiveParser` not found

- [ ] **Step 3: Write the implementation**

```kotlin
package com.spectra.ai.cloud

import com.spectra.ai.model.ArrowDirection
import com.spectra.ai.model.CoachingHint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VisionRequest(
    val model: String = "claude-sonnet-4-20250514",
    @SerialName("max_tokens") val maxTokens: Int = 256,
    val messages: List<VisionMessage>
)

@Serializable
data class VisionMessage(
    val role: String = "user",
    val content: List<VisionContent>
)

@Serializable
data class VisionContent(
    val type: String,
    val source: ImageSource? = null,
    val text: String? = null
)

@Serializable
data class ImageSource(
    val type: String = "base64",
    @SerialName("media_type") val mediaType: String = "image/jpeg",
    val data: String
)

@Serializable
data class VisionResponse(
    val content: List<ResponseContent> = emptyList()
)

@Serializable
data class ResponseContent(
    val type: String,
    val text: String? = null
)

object CloudDirectiveParser {

    private val directionKeywords = mapOf(
        ArrowDirection.UP to listOf("tilt up", "raise", "look up", "higher", "upward"),
        ArrowDirection.DOWN to listOf("tilt down", "lower", "look down", "overhead", "downward"),
        ArrowDirection.LEFT to listOf("step left", "move left", "shift left", "pan left"),
        ArrowDirection.RIGHT to listOf("step right", "move right", "shift right", "pan right"),
        ArrowDirection.STEADY to listOf("hold steady", "stay still", "don't move", "stabilize")
    )

    fun parse(text: String): CoachingHint {
        val lower = text.lowercase()
        val arrow = directionKeywords.entries
            .firstOrNull { (_, keywords) -> keywords.any { lower.contains(it) } }
            ?.key ?: ArrowDirection.NONE
        return CoachingHint(text = text, arrow = arrow, priority = 15)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :ai-engine:testDebugUnitTest --tests "com.spectra.ai.cloud.CloudCoachingModelsTest"`
Expected: PASS — all 6 tests green

- [ ] **Step 5: Commit**

```bash
git add ai-engine/src/main/java/com/spectra/ai/cloud/CloudCoachingModels.kt \
        ai-engine/src/test/java/com/spectra/ai/cloud/CloudCoachingModelsTest.kt
git commit -m "feat: add cloud coaching data models and directive parser"
```

---

### Task 3: Frame Similarity Cache

**Files:**
- Create: `ai-engine/src/main/java/com/spectra/ai/cloud/FrameSimilarityCache.kt`
- Test: `ai-engine/src/test/java/com/spectra/ai/cloud/FrameSimilarityCacheTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.spectra.ai.cloud

import com.google.common.truth.Truth.assertThat
import com.spectra.ai.model.ArrowDirection
import com.spectra.ai.model.CoachingHint
import org.junit.Test

class FrameSimilarityCacheTest {

    private val cache = FrameSimilarityCache()

    private fun makePixels(brightness: Int, size: Int = 32 * 32): IntArray {
        val r = brightness
        val g = brightness
        val b = brightness
        val pixel = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        return IntArray(size) { pixel }
    }

    @Test
    fun getCached_emptyCache_returnsNull() {
        val pixels = makePixels(128)
        assertThat(cache.getCached(pixels)).isNull()
    }

    @Test
    fun getCached_identicalFrame_returnsCachedHint() {
        val pixels = makePixels(128)
        val hint = CoachingHint("TEST HINT", ArrowDirection.UP)
        cache.store(pixels, hint)
        assertThat(cache.getCached(pixels)).isEqualTo(hint)
    }

    @Test
    fun getCached_similarFrame_above70Percent_returnsCached() {
        val stored = makePixels(128)
        val hint = CoachingHint("TEST HINT")
        cache.store(stored, hint)

        // Change 25% of pixels (75% similar > 70% threshold)
        val query = stored.copyOf()
        val changeCount = (query.size * 0.25).toInt()
        for (i in 0 until changeCount) {
            query[i] = (0xFF shl 24) or (255 shl 16) or (255 shl 8) or 255
        }
        assertThat(cache.getCached(query)).isEqualTo(hint)
    }

    @Test
    fun getCached_dissimilarFrame_below70Percent_returnsNull() {
        val stored = makePixels(0)
        val hint = CoachingHint("TEST HINT")
        cache.store(stored, hint)

        // Change 50% of pixels (50% similar < 70% threshold)
        val query = stored.copyOf()
        val changeCount = (query.size * 0.5).toInt()
        for (i in 0 until changeCount) {
            query[i] = (0xFF shl 24) or (255 shl 16) or (255 shl 8) or 255
        }
        assertThat(cache.getCached(query)).isNull()
    }

    @Test
    fun getCached_expiredEntry_returnsNull() {
        val cache = FrameSimilarityCache(expiryMs = 0L)
        val pixels = makePixels(128)
        val hint = CoachingHint("TEST HINT")
        cache.store(pixels, hint)
        // Expiry is 0ms, so immediately expired
        assertThat(cache.getCached(pixels)).isNull()
    }

    @Test
    fun clear_removesCachedEntry() {
        val pixels = makePixels(128)
        cache.store(pixels, CoachingHint("TEST"))
        cache.clear()
        assertThat(cache.getCached(pixels)).isNull()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ai-engine:testDebugUnitTest --tests "com.spectra.ai.cloud.FrameSimilarityCacheTest"`
Expected: FAIL — `FrameSimilarityCache` not found

- [ ] **Step 3: Write the implementation**

```kotlin
package com.spectra.ai.cloud

import com.spectra.ai.model.CoachingHint
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FrameSimilarityCache @Inject constructor() {

    private var cachedThumbnail: IntArray? = null
    private var cachedHint: CoachingHint? = null
    private var cachedAtMs: Long = 0L
    private val similarityThreshold = 0.70f
    val expiryMs: Long

    constructor(expiryMs: Long) : this() {
        this.expiryMsOverride = expiryMs
    }

    private var expiryMsOverride: Long? = null

    init {
        expiryMs = 10_000L
    }

    fun getCached(thumbnail: IntArray): CoachingHint? {
        val stored = cachedThumbnail ?: return null
        val hint = cachedHint ?: return null
        val effectiveExpiry = expiryMsOverride ?: expiryMs

        if (System.currentTimeMillis() - cachedAtMs > effectiveExpiry) {
            clear()
            return null
        }

        val similarity = computeSimilarity(stored, thumbnail)
        return if (similarity >= similarityThreshold) hint else null
    }

    fun store(thumbnail: IntArray, hint: CoachingHint) {
        cachedThumbnail = thumbnail.copyOf()
        cachedHint = hint
        cachedAtMs = System.currentTimeMillis()
    }

    fun clear() {
        cachedThumbnail = null
        cachedHint = null
        cachedAtMs = 0L
    }

    private fun computeSimilarity(a: IntArray, b: IntArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var matchCount = 0
        for (i in a.indices) {
            val ar = (a[i] shr 16) and 0xFF
            val ag = (a[i] shr 8) and 0xFF
            val ab = a[i] and 0xFF
            val br = (b[i] shr 16) and 0xFF
            val bg = (b[i] shr 8) and 0xFF
            val bb = b[i] and 0xFF
            val diff = kotlin.math.abs(ar - br) + kotlin.math.abs(ag - bg) + kotlin.math.abs(ab - bb)
            if (diff < 30) matchCount++
        }
        return matchCount.toFloat() / a.size
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :ai-engine:testDebugUnitTest --tests "com.spectra.ai.cloud.FrameSimilarityCacheTest"`
Expected: PASS — all 6 tests green

- [ ] **Step 5: Commit**

```bash
git add ai-engine/src/main/java/com/spectra/ai/cloud/FrameSimilarityCache.kt \
        ai-engine/src/test/java/com/spectra/ai/cloud/FrameSimilarityCacheTest.kt
git commit -m "feat: add frame similarity cache with 70% threshold and 10s expiry"
```

---

### Task 4: CloudCoachingClient

**Files:**
- Create: `ai-engine/src/main/java/com/spectra/ai/cloud/CloudCoachingClient.kt`
- Test: `ai-engine/src/test/java/com/spectra/ai/cloud/CloudCoachingClientTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.spectra.ai.cloud

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CloudCoachingClientTest {

    @Test
    fun buildPrompt_includesSceneType() {
        val prompt = CloudCoachingClient.buildPrompt("LANDSCAPE", "GOLDEN_HOUR")
        assertThat(prompt).contains("LANDSCAPE")
        assertThat(prompt).contains("GOLDEN_HOUR")
    }

    @Test
    fun buildPrompt_includesFormatInstructions() {
        val prompt = CloudCoachingClient.buildPrompt("PORTRAIT", "BRIGHT_DAYLIGHT")
        assertThat(prompt).contains("directive")
    }

    @Test
    fun buildRequest_createsValidStructure() {
        val base64 = "dGVzdA=="
        val request = CloudCoachingClient.buildRequest(base64, "FOOD", "ARTIFICIAL")
        assertThat(request.messages).hasSize(1)
        assertThat(request.messages[0].content).hasSize(2)
        assertThat(request.messages[0].content[0].type).isEqualTo("image")
        assertThat(request.messages[0].content[0].source?.data).isEqualTo(base64)
        assertThat(request.messages[0].content[1].type).isEqualTo("text")
    }

    @Test
    fun parseResponse_extractsFirstDirective() {
        val responseText = "TILT UP 15° · RULE OF THIRDS\nMOVE LEFT · LEADING LINES"
        val hints = CloudCoachingClient.parseResponseText(responseText)
        assertThat(hints).isNotEmpty()
        assertThat(hints[0].text).isEqualTo("TILT UP 15° · RULE OF THIRDS")
    }

    @Test
    fun parseResponse_emptyText_returnsEmpty() {
        val hints = CloudCoachingClient.parseResponseText("")
        assertThat(hints).isEmpty()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ai-engine:testDebugUnitTest --tests "com.spectra.ai.cloud.CloudCoachingClientTest"`
Expected: FAIL — `CloudCoachingClient` not found

- [ ] **Step 3: Write the implementation**

```kotlin
package com.spectra.ai.cloud

import android.graphics.Bitmap
import com.spectra.ai.model.CoachingHint
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import android.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudCoachingClient @Inject constructor() {

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private var apiKey: String? = null

    fun setApiKey(key: String) {
        apiKey = key
    }

    suspend fun analyzeFrame(
        bitmap: Bitmap,
        sceneLabel: String,
        lightingLabel: String
    ): CoachingHint? {
        val key = apiKey ?: return null

        val base64 = bitmapToBase64(bitmap)
        val request = buildRequest(base64, sceneLabel, lightingLabel)

        return try {
            val response: VisionResponse = httpClient.post("https://api.anthropic.com/v1/messages") {
                header("x-api-key", key)
                header("anthropic-version", "2023-06-01")
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()

            val text = response.content.firstOrNull { it.type == "text" }?.text ?: return null
            parseResponseText(text).firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        val scaled = Bitmap.createScaledBitmap(bitmap, 512, 384, true)
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    fun release() {
        httpClient.close()
    }

    companion object {
        fun buildPrompt(sceneLabel: String, lightingLabel: String): String {
            return """Analyze this camera viewfinder frame for composition improvement.
Scene: $sceneLabel. Lighting: $lightingLabel.

Respond with 1-2 short directive lines in this format:
ACTION DIRECTION AMOUNT · COMPOSITION TECHNIQUE

Examples:
TILT UP 10° · GOLDEN RATIO ALIGN
STEP LEFT 0.5M · LEADING LINES
LOWER ANGLE · DRAMATIC FOREGROUND

Be specific and actionable. Each directive should be under 50 characters."""
        }

        fun buildRequest(base64Image: String, sceneLabel: String, lightingLabel: String): VisionRequest {
            return VisionRequest(
                messages = listOf(
                    VisionMessage(
                        content = listOf(
                            VisionContent(
                                type = "image",
                                source = ImageSource(data = base64Image)
                            ),
                            VisionContent(
                                type = "text",
                                text = buildPrompt(sceneLabel, lightingLabel)
                            )
                        )
                    )
                )
            )
        }

        fun parseResponseText(text: String): List<CoachingHint> {
            if (text.isBlank()) return emptyList()
            return text.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("*") && !it.startsWith("-") }
                .take(2)
                .map { CloudDirectiveParser.parse(it) }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :ai-engine:testDebugUnitTest --tests "com.spectra.ai.cloud.CloudCoachingClientTest"`
Expected: PASS — all 5 tests green

- [ ] **Step 5: Commit**

```bash
git add ai-engine/src/main/java/com/spectra/ai/cloud/CloudCoachingClient.kt \
        ai-engine/src/test/java/com/spectra/ai/cloud/CloudCoachingClientTest.kt
git commit -m "feat: add cloud coaching client for Claude Vision API"
```

---

### Task 5: CloudCoachingManager — Pause Detection & Orchestration

**Files:**
- Create: `ai-engine/src/main/java/com/spectra/ai/cloud/CloudCoachingManager.kt`
- Test: `ai-engine/src/test/java/com/spectra/ai/cloud/CloudCoachingManagerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.spectra.ai.cloud

import com.google.common.truth.Truth.assertThat
import com.spectra.ai.model.CoachingHint
import org.junit.Test

class CloudCoachingManagerTest {

    private fun makePixels(brightness: Int): IntArray {
        val pixel = (0xFF shl 24) or (brightness shl 16) or (brightness shl 8) or brightness
        return IntArray(32 * 32) { pixel }
    }

    @Test
    fun shouldQuery_noStableFrames_returnsFalse() {
        val manager = CloudCoachingManager(
            client = FakeCloudCoachingClient(),
            cache = FrameSimilarityCache()
        )
        assertThat(manager.shouldQuery()).isFalse()
    }

    @Test
    fun shouldQuery_stableFor2Seconds_returnsTrue() {
        val manager = CloudCoachingManager(
            client = FakeCloudCoachingClient(),
            cache = FrameSimilarityCache()
        )
        val pixels = makePixels(128)
        manager.onFrameAnalyzed(pixels, "LANDSCAPE", "GOLDEN_HOUR")
        manager.setStableStartMs(System.currentTimeMillis() - 2100)
        assertThat(manager.shouldQuery()).isTrue()
    }

    @Test
    fun shouldQuery_cachedResult_returnsFalse() {
        val cache = FrameSimilarityCache()
        val pixels = makePixels(128)
        cache.store(pixels, CoachingHint("CACHED"))
        val manager = CloudCoachingManager(
            client = FakeCloudCoachingClient(),
            cache = cache
        )
        manager.onFrameAnalyzed(pixels, "LANDSCAPE", "GOLDEN_HOUR")
        manager.setStableStartMs(System.currentTimeMillis() - 2100)
        assertThat(manager.shouldQuery()).isFalse()
    }

    @Test
    fun getCachedHint_afterStore_returnsCached() {
        val cache = FrameSimilarityCache()
        val pixels = makePixels(128)
        cache.store(pixels, CoachingHint("FROM CACHE"))
        val manager = CloudCoachingManager(
            client = FakeCloudCoachingClient(),
            cache = cache
        )
        manager.onFrameAnalyzed(pixels, "LANDSCAPE", "GOLDEN_HOUR")
        assertThat(manager.getCachedHint()).isNotNull()
        assertThat(manager.getCachedHint()!!.text).isEqualTo("FROM CACHE")
    }

    @Test
    fun reset_clearsState() {
        val manager = CloudCoachingManager(
            client = FakeCloudCoachingClient(),
            cache = FrameSimilarityCache()
        )
        val pixels = makePixels(128)
        manager.onFrameAnalyzed(pixels, "LANDSCAPE", "GOLDEN_HOUR")
        manager.reset()
        assertThat(manager.shouldQuery()).isFalse()
    }

    private class FakeCloudCoachingClient : CloudCoachingClientInterface {
        override suspend fun analyzeFrame(
            bitmap: android.graphics.Bitmap,
            sceneLabel: String,
            lightingLabel: String
        ): CoachingHint? = CoachingHint("FAKE HINT")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ai-engine:testDebugUnitTest --tests "com.spectra.ai.cloud.CloudCoachingManagerTest"`
Expected: FAIL — `CloudCoachingManager` not found

- [ ] **Step 3: Extract interface from CloudCoachingClient**

Add to `CloudCoachingClient.kt` (above the class):

```kotlin
interface CloudCoachingClientInterface {
    suspend fun analyzeFrame(
        bitmap: Bitmap,
        sceneLabel: String,
        lightingLabel: String
    ): CoachingHint?
}
```

Update `CloudCoachingClient` to implement it:

```kotlin
@Singleton
class CloudCoachingClient @Inject constructor() : CloudCoachingClientInterface {
```

- [ ] **Step 4: Write the implementation**

```kotlin
package com.spectra.ai.cloud

import android.graphics.Bitmap
import com.spectra.ai.model.CoachingHint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudCoachingManager @Inject constructor(
    private val client: CloudCoachingClientInterface,
    private val cache: FrameSimilarityCache
) {
    private val pauseThresholdMs = 2000L
    private var stableStartMs: Long = 0L
    private var lastThumbnail: IntArray? = null
    private var lastSceneLabel: String = ""
    private var lastLightingLabel: String = ""
    private var queryInProgress = false

    private val _cloudHint = MutableStateFlow<CoachingHint?>(null)
    val cloudHint: StateFlow<CoachingHint?> = _cloudHint.asStateFlow()

    fun onFrameAnalyzed(thumbnailPixels: IntArray, sceneLabel: String, lightingLabel: String) {
        val prev = lastThumbnail
        lastThumbnail = thumbnailPixels
        lastSceneLabel = sceneLabel
        lastLightingLabel = lightingLabel

        if (prev == null) {
            stableStartMs = System.currentTimeMillis()
            return
        }

        val similarity = computeQuickSimilarity(prev, thumbnailPixels)
        if (similarity < 0.85f) {
            stableStartMs = System.currentTimeMillis()
            _cloudHint.value = null
        }

        val cached = cache.getCached(thumbnailPixels)
        if (cached != null) {
            _cloudHint.value = cached
        }
    }

    fun shouldQuery(): Boolean {
        val thumb = lastThumbnail ?: return false
        if (queryInProgress) return false
        if (System.currentTimeMillis() - stableStartMs < pauseThresholdMs) return false
        if (cache.getCached(thumb) != null) return false
        return true
    }

    suspend fun queryCloud(bitmap: Bitmap) {
        if (!shouldQuery()) return
        queryInProgress = true
        try {
            val hint = client.analyzeFrame(bitmap, lastSceneLabel, lastLightingLabel)
            if (hint != null) {
                lastThumbnail?.let { cache.store(it, hint) }
                _cloudHint.value = hint
            }
        } finally {
            queryInProgress = false
        }
    }

    fun getCachedHint(): CoachingHint? {
        val thumb = lastThumbnail ?: return null
        return cache.getCached(thumb)
    }

    fun setStableStartMs(ms: Long) {
        stableStartMs = ms
    }

    fun reset() {
        stableStartMs = 0L
        lastThumbnail = null
        lastSceneLabel = ""
        lastLightingLabel = ""
        queryInProgress = false
        cache.clear()
        _cloudHint.value = null
    }

    private fun computeQuickSimilarity(a: IntArray, b: IntArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        val step = maxOf(1, a.size / 256)
        var match = 0
        var total = 0
        for (i in a.indices step step) {
            val ar = (a[i] shr 16) and 0xFF
            val br = (b[i] shr 16) and 0xFF
            if (kotlin.math.abs(ar - br) < 20) match++
            total++
        }
        return match.toFloat() / total
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :ai-engine:testDebugUnitTest --tests "com.spectra.ai.cloud.CloudCoachingManagerTest"`
Expected: PASS — all 5 tests green

- [ ] **Step 6: Commit**

```bash
git add ai-engine/src/main/java/com/spectra/ai/cloud/CloudCoachingManager.kt \
        ai-engine/src/main/java/com/spectra/ai/cloud/CloudCoachingClient.kt \
        ai-engine/src/test/java/com/spectra/ai/cloud/CloudCoachingManagerTest.kt
git commit -m "feat: add cloud coaching manager with 2-second pause detection"
```

---

### Task 6: Wire Cloud Coaching into Pipeline & ViewModel

**Files:**
- Modify: `ai-engine/src/main/java/com/spectra/ai/FrameAnalysisPipeline.kt`
- Modify: `core/src/main/java/com/spectra/core/model/HudState.kt`
- Modify: `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`

- [ ] **Step 1: Add CloudCoachingManager to FrameAnalysisPipeline**

Add `cloudCoachingManager` as a constructor parameter and expose its flow. In `FrameAnalysisPipeline.kt`:

```kotlin
@Singleton
class FrameAnalysisPipeline @Inject constructor(
    private val sceneClassifier: SceneClassifier,
    private val lightingAnalyzer: LightingAnalyzer,
    private val motionDetector: MotionDetector,
    private val distanceEstimator: DistanceEstimator,
    private val decisionEngine: DecisionEngine,
    private val coachingEngine: CoachingEngine,
    private val cloudCoachingManager: CloudCoachingManager
) {
```

Add a public accessor:

```kotlin
val cloudCoachingHint: StateFlow<CoachingHint?> = cloudCoachingManager.cloudHint
```

At the end of `analyzeFrame()`, after setting `_coachingHint.value`, add thumbnail extraction and cloud manager update:

```kotlin
val thumbSize = 32
val thumb = Bitmap.createScaledBitmap(bitmap, thumbSize, thumbSize, true)
val thumbPixels = IntArray(thumbSize * thumbSize)
thumb.getPixels(thumbPixels, 0, thumbSize, 0, 0, thumbSize, thumbSize)
cloudCoachingManager.onFrameAnalyzed(thumbPixels, sceneAnalysis.sceneType.label, sceneAnalysis.lighting.label)
```

In `release()`, add:

```kotlin
cloudCoachingManager.reset()
```

- [ ] **Step 2: Add `cloudCoachingText` / `cloudCoachingArrow` to HudState**

In `core/src/main/java/com/spectra/core/model/HudState.kt`, add two fields:

```kotlin
data class HudState(
    // ... existing fields ...
    val showReferenceCard: Boolean = false,
    val cloudCoachingText: String? = null,
    val cloudCoachingArrow: String = "NONE"
)
```

- [ ] **Step 3: Collect cloud coaching flow in CameraViewModel**

In `CameraViewModel.kt`, add to the `init` block (after the `coachingHint` collector):

```kotlin
viewModelScope.launch {
    pipeline.cloudCoachingHint.collect { hint ->
        _hudState.update { it.copy(
            cloudCoachingText = hint?.text,
            cloudCoachingArrow = hint?.arrow?.name ?: "NONE"
        )}
    }
}
```

Also add a periodic check to trigger cloud queries. Add after the `frameProvider.frames.collect` block:

```kotlin
viewModelScope.launch {
    while (true) {
        kotlinx.coroutines.delay(500)
        if (pipeline.cloudCoachingManager.shouldQuery()) {
            val currentFrame = frameProvider.latestFrame
            if (currentFrame != null) {
                pipeline.cloudCoachingManager.queryCloud(currentFrame)
            }
        }
    }
}
```

- [ ] **Step 4: Expose `latestFrame` from FrameProvider**

In `camera/src/main/java/com/spectra/camera/FrameProvider.kt`, add a field to store the latest bitmap:

```kotlin
var latestFrame: Bitmap? = null
    private set
```

In the `analyze()` method, store the bitmap:

```kotlin
override fun analyze(image: ImageProxy) {
    frameCount++
    if (frameCount % analyzeEveryN == 0) {
        try {
            val bitmap = image.toBitmap()
            latestFrame = bitmap
            _frames.tryEmit(bitmap)
        } catch (_: Exception) {
        }
    }
    image.close()
}
```

- [ ] **Step 5: Expose `cloudCoachingManager` from pipeline**

The `cloudCoachingManager` field in `FrameAnalysisPipeline` is already `private`. Make it accessible for ViewModel:

Change in `FrameAnalysisPipeline.kt`:

```kotlin
val cloudCoachingManager: CloudCoachingManager
```

(remove `private` from the constructor parameter)

- [ ] **Step 6: Display cloud coaching in HudOverlay**

In `app/src/main/java/com/spectra/app/ui/hud/HudOverlay.kt`, show cloud coaching when available (it takes priority over on-device coaching). Replace the existing coaching directive block:

```kotlin
val cloudCoaching = state.cloudCoachingText
val coaching = cloudCoaching ?: state.coachingText
val arrowStr = if (cloudCoaching != null) state.cloudCoachingArrow else state.coachingArrow
if (coaching != null) {
    val arrow = try {
        ArrowDirection.valueOf(arrowStr)
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

- [ ] **Step 7: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add ai-engine/src/main/java/com/spectra/ai/FrameAnalysisPipeline.kt \
        core/src/main/java/com/spectra/core/model/HudState.kt \
        app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt \
        camera/src/main/java/com/spectra/camera/FrameProvider.kt \
        app/src/main/java/com/spectra/app/ui/hud/HudOverlay.kt
git commit -m "feat: wire cloud coaching into pipeline, ViewModel, and HUD"
```

---

### Task 7: PRO Mode — HudState & ViewModel Support

**Files:**
- Modify: `core/src/main/java/com/spectra/core/model/HudState.kt`
- Modify: `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`

- [ ] **Step 1: Add PRO mode fields to HudState**

In `HudState.kt`, add after the `cloudCoachingArrow` field:

```kotlin
val aiRecommendedSettings: CameraSettings = CameraSettings(),
val isManualOverride: Boolean = false
```

- [ ] **Step 2: Add PRO mode methods to CameraViewModel**

In `CameraViewModel.kt`, add these methods:

```kotlin
fun updateProSetting(
    iso: Int? = null,
    shutterSpeedDenominator: Int? = null,
    whiteBalanceKelvin: Int? = null,
    exposureCompensation: Float? = null,
    focusDistance: Float? = null
) {
    _hudState.update { state ->
        val current = state.settings
        val updated = CameraSettings.clamped(
            iso = iso ?: current.iso,
            shutterSpeedDenominator = shutterSpeedDenominator ?: current.shutterSpeedDenominator,
            whiteBalanceKelvin = whiteBalanceKelvin ?: current.whiteBalanceKelvin,
            exposureCompensation = exposureCompensation ?: current.exposureCompensation,
            focusDistance = focusDistance ?: current.focusDistance
        )
        val isOverride = updated != state.aiRecommendedSettings
        state.copy(settings = updated, isManualOverride = isOverride)
    }
}

fun snapToAiRecommendation() {
    _hudState.update { state ->
        state.copy(
            settings = state.aiRecommendedSettings,
            isManualOverride = false
        )
    }
}
```

- [ ] **Step 3: Store AI recommended settings in PRO mode**

In the `pipeline.settingsProfile` collector inside `init`, update to also store AI recommendation:

```kotlin
viewModelScope.launch {
    pipeline.settingsProfile.collect { profile ->
        _hudState.update { state ->
            if (state.mode == CameraMode.PRO) {
                state.copy(aiRecommendedSettings = profile.settings)
            } else {
                state.copy(
                    settings = profile.settings,
                    aiRecommendedSettings = profile.settings
                )
            }
        }
    }
}
```

- [ ] **Step 4: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/spectra/core/model/HudState.kt \
        app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt
git commit -m "feat: add PRO mode state management with AI recommendations"
```

---

### Task 8: PRO Mode — Ghost Marker Sliders UI

**Files:**
- Create: `app/src/main/java/com/spectra/app/ui/pro/GhostMarkerSlider.kt`
- Create: `app/src/main/java/com/spectra/app/ui/pro/ProModePanel.kt`

- [ ] **Step 1: Create GhostMarkerSlider composable**

```kotlin
package com.spectra.app.ui.pro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.spectra.app.ui.theme.HudColors
import com.spectra.app.ui.theme.HudTypography

@Composable
fun GhostMarkerSlider(
    label: String,
    value: Float,
    ghostValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    displayText: String,
    onValueChange: (Float) -> Unit,
    onGhostTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
        Text(
            text = "$label: $displayText",
            style = HudTypography.readoutSmall
        )

        Box {
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                colors = SliderDefaults.colors(
                    thumbColor = HudColors.neonGreen,
                    activeTrackColor = HudColors.neonGreen.copy(alpha = 0.7f),
                    inactiveTrackColor = HudColors.neonGreen.copy(alpha = 0.2f)
                )
            )

            val fraction = if (valueRange.endInclusive != valueRange.start) {
                ((ghostValue - valueRange.start) / (valueRange.endInclusive - valueRange.start))
                    .coerceIn(0f, 1f)
            } else 0f

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .align(Alignment.Center)
            ) {
                val trackWidth = size.width - 40.dp.toPx()
                val xPos = 20.dp.toPx() + fraction * trackWidth

                drawCircle(
                    color = Color(0x8800FF88),
                    radius = 8.dp.toPx(),
                    center = Offset(xPos, size.height / 2)
                )
                drawCircle(
                    color = HudColors.neonGreen.copy(alpha = 0.3f),
                    radius = 12.dp.toPx(),
                    center = Offset(xPos, size.height / 2)
                )
            }
        }
    }
}
```

- [ ] **Step 2: Create ProModePanel composable**

```kotlin
package com.spectra.app.ui.pro

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spectra.app.ui.theme.HudColors
import com.spectra.app.ui.theme.HudTypography
import com.spectra.core.model.CameraMode
import com.spectra.core.model.CameraSettings

@Composable
fun ProModePanel(
    isVisible: Boolean,
    settings: CameraSettings,
    aiSettings: CameraSettings,
    isManualOverride: Boolean,
    onIsoChange: (Int) -> Unit,
    onShutterChange: (Int) -> Unit,
    onWbChange: (Int) -> Unit,
    onEvChange: (Float) -> Unit,
    onFocusChange: (Float) -> Unit,
    onSnapToAi: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(HudColors.background.copy(alpha = 0.85f))
                .padding(vertical = 8.dp)
        ) {
            if (isManualOverride) {
                Text(
                    text = "MANUAL OVERRIDE",
                    style = HudTypography.readoutLarge,
                    color = HudColors.warningAmber,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 4.dp)
                        .clickable { onSnapToAi() }
                )
            }

            GhostMarkerSlider(
                label = "ISO",
                value = settings.iso.toFloat(),
                ghostValue = aiSettings.iso.toFloat(),
                valueRange = 50f..3200f,
                displayText = settings.formattedIso,
                onValueChange = { onIsoChange(it.toInt()) },
                onGhostTap = onSnapToAi
            )

            GhostMarkerSlider(
                label = "SHUTTER",
                value = settings.shutterSpeedDenominator.toFloat(),
                ghostValue = aiSettings.shutterSpeedDenominator.toFloat(),
                valueRange = 1f..8000f,
                displayText = settings.formattedShutterSpeed,
                onValueChange = { onShutterChange(it.toInt()) },
                onGhostTap = onSnapToAi
            )

            GhostMarkerSlider(
                label = "WB",
                value = settings.whiteBalanceKelvin.toFloat(),
                ghostValue = aiSettings.whiteBalanceKelvin.toFloat(),
                valueRange = 2300f..10000f,
                displayText = settings.formattedWb,
                onValueChange = { onWbChange(it.toInt()) },
                onGhostTap = onSnapToAi
            )

            GhostMarkerSlider(
                label = "EV",
                value = settings.exposureCompensation,
                ghostValue = aiSettings.exposureCompensation,
                valueRange = -3f..3f,
                displayText = settings.formattedEv,
                onValueChange = { onEvChange(it) },
                onGhostTap = onSnapToAi
            )

            GhostMarkerSlider(
                label = "FOCUS",
                value = settings.focusDistance,
                ghostValue = aiSettings.focusDistance,
                valueRange = 0f..15f,
                displayText = "${"%.1f".format(settings.focusDistance)}m",
                onValueChange = { onFocusChange(it) },
                onGhostTap = onSnapToAi
            )
        }
    }
}
```

- [ ] **Step 3: Add `warningAmber` to HudColors**

In `app/src/main/java/com/spectra/app/ui/theme/SpectraTheme.kt`, add to the `HudColors` object:

```kotlin
val warningAmber = Color(0xFFFFAA00)
```

- [ ] **Step 4: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/spectra/app/ui/pro/GhostMarkerSlider.kt \
        app/src/main/java/com/spectra/app/ui/pro/ProModePanel.kt \
        app/src/main/java/com/spectra/app/ui/theme/SpectraTheme.kt
git commit -m "feat: add PRO mode slider panel with ghost markers and manual override badge"
```

---

### Task 9: Wire PRO Mode into ViewfinderScreen

**Files:**
- Modify: `app/src/main/java/com/spectra/app/ui/viewfinder/ViewfinderScreen.kt`

- [ ] **Step 1: Add ProModePanel to ViewfinderScreen**

Import the PRO mode panel and add it to the composable. In `ViewfinderScreen.kt`, add the import:

```kotlin
import com.spectra.app.ui.pro.ProModePanel
import com.spectra.core.model.CameraMode
```

Below the `ModeSelector` composable and above `CaptureControls`, add:

```kotlin
ProModePanel(
    isVisible = hudState.mode == CameraMode.PRO,
    settings = hudState.settings,
    aiSettings = hudState.aiRecommendedSettings,
    isManualOverride = hudState.isManualOverride,
    onIsoChange = { viewModel.updateProSetting(iso = it) },
    onShutterChange = { viewModel.updateProSetting(shutterSpeedDenominator = it) },
    onWbChange = { viewModel.updateProSetting(whiteBalanceKelvin = it) },
    onEvChange = { viewModel.updateProSetting(exposureCompensation = it) },
    onFocusChange = { viewModel.updateProSetting(focusDistance = it) },
    onSnapToAi = { viewModel.snapToAiRecommendation() },
    modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(bottom = 130.dp)
)
```

- [ ] **Step 2: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/spectra/app/ui/viewfinder/ViewfinderScreen.kt
git commit -m "feat: wire PRO mode panel into viewfinder screen"
```

---

### Task 10: Hilt Wiring for Cloud Coaching

**Files:**
- Create: `ai-engine/src/main/java/com/spectra/ai/cloud/CloudModule.kt`

- [ ] **Step 1: Create Hilt module for cloud coaching bindings**

```kotlin
package com.spectra.ai.cloud

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CloudModule {

    @Binds
    @Singleton
    abstract fun bindCloudCoachingClient(
        impl: CloudCoachingClient
    ): CloudCoachingClientInterface
}
```

- [ ] **Step 2: Build and verify DI graph**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add ai-engine/src/main/java/com/spectra/ai/cloud/CloudModule.kt
git commit -m "feat: add Hilt module for cloud coaching dependency injection"
```

---

### Task 11: Integration Test & Tag

**Files:**
- Modify: `app/src/test/java/com/spectra/app/viewmodel/CameraViewModelTest.kt` (if needed)

- [ ] **Step 1: Run full test suite**

Run: `./gradlew testDebugUnitTest`
Expected: All tests pass

- [ ] **Step 2: Build release APK**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Install and verify on device**

Run: `./gradlew installDebug`
Expected: App installs, all 4 modes work — PHOTO/PORT/NIGHT show normal HUD, PRO shows sliders with ghost markers

- [ ] **Step 4: Tag Phase 4**

```bash
git tag -a v0.4.0-phase4 -m "Phase 4: Cloud AI coaching + PRO mode with ghost markers"
```

- [ ] **Step 5: Final commit if any cleanup needed**

Verify everything is committed:

```bash
git status
```
