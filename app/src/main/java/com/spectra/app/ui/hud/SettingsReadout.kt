package com.spectra.app.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectra.app.ui.theme.HudColors
import com.spectra.core.model.CameraMode
import com.spectra.core.model.CameraSettings
import com.spectra.core.model.SettingsDisplayMode
import kotlin.math.abs

@Composable
fun SettingsReadout(
    aperture: Float,
    settings: CameraSettings,
    mode: CameraMode,
    actualIso: Int,
    actualShutterNs: Long,
    actualColorTemperature: Int = 0,
    aiRecommendedSettings: CameraSettings = CameraSettings(),
    settingsDisplayMode: SettingsDisplayMode = SettingsDisplayMode.ACTUAL,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(HudColors.surfaceGlass, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = "f/${"%.1f".format(aperture)}",
            color = HudColors.textSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End
        )
        if (mode == CameraMode.PRO) {
            Text(
                text = "${settings.formattedIso} · ${settings.formattedShutterSpeed}",
                color = HudColors.accent.copy(alpha = 0.7f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End
            )
            Text(
                text = settings.formattedWb,
                color = HudColors.accent.copy(alpha = 0.5f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End
            )
        } else if (settingsDisplayMode == SettingsDisplayMode.SMART_AUTO) {
            Text(
                text = "AI AUTO",
                color = HudColors.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End
            )
            if (actualIso > 0) {
                val shutterDenom = if (actualShutterNs > 0) {
                    (1_000_000_000L / actualShutterNs).toInt().coerceIn(1, 32000)
                } else 0
                val shutterText = if (shutterDenom > 1) "1/${shutterDenom}s" else if (shutterDenom == 1) "1s" else "—"
                Text(
                    text = "ISO $actualIso · $shutterText",
                    color = HudColors.accent.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End
                )
                if (actualColorTemperature > 0) {
                    Text(
                        text = "${actualColorTemperature}K",
                        color = HudColors.accent.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.End
                    )
                }
            } else {
                Text(
                    text = "${aiRecommendedSettings.formattedIso} · ${aiRecommendedSettings.formattedShutterSpeed}",
                    color = HudColors.textMuted.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End
                )
            }
        } else if (actualIso > 0) {
            Text(
                text = "AUTO",
                color = HudColors.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End
            )

            val shutterDenom = if (actualShutterNs > 0) {
                (1_000_000_000L / actualShutterNs).toInt().coerceIn(1, 32000)
            } else 0
            val shutterText = if (shutterDenom > 1) "1/${shutterDenom}s" else if (shutterDenom == 1) "1s" else "—"
            Text(
                text = "ISO $actualIso · $shutterText",
                color = HudColors.textSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End
            )

            val aiIso = aiRecommendedSettings.iso
            val aiShutterDenom = aiRecommendedSettings.shutterSpeedDenominator
            val isoDiffRatio = if (actualIso > 0) abs(aiIso - actualIso).toFloat() / actualIso else 0f
            val shutterRatio = if (shutterDenom > 0 && aiShutterDenom > 0) {
                maxOf(aiShutterDenom.toFloat() / shutterDenom, shutterDenom.toFloat() / aiShutterDenom)
            } else 0f
            if (aiIso > 0 && (isoDiffRatio > 0.2f || shutterRatio >= 2f)) {
                Text(
                    text = "AI: ${aiRecommendedSettings.formattedIso} · ${aiRecommendedSettings.formattedShutterSpeed}",
                    color = HudColors.textMuted.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End
                )
            }

            if (actualColorTemperature > 0) {
                Text(
                    text = "${actualColorTemperature}K",
                    color = HudColors.textMuted.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}
