package com.spectra.app.ui.pro

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectra.app.ui.theme.HudColors
import com.spectra.app.ui.theme.HudTypography
import com.spectra.core.model.CameraSettings

private enum class ProParam(val label: String) {
    ISO("ISO"),
    SHUTTER("SS"),
    WB("WB"),
    EV("EV"),
    FOCUS("FOCUS");

    fun getValue(s: CameraSettings): Float = when (this) {
        ISO -> s.iso.toFloat()
        SHUTTER -> s.shutterSpeedDenominator.toFloat()
        WB -> s.whiteBalanceKelvin.toFloat()
        EV -> s.exposureCompensation
        FOCUS -> s.focusDistance
    }

    fun getDisplay(s: CameraSettings): String = when (this) {
        ISO -> "${s.iso}"
        SHUTTER -> s.formattedShutterSpeed
        WB -> s.formattedWb
        EV -> when {
            s.exposureCompensation > 0 -> "+${"%.1f".format(s.exposureCompensation)}"
            s.exposureCompensation < 0 -> "${"%.1f".format(s.exposureCompensation)}"
            else -> "0"
        }
        FOCUS -> if (s.focusDistance == 0f) "AF" else "${"%.1f".format(s.focusDistance)}m"
    }

    val isLogScale: Boolean get() = this == ISO || this == SHUTTER

    fun toSliderPosition(value: Float): Float {
        val r = range
        if (!isLogScale) return value
        val logMin = kotlin.math.ln(r.start.coerceAtLeast(1f))
        val logMax = kotlin.math.ln(r.endInclusive)
        val logVal = kotlin.math.ln(value.coerceIn(r.start, r.endInclusive).coerceAtLeast(1f))
        return ((logVal - logMin) / (logMax - logMin)) * (r.endInclusive - r.start) + r.start
    }

    fun fromSliderPosition(position: Float): Float {
        val r = range
        if (!isLogScale) return position
        val logMin = kotlin.math.ln(r.start.coerceAtLeast(1f))
        val logMax = kotlin.math.ln(r.endInclusive)
        val t = (position - r.start) / (r.endInclusive - r.start)
        return kotlin.math.exp(logMin + t * (logMax - logMin))
    }

    val range: ClosedFloatingPointRange<Float> get() = when (this) {
        ISO -> 50f..3200f
        SHUTTER -> 1f..8000f
        WB -> 2300f..10000f
        EV -> -3f..3f
        FOCUS -> 0f..15f
    }

    val rangeLow: String get() = when (this) {
        ISO -> "50"
        SHUTTER -> "1s"
        WB -> "2.3K"
        EV -> "-3"
        FOCUS -> "AF"
    }

    val rangeHigh: String get() = when (this) {
        ISO -> "3200"
        SHUTTER -> "1/8000"
        WB -> "10K"
        EV -> "+3"
        FOCUS -> "15m"
    }
}

@Composable
fun ProModePanel(
    isVisible: Boolean,
    settings: CameraSettings,
    aiSettings: CameraSettings,
    isManualOverride: Boolean,
    focusPeakingEnabled: Boolean = false,
    zebraEnabled: Boolean = false,
    rawEnabled: Boolean = false,
    gridLabel: String = "3×3",
    onIsoChange: (Int) -> Unit,
    onShutterChange: (Int) -> Unit,
    onWbChange: (Int) -> Unit,
    onEvChange: (Float) -> Unit,
    onFocusChange: (Float) -> Unit,
    onSnapToAi: () -> Unit,
    zebraThreshold: Int = 235,
    onToggleFocusPeaking: () -> Unit = {},
    onToggleZebra: () -> Unit = {},
    onToggleRaw: () -> Unit = {},
    onCycleZebraThreshold: () -> Unit = {},
    onCycleGrid: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedParam by remember { mutableStateOf(ProParam.ISO) }

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(HudColors.background.copy(alpha = 0.9f))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (isManualOverride) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "MANUAL OVERRIDE",
                        style = HudTypography.readoutSmall,
                        color = HudColors.warningAmber,
                        letterSpacing = 2.sp
                    )
                    Text(
                        text = "↺ SNAP AI",
                        style = HudTypography.readoutSmall,
                        color = HudColors.accent,
                        modifier = Modifier.clickable { onSnapToAi() }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ProParam.entries.forEach { param ->
                    val isSelected = selectedParam == param
                    val isDifferent = param.getValue(settings) != param.getValue(aiSettings)

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(60.dp)
                            .let {
                                if (isSelected) {
                                    it.border(
                                        1.dp,
                                        HudColors.accent.copy(alpha = 0.6f),
                                        RoundedCornerShape(6.dp)
                                    )
                                } else it
                            }
                            .background(
                                if (isSelected) HudColors.accent.copy(alpha = 0.1f)
                                else Color.Transparent,
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { selectedParam = param }
                            .padding(vertical = 4.dp, horizontal = 2.dp)
                    ) {
                        Text(
                            text = param.label,
                            color = if (isSelected) HudColors.accent
                                    else HudColors.accent.copy(alpha = 0.5f),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = param.getDisplay(settings),
                            color = when {
                                isSelected && isDifferent -> HudColors.warningAmber
                                isSelected -> HudColors.accent
                                isDifferent -> HudColors.warningAmber.copy(alpha = 0.7f)
                                else -> HudColors.accent.copy(alpha = 0.6f)
                            },
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${selectedParam.label}: ${selectedParam.getDisplay(settings)}",
                    color = HudColors.accent,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "AI: ${selectedParam.getDisplay(aiSettings)}",
                    color = HudColors.accent.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Box {
                Slider(
                    value = selectedParam.toSliderPosition(selectedParam.getValue(settings)),
                    onValueChange = { sliderPos ->
                        val value = selectedParam.fromSliderPosition(sliderPos)
                        when (selectedParam) {
                            ProParam.ISO -> onIsoChange(value.toInt())
                            ProParam.SHUTTER -> onShutterChange(value.toInt())
                            ProParam.WB -> onWbChange(value.toInt())
                            ProParam.EV -> onEvChange(value)
                            ProParam.FOCUS -> onFocusChange(value)
                        }
                    },
                    valueRange = selectedParam.range,
                    colors = SliderDefaults.colors(
                        thumbColor = HudColors.accent,
                        activeTrackColor = HudColors.accent.copy(alpha = 0.7f),
                        inactiveTrackColor = HudColors.accent.copy(alpha = 0.15f)
                    )
                )

                val ghostValue = selectedParam.toSliderPosition(selectedParam.getValue(aiSettings))
                val range = selectedParam.range
                val fraction = if (range.endInclusive != range.start) {
                    ((ghostValue - range.start) / (range.endInclusive - range.start))
                        .coerceIn(0f, 1f)
                } else 0f

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .align(Alignment.Center)
                ) {
                    val trackWidth = size.width - 40.dp.toPx()
                    val xPos = 20.dp.toPx() + fraction * trackWidth

                    drawCircle(
                        color = HudColors.aiCyan.copy(alpha = 0.5f),
                        radius = 6.dp.toPx(),
                        center = Offset(xPos, size.height / 2)
                    )
                    drawCircle(
                        color = HudColors.aiCyan.copy(alpha = 0.25f),
                        radius = 10.dp.toPx(),
                        center = Offset(xPos, size.height / 2)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = selectedParam.rangeLow,
                    color = HudColors.accent.copy(alpha = 0.3f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = selectedParam.rangeHigh,
                    color = HudColors.accent.copy(alpha = 0.3f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ProToggle("RAW", rawEnabled, onToggleRaw)
                ProToggle("PEAK", focusPeakingEnabled, onToggleFocusPeaking)
                ProToggle("ZEBRA", zebraEnabled, onToggleZebra)
                if (zebraEnabled) {
                    Text(
                        text = "${zebraThreshold / 255f * 100f}".take(2) + "%",
                        color = HudColors.accent.copy(alpha = 0.5f),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .background(HudColors.surfaceGlass, RoundedCornerShape(4.dp))
                            .clickable { onCycleZebraThreshold() }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
                Text(
                    text = "GRID: $gridLabel",
                    color = HudColors.accent.copy(alpha = 0.5f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .background(HudColors.surfaceGlass, RoundedCornerShape(4.dp))
                        .clickable { onCycleGrid() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ProToggle(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (enabled) HudColors.accent else HudColors.accent.copy(alpha = 0.3f),
        fontSize = 9.sp,
        fontWeight = if (enabled) FontWeight.Bold else FontWeight.Normal,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .background(
                if (enabled) HudColors.accent.copy(alpha = 0.15f) else HudColors.surfaceGlass,
                RoundedCornerShape(4.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}
