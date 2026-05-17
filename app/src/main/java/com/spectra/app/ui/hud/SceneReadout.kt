package com.spectra.app.ui.hud

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.spectra.app.ui.theme.HudTypography

@Composable
fun SceneReadout(
    sceneLabel: String,
    confidence: Float,
    lightingLabel: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "SCENE: $sceneLabel",
            style = HudTypography.readoutLarge
        )
        Text(
            text = "CONF: ${"%.1f".format(confidence * 100)}% · $lightingLabel",
            style = HudTypography.readoutSmall
        )
    }
}
