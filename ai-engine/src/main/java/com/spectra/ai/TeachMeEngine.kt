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
        motionLevel: Int
    ): List<Lesson> {
        val lessons = mutableListOf<Lesson>()

        val isoDenom = if (shutterSpeedNs > 0) (1_000_000_000L / shutterSpeedNs).toInt() else 0

        if (iso <= 200) {
            lessons.add(Lesson(
                "ISO $iso",
                "Low ISO means less sensor amplification, producing a cleaner image with minimal grain.",
                "Use low ISO whenever there's enough light — it always produces better quality."
            ))
        } else if (iso >= 800) {
            lessons.add(Lesson(
                "ISO $iso",
                "High ISO amplifies the sensor signal to capture more light, but introduces grain/noise. The camera chose this because the scene is ${if (isLowLight) "dark" else "dim"}.",
                "To use lower ISO: add light, use a wider aperture, slow the shutter, or use a tripod."
            ))
        }

        if (isoDenom >= 500) {
            lessons.add(Lesson(
                "1/${isoDenom}s",
                "Fast shutter speed freezes motion. ${if (motionLevel > 1) "Movement was detected in the scene." else "This prevents camera shake from hand-holding."}",
                "Fast shutter = sharp action. Slow shutter = motion blur (creative) or shake (unwanted)."
            ))
        } else if (isoDenom > 0 && isoDenom < 30) {
            lessons.add(Lesson(
                "1/${isoDenom}s",
                "Slow shutter lets in more light but risks motion blur. ${if (isLowLight) "The camera is compensating for low light." else ""}",
                "Below 1/60s, use a tripod or brace the phone. Below 1/30s, even breathing causes blur."
            ))
        }

        if (preset == CameraPreset.PORTRAIT && faceCount > 0) {
            lessons.add(Lesson(
                "Telephoto lens",
                "Longer focal lengths compress perspective, making faces look more natural and flattering. Wide-angle lenses distort facial features.",
                "For portraits: 50mm+ equivalent is flattering. Below 35mm distorts noses and foreheads."
            ))
        }

        when (preset) {
            CameraPreset.NIGHT -> lessons.add(Lesson(
                "Night Mode",
                "The camera captures multiple frames and merges them to reduce noise without a tripod. Each frame is short enough to hand-hold.",
                "Night mode works best when you hold still for 2-3 seconds after pressing the shutter."
            ))
            CameraPreset.FOOD -> lessons.add(Lesson(
                "Food Photography",
                "Warm white balance and moderate close-up framing make food look appetizing. The camera avoids harsh contrast.",
                "Natural window light from the side is the #1 food photography tip. Avoid flash — it makes food look flat."
            ))
            CameraPreset.LANDSCAPE -> lessons.add(Lesson(
                "Landscape Mode",
                "Low ISO and moderate shutter speed maximize detail across the entire scene. The camera prioritizes sharpness over speed.",
                "Place the horizon on the upper or lower third — never dead center — for more dynamic compositions."
            ))
            else -> {}
        }

        return lessons
    }
}
