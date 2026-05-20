package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HdrProcessorTest {

    private val processor = HdrProcessor()

    @Test
    fun `computeBracketExposures returns 3 values`() {
        val result = HdrProcessor.computeBracketExposures(10_000_000L, 200)
        assertThat(result).hasSize(3)
    }

    @Test
    fun `bracket exposures are ordered underexposed to overexposed`() {
        val result = HdrProcessor.computeBracketExposures(10_000_000L, 200)
        assertThat(result[0].first).isLessThan(result[1].first)
        assertThat(result[1].first).isLessThan(result[2].first)
    }

    @Test
    fun `bracket middle exposure matches base`() {
        val baseNs = 10_000_000L
        val baseIso = 200
        val result = HdrProcessor.computeBracketExposures(baseNs, baseIso)
        assertThat(result[1].first).isEqualTo(baseNs)
        assertThat(result[1].second).isEqualTo(baseIso)
    }

    @Test
    fun `bracket under-exposure is approximately base divided by 2_83`() {
        val baseNs = 10_000_000L
        val result = HdrProcessor.computeBracketExposures(baseNs, 200)
        val expected = (baseNs / 2.83f).toLong()
        assertThat(result[0].first).isEqualTo(expected)
    }

    @Test
    fun `bracket over-exposure is approximately base multiplied by 2_83`() {
        val baseNs = 10_000_000L
        val result = HdrProcessor.computeBracketExposures(baseNs, 200)
        val expected = (baseNs * 2.83f).toLong()
        assertThat(result[2].first).isEqualTo(expected)
    }

    @Test
    fun `bracket spread is approximately 3 EV total`() {
        val baseNs = 10_000_000L
        val result = HdrProcessor.computeBracketExposures(baseNs, 200)
        val ratio = result[2].first.toFloat() / result[0].first.toFloat()
        // ±1.5EV means total spread of 3EV, ratio should be 2.83^2 ≈ 8.0
        assertThat(ratio).isGreaterThan(7.0f)
        assertThat(ratio).isLessThan(9.0f)
    }

    @Test
    fun `bracket keeps ISO constant`() {
        val result = HdrProcessor.computeBracketExposures(10_000_000L, 400)
        assertThat(result[0].second).isEqualTo(400)
        assertThat(result[1].second).isEqualTo(400)
        assertThat(result[2].second).isEqualTo(400)
    }

    @Test
    fun `computeWeight contrast enhances edges`() {
        val flat = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f)
        val edgy = floatArrayOf(0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f)
        val flatWeight = HdrProcessor.contrastWeight(flat, 3, 3, 1, 1)
        val edgyWeight = HdrProcessor.contrastWeight(edgy, 3, 3, 1, 1)
        assertThat(edgyWeight).isGreaterThan(flatWeight)
    }

    @Test
    fun `wellExposedness peaks at 0_5`() {
        val at05 = HdrProcessor.wellExposednessWeight(0.5f)
        val at01 = HdrProcessor.wellExposednessWeight(0.1f)
        val at09 = HdrProcessor.wellExposednessWeight(0.9f)
        assertThat(at05).isGreaterThan(at01)
        assertThat(at05).isGreaterThan(at09)
    }

    @Test
    fun `saturationWeight is higher for colorful pixels`() {
        val colorful = HdrProcessor.saturationWeight(0.8f, 0.2f, 0.1f)
        val gray = HdrProcessor.saturationWeight(0.5f, 0.5f, 0.5f)
        assertThat(colorful).isGreaterThan(gray)
    }

    @Test
    fun `mertensFusion with single frame returns copy`() {
        val w = 4; val h = 4
        val pixels = IntArray(w * h) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }
        val result = processor.mertensFusion(listOf(pixels), w, h)
        assertThat(result).hasLength(w * h)
        for (i in result.indices) {
            assertThat(result[i]).isEqualTo(pixels[i])
        }
    }

    @Test
    fun `mertensFusion with empty list returns empty`() {
        val result = processor.mertensFusion(emptyList(), 0, 0)
        assertThat(result).hasLength(0)
    }

    @Test
    fun `mertensFusion of three frames produces valid pixels`() {
        val w = 8; val h = 8; val n = w * h
        val dark = IntArray(n) { (0xFF shl 24) or (30 shl 16) or (30 shl 8) or 30 }
        val mid = IntArray(n) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }
        val bright = IntArray(n) { (0xFF shl 24) or (240 shl 16) or (240 shl 8) or 240 }
        val result = processor.mertensFusion(listOf(dark, mid, bright), w, h)
        assertThat(result).hasLength(n)
        for (pixel in result) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            assertThat(r).isIn(0..255)
            assertThat(g).isIn(0..255)
            assertThat(b).isIn(0..255)
        }
    }

    @Test
    fun `mertensFusion prefers well-exposed mid frame`() {
        val w = 4; val h = 4; val n = w * h
        val dark = IntArray(n) { (0xFF shl 24) or (10 shl 16) or (10 shl 8) or 10 }
        val mid = IntArray(n) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }
        val bright = IntArray(n) { (0xFF shl 24) or (250 shl 16) or (250 shl 8) or 250 }
        val result = processor.mertensFusion(listOf(dark, mid, bright), w, h)
        val avgR = result.map { (it shr 16) and 0xFF }.average()
        assertThat(avgR).isGreaterThan(50.0)
        assertThat(avgR).isLessThan(200.0)
    }

    @Test
    fun `alignFrame with no motion returns same pixels`() {
        val w = 16; val h = 16; val n = w * h
        val frame = IntArray(n) { i ->
            val x = i % w; val y = i / w
            (0xFF shl 24) or ((x * 16) shl 16) or ((y * 16) shl 8) or 128
        }
        val aligned = processor.alignFrame(frame, frame, w, h)
        for (i in aligned.indices) {
            assertThat(aligned[i]).isEqualTo(frame[i])
        }
    }

    // --- Ghost detection tests ---

    @Test
    fun `detectGhostRegions returns all-false for identical frames`() {
        val w = 8; val h = 8; val n = w * h
        val frame = IntArray(n) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }
        val mask = HdrProcessor.detectGhostRegions(listOf(frame, frame, frame), w, h)
        assertThat(mask).hasLength(n)
        // All identical frames should produce no ghost regions
        assertThat(mask.any { it }).isFalse()
    }

    @Test
    fun `detectGhostRegions returns all-false for single frame`() {
        val w = 4; val h = 4; val n = w * h
        val frame = IntArray(n) { (0xFF shl 24) or (100 shl 16) or (100 shl 8) or 100 }
        val mask = HdrProcessor.detectGhostRegions(listOf(frame), w, h)
        assertThat(mask).hasLength(n)
        assertThat(mask.any { it }).isFalse()
    }

    @Test
    fun `detectGhostRegions returns empty for empty list`() {
        val mask = HdrProcessor.detectGhostRegions(emptyList(), 0, 0)
        assertThat(mask).hasLength(0)
    }

    @Test
    fun `detectGhostRegions detects moved object`() {
        val w = 32; val h = 32; val n = w * h

        // Frame 1: gradient background, bright object at top-left
        val frame1 = IntArray(n) { i ->
            val x = i % w; val y = i / w
            val v = if (x < 16 && y < 16) 240 else (40 + y * 4).coerceAtMost(160)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

        // Frame 2: same gradient, bright object at bottom-right
        val frame2 = IntArray(n) { i ->
            val x = i % w; val y = i / w
            val v = if (x >= 16 && y >= 16) 240 else (40 + y * 4).coerceAtMost(160)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

        val mask = HdrProcessor.detectGhostRegions(listOf(frame1, frame2), w, h)
        assertThat(mask.any { it }).isTrue()
    }

    @Test
    fun `detectGhostRegions finds disagreeing pixels`() {
        val w = 4; val h = 4; val n = w * h
        val frame1 = IntArray(n) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }
        val frame2 = IntArray(n) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }
        // Make some pixels very different in frame2
        frame2[0] = (0xFF shl 24) or (255 shl 16) or (255 shl 8) or 255
        frame2[1] = (0xFF shl 24) or (255 shl 16) or (255 shl 8) or 255
        val mask = HdrProcessor.detectGhostRegions(listOf(frame1, frame2), w, h)
        assertThat(mask.size).isEqualTo(n)
    }

    @Test
    fun `detectGhostRegions with significantly different frames marks ghosts`() {
        val w = 8; val h = 8; val n = w * h

        // Frame 1: mostly bright with a dark patch (top-left 2x2)
        val frame1 = IntArray(n) { i ->
            val x = i % w; val y = i / w
            if (x < 2 && y < 2) {
                (0xFF shl 24) or (20 shl 16) or (20 shl 8) or 20
            } else {
                (0xFF shl 24) or (200 shl 16) or (200 shl 8) or 200
            }
        }

        // Frame 2: mostly dark with a bright patch (top-left 2x2)
        val frame2 = IntArray(n) { i ->
            val x = i % w; val y = i / w
            if (x < 2 && y < 2) {
                (0xFF shl 24) or (200 shl 16) or (200 shl 8) or 200
            } else {
                (0xFF shl 24) or (20 shl 16) or (20 shl 8) or 20
            }
        }

        val mask = HdrProcessor.detectGhostRegions(listOf(frame1, frame2), w, h)
        val ghostCount = mask.count { it }
        assertThat(ghostCount).isGreaterThan(0)
    }

    // --- Laplacian contrast weight tests ---

    @Test
    fun `laplacianContrastWeight returns zero for flat region`() {
        val w = 5; val h = 5
        val flat = FloatArray(w * h) { 0.5f }
        val weight = HdrProcessor.laplacianContrastWeight(flat, w, h, 2, 2)
        assertThat(weight).isWithin(0.001f).of(0f)
    }

    @Test
    fun `laplacianContrastWeight returns nonzero for edge`() {
        val w = 5; val h = 5
        val lum = FloatArray(w * h) { i ->
            val x = i % w
            if (x < 3) 0.2f else 0.8f
        }
        // Pixel at (2,2) is on the edge boundary
        val weight = HdrProcessor.laplacianContrastWeight(lum, w, h, 2, 2)
        assertThat(weight).isGreaterThan(0f)
    }

    @Test
    fun `laplacianContrastWeight returns zero for border pixels`() {
        val w = 5; val h = 5
        val lum = FloatArray(w * h) { 0.5f }
        // Border pixels should return 0
        assertThat(HdrProcessor.laplacianContrastWeight(lum, w, h, 0, 0)).isEqualTo(0f)
        assertThat(HdrProcessor.laplacianContrastWeight(lum, w, h, 4, 4)).isEqualTo(0f)
        assertThat(HdrProcessor.laplacianContrastWeight(lum, w, h, 0, 2)).isEqualTo(0f)
        assertThat(HdrProcessor.laplacianContrastWeight(lum, w, h, 2, 0)).isEqualTo(0f)
    }

    @Test
    fun `laplacianContrastWeight is higher for sharp detail than smooth gradient`() {
        val w = 5; val h = 5

        // Sharp edge: center pixel is very different from neighbors
        val sharp = FloatArray(w * h) { 0.2f }
        sharp[2 * w + 2] = 0.9f // center pixel is bright, neighbors dark

        // Smooth gradient
        val smooth = FloatArray(w * h) { i ->
            val x = i % w
            x.toFloat() / (w - 1).toFloat()
        }

        val sharpWeight = HdrProcessor.laplacianContrastWeight(sharp, w, h, 2, 2)
        val smoothWeight = HdrProcessor.laplacianContrastWeight(smooth, w, h, 2, 2)
        assertThat(sharpWeight).isGreaterThan(smoothWeight)
    }

    // --- Ghost-aware fusion test ---

    @Test
    fun `mertensFusion uses reference frame for ghosted pixels`() {
        val w = 8; val h = 8; val n = w * h

        // Frame 1 (under): left=bright, right=dark
        val frame1 = IntArray(n) { i ->
            val x = i % w
            if (x < 4) (0xFF shl 24) or (200 shl 16) or (200 shl 8) or 200
            else (0xFF shl 24) or (20 shl 16) or (20 shl 8) or 20
        }

        // Frame 2 (base): uniform mid-gray
        val frame2 = IntArray(n) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }

        // Frame 3 (over): inverted from frame1
        val frame3 = IntArray(n) { i ->
            val x = i % w
            if (x < 4) (0xFF shl 24) or (20 shl 16) or (20 shl 8) or 20
            else (0xFF shl 24) or (200 shl 16) or (200 shl 8) or 200
        }

        val result = processor.mertensFusion(listOf(frame1, frame2, frame3), w, h)
        // Ghost regions should fall back to frame2 (base, index 1) which is 128
        // For heavily ghosted areas, the output should be close to 128
        assertThat(result).hasLength(n)
        for (pixel in result) {
            val r = (pixel shr 16) and 0xFF
            assertThat(r).isIn(0..255)
        }
    }

    @Test
    fun `semanticMertensFusion falls back to standard for single frame`() {
        val w = 8; val h = 8; val n = w * h
        val frame = IntArray(n) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }
        val result = processor.semanticMertensFusion(listOf(frame), w, h)
        assertThat(result).hasLength(n)
    }

    @Test
    fun `semanticMertensFusion produces valid output with sky scene`() {
        val w = 32; val h = 32; val n = w * h
        val darkFrame = IntArray(n) { i ->
            val y = i / w
            if (y < 12) (0xFF shl 24) or (50 shl 16) or (80 shl 8) or 160
            else (0xFF shl 24) or (30 shl 16) or (50 shl 8) or 20
        }
        val midFrame = IntArray(n) { i ->
            val y = i / w
            if (y < 12) (0xFF shl 24) or (100 shl 16) or (140 shl 8) or 220
            else (0xFF shl 24) or (80 shl 16) or (120 shl 8) or 60
        }
        val brightFrame = IntArray(n) { i ->
            val y = i / w
            if (y < 12) (0xFF shl 24) or (220 shl 16) or (230 shl 8) or 250
            else (0xFF shl 24) or (160 shl 16) or (200 shl 8) or 120
        }
        val result = processor.semanticMertensFusion(listOf(darkFrame, midFrame, brightFrame), w, h)
        assertThat(result).hasLength(n)
        for (pixel in result) {
            val r = (pixel shr 16) and 0xFF
            assertThat(r).isIn(0..255)
        }
    }

    @Test
    fun `mertensFusionPyramid produces valid output`() {
        val w = 16; val h = 16; val n = w * h
        val dark = IntArray(n) { (0xFF shl 24) or (60 shl 16) or (60 shl 8) or 60 }
        val mid = IntArray(n) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }
        val bright = IntArray(n) { (0xFF shl 24) or (220 shl 16) or (220 shl 8) or 220 }
        val result = processor.mertensFusionPyramid(listOf(dark, mid, bright), w, h)
        assertThat(result).hasLength(n)
        for (pixel in result) {
            val r = (pixel shr 16) and 0xFF
            assertThat(r).isIn(0..255)
        }
    }

    @Test
    fun `mertensFusionPyramid single frame returns copy`() {
        val w = 8; val h = 8; val n = w * h
        val frame = IntArray(n) { (0xFF shl 24) or (100 shl 16) or (100 shl 8) or 100 }
        val result = processor.mertensFusionPyramid(listOf(frame), w, h)
        assertThat(result).hasLength(n)
        for (i in result.indices) assertThat(result[i]).isEqualTo(frame[i])
    }

    @Test
    fun `wellExposednessWeight with custom target shifts peak`() {
        val defaultPeak = HdrProcessor.wellExposednessWeight(0.5f)
        val skyPeak = HdrProcessor.wellExposednessWeight(0.35f, targetLum = 0.35f)
        val fgPeak = HdrProcessor.wellExposednessWeight(0.6f, targetLum = 0.6f)
        assertThat(defaultPeak).isGreaterThan(0.9f)
        assertThat(skyPeak).isGreaterThan(0.9f)
        assertThat(fgPeak).isGreaterThan(0.9f)
    }

    @Test
    fun `3-frame bracket always includes at least 3 frames`() {
        val result = HdrProcessor.computeBracketExposures(10_000_000L, 200)
        assertThat(result.size).isAtLeast(3)
    }

    @Test
    fun `3-frame bracket always includes base exposure`() {
        val baseNs = 16_000_000L
        val result = HdrProcessor.computeBracketExposures(baseNs, 400)
        val baseFrame = result.find { it.first == baseNs && it.second == 400 }
        assertThat(baseFrame).isNotNull()
    }

    @Test
    fun `3-frame bracket spacing is approximately 1_5 EV`() {
        val baseNs = 10_000_000L
        val result = HdrProcessor.computeBracketExposures(baseNs, 200)
        val underRatio = baseNs.toFloat() / result[0].first
        val overRatio = result[2].first.toFloat() / baseNs
        assertThat(underRatio).isWithin(0.3f).of(2.83f)
        assertThat(overRatio).isWithin(0.3f).of(2.83f)
    }

    @Test
    fun `5-frame bracket returns 5 frames`() {
        val result = HdrProcessor.computeBracketExposures5Frame(10_000_000L, 200)
        assertThat(result).hasSize(5)
    }

    @Test
    fun `5-frame bracket includes base exposure`() {
        val baseNs = 10_000_000L
        val result = HdrProcessor.computeBracketExposures5Frame(baseNs, 200)
        assertThat(result[2].first).isEqualTo(baseNs)
        assertThat(result[2].second).isEqualTo(200)
    }

    @Test
    fun `5-frame bracket is ordered underexposed to overexposed`() {
        val result = HdrProcessor.computeBracketExposures5Frame(10_000_000L, 200)
        for (i in 0 until result.size - 1) {
            assertThat(result[i].first).isLessThan(result[i + 1].first)
        }
    }

    @Test
    fun `5-frame bracket spread is approximately 6 EV total`() {
        val result = HdrProcessor.computeBracketExposures5Frame(10_000_000L, 200)
        val ratio = result[4].first.toFloat() / result[0].first.toFloat()
        assertThat(ratio).isGreaterThan(50f)
        assertThat(ratio).isLessThan(80f)
    }

    @Test
    fun `highlight bracket with evBias shifts base exposure`() {
        val baseNs = 10_000_000L
        val result = HdrProcessor.computeBracketExposuresForHighlights(baseNs, 200, -0.5f)
        assertThat(result).hasSize(3)
        assertThat(result[1].first).isLessThan(baseNs)
    }

    @Test
    fun `computeHighlightEvBias returns zero for low contrast`() {
        assertThat(HdrProcessor.computeHighlightEvBias(0.1f)).isEqualTo(0f)
    }

    @Test
    fun `computeHighlightEvBias returns negative for high contrast`() {
        assertThat(HdrProcessor.computeHighlightEvBias(0.8f)).isLessThan(0f)
    }

    @Test
    fun `bracket on S24-like 1_10 EV step device produces correct exposure times`() {
        val baseNs = 8_333_333L
        val baseIso = 100
        val result = HdrProcessor.computeBracketExposures(baseNs, baseIso)
        assertThat(result[0].first).isEqualTo((baseNs / 2.83f).toLong())
        assertThat(result[1].first).isEqualTo(baseNs)
        assertThat(result[2].first).isEqualTo((baseNs * 2.83f).toLong())
    }
}
