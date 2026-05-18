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

@Composable
fun SettingsReadout(
    aperture: Float,
    settings: CameraSettings,
    mode: CameraMode,
    actualIso: Int,
    actualShutterNs: Long,
    actualColorTemperature: Int = 0,
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
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End
        )
        if (mode == CameraMode.PRO) {
            Text(
                text = "${settings.formattedIso} · ${settings.formattedShutterSpeed}",
                color = HudColors.accent.copy(alpha = 0.7f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End
            )
            Text(
                text = settings.formattedWb,
                color = HudColors.accent.copy(alpha = 0.5f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End
            )
        } else if (actualIso > 0) {
            val shutterDenom = if (actualShutterNs > 0) {
                (1_000_000_000L / actualShutterNs).toInt().coerceIn(1, 32000)
            } else 0
            val shutterText = if (shutterDenom > 1) "1/$shutterDenom" else if (shutterDenom == 1) "1s" else "—"
            Text(
                text = "ISO $actualIso · $shutterText",
                color = HudColors.textMuted,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End
            )
            if (actualColorTemperature > 0) {
                Text(
                    text = "${actualColorTemperature}K",
                    color = HudColors.textMuted.copy(alpha = 0.7f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}
