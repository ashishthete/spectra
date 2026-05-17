package com.spectra.app.ui.hud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spectra.app.ui.tips.TipsThumbnail
import com.spectra.core.model.HudState

@Composable
fun HudOverlay(
    state: HudState,
    onCoachingDismiss: () -> Unit,
    onTipsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        ScanLines()
        CornerBrackets()

        AnimatedVisibility(
            visible = state.isHudVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                CrosshairAndGrid()

                SceneReadout(
                    sceneLabel = state.sceneLabel,
                    confidence = state.sceneConfidence,
                    lightingLabel = state.lightingLabel,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 24.dp, top = 24.dp)
                )

                SettingsReadout(
                    activeLens = state.activeLens,
                    settings = state.settings,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 24.dp, top = 24.dp)
                )

                MotionIndicator(
                    motionLevel = state.motionLevel,
                    distanceLabel = state.distanceLabel,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 24.dp)
                )

                LensMatchBars(
                    scores = state.lensMatchScores,
                    activeLens = state.activeLens,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 24.dp)
                )

                val coaching = state.coachingText
                if (coaching != null) {
                    CoachingDirective(
                        text = coaching,
                        onDismiss = onCoachingDismiss,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 180.dp)
                    )
                }

                TipsThumbnail(
                    sceneLabel = state.sceneLabel,
                    isVisible = state.showTipsThumbnail,
                    onClick = onTipsClick,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 24.dp, bottom = 180.dp)
                )
            }
        }
    }
}
