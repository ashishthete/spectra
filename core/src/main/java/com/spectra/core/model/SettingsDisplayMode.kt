// core/src/main/java/com/spectra/core/model/SettingsDisplayMode.kt
package com.spectra.core.model

enum class SettingsDisplayMode(
    val showsActualSensorValues: Boolean,
    val appliesAiValues: Boolean
) {
    ACTUAL(showsActualSensorValues = true, appliesAiValues = false),
    SMART_AUTO(showsActualSensorValues = false, appliesAiValues = true),
    MANUAL(showsActualSensorValues = false, appliesAiValues = false);
}
