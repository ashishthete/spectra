package com.spectra.ai

import com.spectra.core.model.CameraPreset

object ShotSuggestionEngine {

    data class ShotSuggestion(
        val text: String,
        val priority: Int = 0
    )

    private val suggestionBank = mapOf(
        CameraPreset.LANDSCAPE to listOf(
            ShotSuggestion("Place horizon on the lower third for dramatic sky", 1),
            ShotSuggestion("Include foreground interest for depth", 2),
            ShotSuggestion("Try ultrawide for sweeping perspective", 1),
            ShotSuggestion("Wait for golden hour light on the scene", 0)
        ),
        CameraPreset.PORTRAIT to listOf(
            ShotSuggestion("Position subject slightly off-center", 2),
            ShotSuggestion("Ensure catchlights in the eyes", 1),
            ShotSuggestion("Try a slightly lower angle for a powerful look", 0),
            ShotSuggestion("Use the telephoto for flattering compression", 1)
        ),
        CameraPreset.FOOD to listOf(
            ShotSuggestion("Shoot from 45 degrees for a natural dining perspective", 2),
            ShotSuggestion("Use natural window light from the side", 1),
            ShotSuggestion("Include utensils or hands for context", 0),
            ShotSuggestion("Get closer to fill the frame", 1)
        ),
        CameraPreset.NIGHT to listOf(
            ShotSuggestion("Brace against a surface for stability", 2),
            ShotSuggestion("Include light sources for visual anchors", 1),
            ShotSuggestion("Try a long exposure for light trails", 0)
        ),
        CameraPreset.ACTION to listOf(
            ShotSuggestion("Pre-focus on where the action will happen", 2),
            ShotSuggestion("Use burst mode for peak moments", 1),
            ShotSuggestion("Leave space in the direction of movement", 1)
        ),
        CameraPreset.MACRO to listOf(
            ShotSuggestion("Keep the camera parallel to the subject plane", 2),
            ShotSuggestion("Use the 3x telephoto for working distance", 1),
            ShotSuggestion("Shoot in bright light for maximum sharpness", 1)
        )
    )

    fun getSuggestion(
        preset: CameraPreset,
        compositionThirdsScore: Float = 0f,
        isStable: Boolean = true,
        brightness: Float = 0.5f
    ): ShotSuggestion? {
        val candidates = suggestionBank[preset] ?: return null

        val brightnessOk = brightness in 0.3f..0.7f

        val filtered = candidates.filter { suggestion ->
            val text = suggestion.text.lowercase()
            val isStabilityHint = "stability" in text || "brace" in text
            val isBrightnessHint = "bright light" in text || "more light" in text || "find light" in text ||
                "window light" in text || "natural light" in text
            if (isStabilityHint && isStable) return@filter false
            if (isBrightnessHint && brightnessOk) return@filter false
            true
        }

        return filtered.maxByOrNull { it.priority }
    }
}
