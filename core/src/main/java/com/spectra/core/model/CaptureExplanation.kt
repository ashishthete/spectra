package com.spectra.core.model

data class CaptureExplanation(
    val iso: Int,
    val shutterSpeedNs: Long,
    val sceneLabel: String,
    val lightingLabel: String,
    val isHdrApplied: Boolean = false,
    val isPortraitBokeh: Boolean = false,
    val isNightMode: Boolean = false,
    val reasons: List<String> = emptyList(),
    val appliedStages: List<String> = emptyList(),
    val hdrFrameCount: Int = 0,
    val burstFrameCount: Int = 0,
    val denoiseStrength: String = "",
    val bokehRadius: Int = 0,
    val beautyApplied: Boolean = false,
    val lensUsed: LensId = LensId.MAIN,
    val processingBackend: String = "CPU+AGSL"
) {
    val isoLabel: String
        get() = "ISO $iso"

    val shutterLabel: String
        get() {
            if (shutterSpeedNs <= 0L) return "Auto"
            val denominator = 1_000_000_000L / shutterSpeedNs
            return when {
                shutterSpeedNs >= 1_000_000_000L -> "${shutterSpeedNs / 1_000_000_000L}s"
                denominator >= 1 -> "1/${denominator}s"
                else -> "${shutterSpeedNs / 1_000_000}ms"
            }
        }

    val headline: String
        get() = headlineForTier(UserTier.CREATOR)

    fun headlineForTier(tier: UserTier): String = when {
        isTrueScene -> when (tier) {
            UserTier.EVERYDAY -> "Natural photo · $isoLabel · $shutterLabel"
            UserTier.CREATOR -> "True Scene captured · $isoLabel · $shutterLabel"
            UserTier.PRO -> "True Scene · $isoLabel · $shutterLabel · $processingBackend"
        }
        else -> when (tier) {
            UserTier.EVERYDAY -> "Auto $isoLabel · $shutterLabel"
            UserTier.CREATOR -> "AI chose $isoLabel · $shutterLabel"
            UserTier.PRO -> "$isoLabel · $shutterLabel · ${appliedStages.size} stages · $processingBackend"
        }
    }

    val summaryReasons: List<String>
        get() = reasons.take(3)

    val stageLabels: List<String>
        get() = stageLabelsForTier(UserTier.CREATOR)

    fun stageLabelsForTier(tier: UserTier): List<String> = appliedStages.map { stage ->
        when (tier) {
            UserTier.EVERYDAY -> everydayLabel(stage)
            UserTier.CREATOR -> creatorLabel(stage)
            UserTier.PRO -> proLabel(stage)
        }
    }

    private fun everydayLabel(stage: String): String = when (stage) {
        "noise_reduction" -> "Cleaned up grain"
        "tone_curve", "local_tone_map" -> "Made lighting look natural"
        "shadow_recovery" -> "Brightened dark areas"
        "sky_gnd" -> "Balanced the sky"
        "beauty" -> "Smoothed skin"
        "highlight_rolloff" -> "Saved bright spots"
        "portrait_bokeh" -> "Blurred background"
        "hdr_bracket" -> "Combined $hdrFrameCount photos for better detail"
        "burst_merge" -> "Combined $burstFrameCount photos for less grain"
        "skin_protection" -> "Kept skin looking natural"
        "chroma_compress" -> "Kept colors accurate"
        "focus_stack" -> "Sharpened everything front to back"
        "neural_denoise" -> "AI cleaned up the image"
        "depth_mask" -> "Separated you from the background"
        "laplacian_sharpen" -> "Sharpened details"
        else -> stage.replace("_", " ").replaceFirstChar { it.uppercase() }
    }

    private fun creatorLabel(stage: String): String = when (stage) {
        "noise_reduction" -> "Reduced noise"
        "tone_curve", "local_tone_map" -> "Adjusted tones"
        "shadow_recovery" -> "Recovered shadows"
        "sky_gnd" -> "Balanced sky exposure"
        "beauty" -> "Applied beauty enhancement"
        "highlight_rolloff" -> "Protected highlights"
        "portrait_bokeh" -> "Added portrait depth"
        "hdr_bracket" -> "Recovered highlights (${hdrFrameCount}-frame HDR)"
        "burst_merge" -> "Reduced noise ($burstFrameCount frames merged)"
        "skin_protection" -> "Protected skin tones"
        "chroma_compress" -> "Preserved color accuracy"
        "focus_stack" -> "Focus stacked for edge-to-edge sharpness"
        "neural_denoise" -> "AI noise removal applied"
        "depth_mask" -> "Depth-aware subject isolation"
        "laplacian_sharpen" -> "Multi-scale detail sharpening"
        else -> stage.replace("_", " ").replaceFirstChar { it.uppercase() }
    }

    private fun proLabel(stage: String): String = when (stage) {
        "noise_reduction" -> "Bilateral NR, YCbCr domain"
        "tone_curve", "local_tone_map" -> "Guided-filter local tone map"
        "shadow_recovery" -> "Shadow lift +${String.format("%.1f", 1.5f)} EV"
        "sky_gnd" -> "Sky/ground GND simulation"
        "beauty" -> "Frequency-separation beauty"
        "highlight_rolloff" -> "Highlight rolloff, shoulder compress"
        "portrait_bokeh" -> "Depth bokeh, disc kernel r=$bokehRadius"
        "hdr_bracket" -> "${hdrFrameCount}-frame Mertens fusion, ghost-detect"
        "burst_merge" -> "${burstFrameCount}-frame Wiener merge, tile-aligned"
        "skin_protection" -> "Skin hue lock, ΔE < 2.0 target"
        "chroma_compress" -> "Chroma gamut compress, Rec.709 clip"
        "focus_stack" -> "Laplacian variance focus stack"
        "neural_denoise" -> "TFLite denoise, 256px tiles"
        "depth_mask" -> "Depth mask, 256×256 MiDaS"
        "laplacian_sharpen" -> "3-level Laplacian pyramid sharpen"
        else -> stage.replace("_", " ").replaceFirstChar { it.uppercase() }
    }

    val isTrueScene: Boolean
        get() = processingBackend == "TRUE_SCENE"

    val processingBadge: String
        get() {
            if (appliedStages.isEmpty() && reasons.any { "True Scene" in it }) return "True Scene"
            return buildString {
                if (isHdrApplied) append("HDR ")
                if (isNightMode) append("Night ")
                if (isPortraitBokeh) append("Portrait ")
                if (beautyApplied) append("Beauty ")
            }.trim().ifEmpty { "Auto" }
        }
}
