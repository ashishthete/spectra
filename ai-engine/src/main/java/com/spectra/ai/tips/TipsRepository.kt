package com.spectra.ai.tips

import com.spectra.ai.model.PhotoTip
import com.spectra.core.model.SceneType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TipsRepository @Inject constructor() {

    private val tips = mapOf(
        SceneType.LANDSCAPE to PhotoTip(
            sceneType = SceneType.LANDSCAPE,
            title = "LANDSCAPE MASTERY",
            tips = listOf(
                "Place horizon on upper or lower third — never center",
                "Shoot during golden hour for warm, dramatic light",
                "Use leading lines (roads, rivers) to draw the eye in"
            ),
            referenceImageAsset = "tips/landscape.webp"
        ),
        SceneType.PORTRAIT to PhotoTip(
            sceneType = SceneType.PORTRAIT,
            title = "PORTRAIT PERFECTION",
            tips = listOf(
                "Focus on the eyes — they anchor the viewer's attention",
                "Use 3x telephoto for flattering compression",
                "Position subject facing natural light for soft, even tones"
            ),
            referenceImageAsset = "tips/portrait.webp"
        ),
        SceneType.FOOD to PhotoTip(
            sceneType = SceneType.FOOD,
            title = "FOOD PHOTOGRAPHY",
            tips = listOf(
                "Shoot at 45° angle or directly overhead for best presentation",
                "Use natural window light — avoid flash completely",
                "Add context: utensils, napkins, hands for storytelling"
            ),
            referenceImageAsset = "tips/food.webp"
        ),
        SceneType.NIGHT to PhotoTip(
            sceneType = SceneType.NIGHT,
            title = "NIGHT CAPTURE",
            tips = listOf(
                "Brace against a wall or use a tripod for sharp shots",
                "Include light sources (streetlamps, signs) for visual anchors",
                "Let the AI extend exposure — stay perfectly still"
            ),
            referenceImageAsset = "tips/night.webp"
        ),
        SceneType.ARCHITECTURE to PhotoTip(
            sceneType = SceneType.ARCHITECTURE,
            title = "ARCHITECTURE SHOTS",
            tips = listOf(
                "Keep vertical lines straight — tilt correction matters",
                "Look for symmetry and repeating patterns",
                "Use ultrawide lens but watch for distortion at edges"
            ),
            referenceImageAsset = "tips/architecture.webp"
        ),
        SceneType.MACRO to PhotoTip(
            sceneType = SceneType.MACRO,
            title = "MACRO DETAIL",
            tips = listOf(
                "Hold your breath and stabilize — tiny movements blur macro shots",
                "Use soft, diffused light to avoid harsh reflections",
                "Focus on textures and patterns that aren't visible to the naked eye"
            ),
            referenceImageAsset = "tips/macro.webp"
        ),
        SceneType.PET to PhotoTip(
            sceneType = SceneType.PET,
            title = "PET PHOTOGRAPHY",
            tips = listOf(
                "Get down to their eye level for engaging perspective",
                "Use burst mode — animals are unpredictable",
                "Natural light near a window works best for fur detail"
            ),
            referenceImageAsset = "tips/pet.webp"
        ),
        SceneType.ACTION to PhotoTip(
            sceneType = SceneType.ACTION,
            title = "ACTION SHOTS",
            tips = listOf(
                "Pre-focus on where the action will happen",
                "Pan with the subject for motion blur in background",
                "Use burst mode and pick the peak moment afterward"
            ),
            referenceImageAsset = "tips/action.webp"
        ),
        SceneType.DOCUMENT to PhotoTip(
            sceneType = SceneType.DOCUMENT,
            title = "DOCUMENT SCAN",
            tips = listOf(
                "Shoot directly overhead to avoid perspective distortion",
                "Ensure even lighting — no shadows across the text",
                "Fill the frame with the document for maximum resolution"
            ),
            referenceImageAsset = "tips/document.webp"
        ),
        SceneType.INDOOR to PhotoTip(
            sceneType = SceneType.INDOOR,
            title = "INDOOR PHOTOGRAPHY",
            tips = listOf(
                "Use the main lens for its wide f/1.7 aperture in low light",
                "Turn off overhead fluorescents — use window light instead",
                "Watch white balance — indoor lighting shifts colors warm/cool"
            ),
            referenceImageAsset = "tips/indoor.webp"
        )
    )

    fun getTip(sceneType: SceneType): PhotoTip? = tips[sceneType]
}
