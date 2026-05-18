package com.spectra.ai.model

enum class LightingCondition(val label: String) {
    GOLDEN_HOUR("GOLDEN HOUR"),
    BLUE_HOUR("BLUE HOUR"),
    BRIGHT_DAYLIGHT("DAYLIGHT"),
    OVERCAST("OVERCAST"),
    HARSH_MIDDAY("HARSH LIGHT"),
    BACKLIT("BACKLIT"),
    LOW_LIGHT("LOW LIGHT"),
    ARTIFICIAL("ARTIFICIAL"),
    STUDIO("STUDIO"),
    MIXED("MIXED"),
    UNKNOWN("—");
}
