package com.spectra.app.ui.hud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectra.ai.model.ArrowDirection
import com.spectra.app.ui.theme.HudColors
import com.spectra.core.model.CameraMode
import com.spectra.core.model.GridMode
import com.spectra.core.model.HudState

@Composable
fun HudOverlay(
    state: HudState,
    onCoachingDismiss: () -> Unit,
    onTipsClick: () -> Unit,
    onCoachingAction: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        FocusRing(
            focusX = state.focusX,
            focusY = state.focusY,
            isFocusing = state.isFocusing,
            focusSuccess = state.focusSuccess
        )

        AnimatedVisibility(
            visible = state.isHudVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                CrosshairAndGrid(gridMode = state.gridMode)

                SceneReadout(
                    sceneLabel = state.sceneLabel,
                    confidence = state.sceneConfidence,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 20.dp, top = 60.dp)
                )

                SettingsReadout(
                    aperture = state.cameraAperture,
                    settings = state.settings,
                    mode = state.mode,
                    actualIso = state.actualIso,
                    actualShutterNs = state.actualShutterSpeedNs,
                    actualColorTemperature = state.actualColorTemperature,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 20.dp, top = 60.dp)
                )

                if (state.isLowLight || state.isHdrActive) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 60.dp)
                    ) {
                        if (state.isHdrActive) {
                            Text(
                                text = "HDR",
                                color = HudColors.accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .background(HudColors.surfaceGlass, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        if (state.isLowLight) {
                            Text(
                                text = "NIGHT",
                                color = HudColors.accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .background(HudColors.surfaceGlass, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                if (state.aeAfLocked) {
                    Text(
                        text = "AE/AF LOCK",
                        color = HudColors.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 90.dp)
                            .background(HudColors.surfaceGlass, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                if (state.faceCount > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 20.dp, top = 90.dp)
                            .background(HudColors.surfaceGlass, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "🙂",
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${state.faceCount}",
                            color = HudColors.accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                if (state.beautyLevel > 0 && state.isFrontCamera) {
                    Text(
                        text = "BEAUTY ${"●".repeat(state.beautyLevel)}${"○".repeat(3 - state.beautyLevel)}",
                        color = HudColors.accent.copy(alpha = 0.6f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 20.dp, top = 110.dp)
                            .background(HudColors.surfaceGlass, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                LevelIndicator(
                    angle = state.levelAngle,
                    modifier = Modifier.align(Alignment.Center)
                )

                PitchIndicator(
                    pitchAngle = state.pitchAngle,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp)
                )

                ZoomBar(
                    zoomRatio = state.zoomRatio,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = 80.dp)
                )

                val cloudCoaching = state.cloudCoachingText
                val coaching = cloudCoaching ?: state.coachingText
                val arrowStr = if (cloudCoaching != null) state.cloudCoachingArrow else state.coachingArrow
                if (coaching != null) {
                    val arrow = try {
                        ArrowDirection.valueOf(arrowStr)
                    } catch (_: Exception) {
                        ArrowDirection.NONE
                    }
                    CoachingDirective(
                        text = coaching,
                        arrowDirection = arrow,
                        onDismiss = onCoachingDismiss,
                        actionLabel = state.coachingActionLabel,
                        onAction = if (state.coachingActionLabel != null) onCoachingAction else null,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 250.dp)
                    )
                }
            }
        }
    }
}
