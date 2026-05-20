package com.spectra.ai

import com.spectra.core.model.CameraPreset
import com.spectra.core.model.CameraSettings
import com.spectra.core.model.LensId

object TeachMeEngine {

    data class Lesson(
        val topic: String,
        val explanation: String,
        val tip: String
    )

    fun explainSettings(
        settings: CameraSettings,
        preset: CameraPreset,
        sceneLabel: String,
        iso: Int,
        shutterSpeedNs: Long,
        faceCount: Int,
        isLowLight: Boolean,
        motionLevel: Int,
        lightingLabel: String = "",
        isHdrActive: Boolean = false,
        sceneContrast: Float = 0f
    ): List<Lesson> {
        val lessons = mutableListOf<Lesson>()

        val isoDenom = if (shutterSpeedNs > 0) (1_000_000_000L / shutterSpeedNs).toInt() else 0
        val sceneContext = if (sceneLabel.isNotEmpty() && sceneLabel != "READY") " in this $sceneLabel scene" else ""
        val lightContext = if (lightingLabel.isNotEmpty() && lightingLabel != "—") " (${lightingLabel.lowercase()} lighting)" else ""

        if (iso <= 200) {
            lessons.add(Lesson(
                "ISO $iso",
                "The AI chose low ISO$sceneContext$lightContext because there's enough light. Less amplification = cleaner image with minimal grain.",
                "Use low ISO whenever there's enough light — it always produces better quality."
            ))
        } else if (iso >= 800) {
            val reason = when {
                isLowLight && motionLevel > 1 -> "The scene is dark and there's movement — the AI raised ISO to keep the shutter fast enough to freeze motion"
                isLowLight -> "The scene is dark$lightContext — the AI raised ISO to capture enough light without motion blur"
                motionLevel > 2 -> "Fast motion detected — the AI boosted ISO to enable a fast shutter speed"
                else -> "The AI raised ISO because the scene needs more light$lightContext"
            }
            lessons.add(Lesson(
                "ISO $iso",
                "$reason. Higher ISO means more grain, but it's a necessary trade-off here.",
                "To use lower ISO: add light, use a wider aperture, slow the shutter, or use a tripod."
            ))
        }

        if (isoDenom >= 500) {
            val reason = when {
                motionLevel > 2 -> "The AI detected fast movement$sceneContext and chose a fast shutter to freeze the action"
                preset == CameraPreset.ACTION -> "Action mode prioritizes freezing motion — this shutter speed will capture the peak moment"
                preset == CameraPreset.MACRO -> "Close-up shots amplify tiny hand movements — the AI chose a fast shutter to keep the subject sharp"
                else -> "The AI chose a fast shutter speed to prevent blur${if (motionLevel > 0) " — motion was detected" else ""}"
            }
            lessons.add(Lesson(
                "1/${isoDenom}s",
                "$reason.",
                "Fast shutter = sharp action. Slow shutter = motion blur (creative) or shake (unwanted)."
            ))
        } else if (isoDenom > 0 && isoDenom < 30) {
            val reason = when {
                isLowLight && preset == CameraPreset.NIGHT -> "Night mode uses a slower shutter to gather more light — the AI will merge multiple frames to compensate for any hand shake"
                isLowLight -> "The AI slowed the shutter to let in more light$lightContext — hold steady or brace the phone"
                else -> "The AI chose a slow shutter speed for maximum light gathering"
            }
            lessons.add(Lesson(
                "1/${isoDenom}s",
                "$reason.",
                "Below 1/60s, use a tripod or brace the phone. Below 1/30s, even breathing causes blur."
            ))
        }

        if (isHdrActive) {
            lessons.add(Lesson(
                "HDR Active",
                "The AI detected high contrast (bright sky + dark foreground)$sceneContext — it will capture multiple exposures and merge them to recover both highlights and shadows that a single shot would lose.",
                "HDR works best on still scenes. Moving subjects may show ghosting artifacts."
            ))
        }

        if (preset == CameraPreset.PORTRAIT && faceCount > 0) {
            val faceReason = if (faceCount > 1) "With ${faceCount} people, the AI balances exposure across all faces" else "The AI is metering for the face to ensure flattering skin exposure"
            lessons.add(Lesson(
                "Portrait telephoto",
                "The AI chose the telephoto lens because longer focal lengths compress perspective, making faces more natural. $faceReason.",
                "For portraits: 50mm+ equivalent is flattering. Below 35mm distorts noses and foreheads."
            ))
        }

        when (preset) {
            CameraPreset.NIGHT -> lessons.add(Lesson(
                "Night Mode",
                "The AI is capturing multiple frames and merging them to reduce noise$lightContext. Each frame is short enough to hand-hold, and alignment corrects for small movements between frames.",
                "Night mode works best when you hold still for 2-3 seconds after pressing the shutter."
            ))
            CameraPreset.FOOD -> lessons.add(Lesson(
                "Food Photography",
                "The AI set warm white balance to make the food look appetizing and kept contrast moderate to preserve texture$lightContext. Low ISO keeps the image clean for close viewing.",
                "Natural window light from the side is the #1 food photography tip. Avoid flash — it makes food look flat."
            ))
            CameraPreset.LANDSCAPE -> {
                val hdrNote = if (isHdrActive || sceneContrast > 0.2f) " HDR is recovering sky detail that would otherwise blow out." else ""
                lessons.add(Lesson(
                    "Landscape Mode",
                    "The AI prioritized base ISO for maximum sharpness across the entire scene$lightContext.$hdrNote",
                    "Place the horizon on the upper or lower third — never dead center — for more dynamic compositions."
                ))
            }
            CameraPreset.MACRO -> lessons.add(Lesson(
                "Macro Mode",
                "The AI is using a fast shutter to counteract the magnified hand shake that close-ups create. ${if (isLowLight) "In this low light, it's trading some noise for sharpness." else "Good light means the AI can keep ISO low for a clean close-up."}",
                "Get as close as the lens allows and let autofocus lock before shooting. A surface to brace on helps enormously."
            ))
            CameraPreset.ACTION -> lessons.add(Lesson(
                "Action Mode",
                "The AI prioritized shutter speed over noise — it chose ISO $iso to enable 1/${isoDenom}s, fast enough to freeze${if (motionLevel > 2) " the detected" else ""} movement.",
                "Pre-focus where the action will happen, then use burst mode to catch the peak moment."
            ))
            else -> {}
        }

        return lessons
    }
}
