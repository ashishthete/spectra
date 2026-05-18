package com.spectra.core.model

data class CaptureExplanation(
    val iso: Int,
    val shutterSpeedNs: Long,
    val sceneLabel: String,
    val lightingLabel: String,
    val isHdrApplied: Boolean = false,
    val isPortraitBokeh: Boolean = false,
    val isNightMode: Boolean = false,
    val reasons: List<String> = emptyList()
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
        get() = "AI chose $isoLabel · $shutterLabel"

    val summaryReasons: List<String>
        get() = reasons.take(3)
}
