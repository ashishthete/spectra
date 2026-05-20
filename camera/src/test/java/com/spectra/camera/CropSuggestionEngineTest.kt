package com.spectra.camera

import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CropSuggestionEngineTest {

    private val imageWidth = 4000
    private val imageHeight = 3000

    // -- 1. suggest() returns exactly 3 suggestions (top 3 by score) --

    @Test
    fun `suggest returns exactly 3 suggestions`() {
        val results = CropSuggestionEngine.suggest(imageWidth, imageHeight)
        assertThat(results).hasSize(3)
    }

    @Test
    fun `suggest returns exactly 3 suggestions when faces are provided`() {
        val face = RectF(0.4f, 0.3f, 0.6f, 0.7f)
        val results = CropSuggestionEngine.suggest(imageWidth, imageHeight, faceRects = listOf(face))
        assertThat(results).hasSize(3)
    }

    @Test
    fun `suggest returns exactly 3 suggestions when saliency center is provided`() {
        val results = CropSuggestionEngine.suggest(
            imageWidth, imageHeight, saliencyCenter = Pair(0.3f, 0.7f)
        )
        assertThat(results).hasSize(3)
    }

    // -- 2. All returned crops are within image bounds --

    @Test
    fun `all crops are within image bounds with no focus hints`() {
        val results = CropSuggestionEngine.suggest(imageWidth, imageHeight)
        for (suggestion in results) {
            assertThat(suggestion.rect.left).isAtLeast(0)
            assertThat(suggestion.rect.top).isAtLeast(0)
            assertThat(suggestion.rect.right).isAtMost(imageWidth)
            assertThat(suggestion.rect.bottom).isAtMost(imageHeight)
        }
    }

    @Test
    fun `all crops are within image bounds with face near edge`() {
        val edgeFace = RectF(0.85f, 0.85f, 0.98f, 0.98f)
        val results = CropSuggestionEngine.suggest(
            imageWidth, imageHeight, faceRects = listOf(edgeFace)
        )
        for (suggestion in results) {
            assertThat(suggestion.rect.left).isAtLeast(0)
            assertThat(suggestion.rect.top).isAtLeast(0)
            assertThat(suggestion.rect.right).isAtMost(imageWidth)
            assertThat(suggestion.rect.bottom).isAtMost(imageHeight)
        }
    }

    @Test
    fun `all crops are within image bounds with face near top-left corner`() {
        val cornerFace = RectF(0.01f, 0.01f, 0.1f, 0.15f)
        val results = CropSuggestionEngine.suggest(
            imageWidth, imageHeight, faceRects = listOf(cornerFace)
        )
        for (suggestion in results) {
            assertThat(suggestion.rect.left).isAtLeast(0)
            assertThat(suggestion.rect.top).isAtLeast(0)
            assertThat(suggestion.rect.right).isAtMost(imageWidth)
            assertThat(suggestion.rect.bottom).isAtMost(imageHeight)
        }
    }

    // -- 3. With face rects, crops are centered near the face --

    @Test
    fun `crops are centered near the face when face is provided`() {
        val face = RectF(0.3f, 0.2f, 0.5f, 0.6f)
        val faceCenterX = (face.left + face.right) / 2f
        val faceCenterY = (face.top + face.bottom) / 2f

        val results = CropSuggestionEngine.suggest(
            imageWidth, imageHeight, faceRects = listOf(face)
        )

        for (suggestion in results) {
            val cropCenterX = (suggestion.rect.left + suggestion.rect.right) / 2f / imageWidth
            val cropCenterY = (suggestion.rect.top + suggestion.rect.bottom) / 2f / imageHeight
            // Crop center should be within 0.3 normalized distance of the face center
            val dx = cropCenterX - faceCenterX
            val dy = cropCenterY - faceCenterY
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            assertThat(dist).isLessThan(0.3f)
        }
    }

    @Test
    fun `largest face is used as focus when multiple faces are provided`() {
        val smallFace = RectF(0.1f, 0.1f, 0.15f, 0.15f)   // area = 0.05 * 0.05 = 0.0025
        val largeFace = RectF(0.6f, 0.4f, 0.8f, 0.7f)      // area = 0.2 * 0.3 = 0.06
        val largeFaceCenterX = (largeFace.left + largeFace.right) / 2f
        val largeFaceCenterY = (largeFace.top + largeFace.bottom) / 2f

        val results = CropSuggestionEngine.suggest(
            imageWidth, imageHeight, faceRects = listOf(smallFace, largeFace)
        )

        for (suggestion in results) {
            val cropCenterX = (suggestion.rect.left + suggestion.rect.right) / 2f / imageWidth
            val cropCenterY = (suggestion.rect.top + suggestion.rect.bottom) / 2f / imageHeight
            val dx = cropCenterX - largeFaceCenterX
            val dy = cropCenterY - largeFaceCenterY
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            assertThat(dist).isLessThan(0.3f)
        }
    }

    // -- 4. Score is between 0 and 1 for all suggestions --

    @Test
    fun `all scores are between 0 and 1 with no focus hints`() {
        val results = CropSuggestionEngine.suggest(imageWidth, imageHeight)
        for (suggestion in results) {
            assertThat(suggestion.score).isAtLeast(0f)
            assertThat(suggestion.score).isAtMost(1f)
        }
    }

    @Test
    fun `all scores are between 0 and 1 with face rects`() {
        val face = RectF(0.3f, 0.2f, 0.7f, 0.8f)
        val results = CropSuggestionEngine.suggest(
            imageWidth, imageHeight, faceRects = listOf(face)
        )
        for (suggestion in results) {
            assertThat(suggestion.score).isAtLeast(0f)
            assertThat(suggestion.score).isAtMost(1f)
        }
    }

    @Test
    fun `all scores are between 0 and 1 with saliency center`() {
        val results = CropSuggestionEngine.suggest(
            imageWidth, imageHeight, saliencyCenter = Pair(0.8f, 0.2f)
        )
        for (suggestion in results) {
            assertThat(suggestion.score).isAtLeast(0f)
            assertThat(suggestion.score).isAtMost(1f)
        }
    }

    // -- 5. Suggestions include different aspect ratio labels --

    @Test
    fun `suggestions include different aspect ratio labels`() {
        val results = CropSuggestionEngine.suggest(imageWidth, imageHeight)
        val labels = results.map { it.aspectRatio }.toSet()
        assertThat(labels.size).isEqualTo(3)
    }

    @Test
    fun `all labels come from the known set of aspect ratios`() {
        val knownLabels = setOf("1:1", "4:5", "9:16", "16:9", "3:2")
        val results = CropSuggestionEngine.suggest(imageWidth, imageHeight)
        for (suggestion in results) {
            assertThat(suggestion.aspectRatio).isIn(knownLabels)
        }
    }

    // -- 6. With no faces and no saliency, focus defaults to center --

    @Test
    fun `with no faces and no saliency focus defaults to center`() {
        val results = CropSuggestionEngine.suggest(imageWidth, imageHeight)
        for (suggestion in results) {
            val cropCenterX = (suggestion.rect.left + suggestion.rect.right) / 2f / imageWidth
            val cropCenterY = (suggestion.rect.top + suggestion.rect.bottom) / 2f / imageHeight
            // With center focus (0.5, 0.5), crop centers should be near the image center
            assertThat(cropCenterX).isWithin(0.15f).of(0.5f)
            assertThat(cropCenterY).isWithin(0.15f).of(0.5f)
        }
    }

    @Test
    fun `center focus produces different crops than edge focus`() {
        val centerResults = CropSuggestionEngine.suggest(imageWidth, imageHeight)
        val edgeResults = CropSuggestionEngine.suggest(
            imageWidth, imageHeight, saliencyCenter = Pair(0.1f, 0.1f)
        )
        // At least one crop rect should differ between center and edge focus
        val centerRects = centerResults.map { it.rect }
        val edgeRects = edgeResults.map { it.rect }
        assertThat(centerRects).isNotEqualTo(edgeRects)
    }

    // -- 7. Each suggestion has a non-empty reason string --

    @Test
    fun `each suggestion has a non-empty reason with no faces`() {
        val results = CropSuggestionEngine.suggest(imageWidth, imageHeight)
        for (suggestion in results) {
            assertThat(suggestion.reason).isNotEmpty()
        }
    }

    @Test
    fun `each suggestion has a non-empty reason with faces`() {
        val face = RectF(0.3f, 0.2f, 0.6f, 0.7f)
        val results = CropSuggestionEngine.suggest(
            imageWidth, imageHeight, faceRects = listOf(face)
        )
        for (suggestion in results) {
            assertThat(suggestion.reason).isNotEmpty()
        }
    }

    @Test
    fun `reason mentions face when face rects are provided`() {
        val face = RectF(0.3f, 0.2f, 0.6f, 0.7f)
        val results = CropSuggestionEngine.suggest(
            imageWidth, imageHeight, faceRects = listOf(face)
        )
        // At least one suggestion should reference faces/portrait in its reason
        val hasFaceReason = results.any { suggestion ->
            suggestion.reason.lowercase().let { r ->
                r.contains("face") || r.contains("portrait")
            }
        }
        assertThat(hasFaceReason).isTrue()
    }

    // -- Sorting: results are in descending score order --

    @Test
    fun `suggestions are sorted by score in descending order`() {
        val results = CropSuggestionEngine.suggest(imageWidth, imageHeight)
        for (i in 0 until results.size - 1) {
            assertThat(results[i].score).isAtLeast(results[i + 1].score)
        }
    }

    @Test
    fun `suggestions are sorted by score in descending order with faces`() {
        val face = RectF(0.4f, 0.3f, 0.6f, 0.7f)
        val results = CropSuggestionEngine.suggest(
            imageWidth, imageHeight, faceRects = listOf(face)
        )
        for (i in 0 until results.size - 1) {
            assertThat(results[i].score).isAtLeast(results[i + 1].score)
        }
    }
}
