package com.spectra.app.ui.hud

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.spectra.app.ui.theme.HudTypography
import com.spectra.core.model.CameraSettings
import com.spectra.core.model.LensId

@Composable
fun SettingsReadout(
    activeLens: LensId,
    settings: CameraSettings,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "${activeLens.megapixels}MP ACTIVE",
            style = HudTypography.readoutLarge,
            textAlign = TextAlign.End
        )
        Text(
            text = "${settings.formattedIso} · ${settings.formattedShutterSpeed} · f/${activeLens.maxAperture}",
            style = HudTypography.readoutSmall,
            textAlign = TextAlign.End
        )
        Text(
            text = "${settings.formattedWb} · ${settings.formattedEv}",
            style = HudTypography.readoutSmall,
            textAlign = TextAlign.End
        )
    }
}
