package com.spectra.app.ui.viewfinder

import android.content.Intent
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.spectra.app.ui.controls.BeautyToggle
import com.spectra.app.ui.controls.CaptureControls
import com.spectra.app.ui.controls.ModeSelector
import com.spectra.app.ui.controls.PresetSelector
import com.spectra.app.ui.controls.StyleSelector
import com.spectra.app.ui.controls.TopControlBar
import com.spectra.app.ui.hud.AspectRatioOverlay
import com.spectra.app.ui.hud.BeautyOverlay
import com.spectra.app.ui.hud.CaptureFlash
import com.spectra.app.ui.hud.HudOverlay
import com.spectra.app.ui.hud.ReviewOverlay
import com.spectra.app.ui.pro.ProModePanel
import com.spectra.app.ui.theme.HudColors
import com.spectra.app.ui.tips.ReferenceCard
import com.spectra.app.viewmodel.CameraViewModel
import com.spectra.core.model.CameraMode

@Composable
fun ViewfinderScreen(
    viewModel: CameraViewModel = hiltViewModel()
) {
    val hudState by viewModel.hudState.collectAsState()
    val currentTip by viewModel.currentTip.collectAsState()
    val cameraReady by viewModel.cameraController.isReady.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var lastZoomRatio by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(Unit) {
        viewModel.toastMessage.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HudColors.background)
    ) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { previewView ->
                    previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    viewModel.cameraController.initialize(lifecycleOwner, previewView)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        lastZoomRatio = (lastZoomRatio * zoom).coerceIn(1f, hudState.maxZoomRatio)
                        viewModel.setZoom(lastZoomRatio)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            viewModel.tapToFocus(offset.x, offset.y)
                        },
                        onDoubleTap = { viewModel.toggleHud() },
                        onLongPress = { offset ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.longPressToLock(offset.x, offset.y)
                        }
                    )
                }
        )

        BeautyOverlay(beautyLevel = hudState.beautyLevel)

        AspectRatioOverlay(aspectRatio = hudState.aspectRatio)

        CaptureFlash(visible = hudState.showCaptureFlash)

        HudOverlay(
            state = hudState,
            onCoachingDismiss = { viewModel.dismissCoaching() },
            onTipsClick = { viewModel.showReferenceCard() }
        )

        TopControlBar(
            flashMode = hudState.flashMode,
            timerSeconds = hudState.timerSeconds,
            aspectRatio = hudState.aspectRatio,
            onFlashToggle = { viewModel.toggleFlash() },
            onTimerToggle = { viewModel.toggleTimer() },
            onAspectToggle = { viewModel.toggleAspectRatio() },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
        )

        BeautyToggle(
            beautyLevel = hudState.beautyLevel,
            isVisible = hudState.isFrontCamera,
            onToggle = { viewModel.cycleBeauty() },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp, top = 60.dp)
        )

        if (hudState.timerCountdown > 0) {
            LaunchedEffect(hudState.timerCountdown) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            Text(
                text = "${hudState.timerCountdown}",
                color = HudColors.accent,
                fontSize = 72.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.SansSerif,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(HudColors.surfaceGlass, RoundedCornerShape(20.dp))
                    .padding(horizontal = 32.dp, vertical = 16.dp)
            )
        }

        ProModePanel(
            isVisible = hudState.preset == com.spectra.core.model.CameraPreset.PRO,
            settings = hudState.settings,
            aiSettings = hudState.aiRecommendedSettings,
            isManualOverride = hudState.isManualOverride,
            onIsoChange = { viewModel.updateProSetting(iso = it) },
            onShutterChange = { viewModel.updateProSetting(shutterSpeedDenominator = it) },
            onWbChange = { viewModel.updateProSetting(whiteBalanceKelvin = it) },
            onEvChange = { viewModel.updateProSetting(exposureCompensation = it) },
            onFocusChange = { viewModel.updateProSetting(focusDistance = it) },
            onSnapToAi = { viewModel.snapToAiRecommendation() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 200.dp)
        )

        PresetSelector(
            currentPreset = hudState.preset,
            onPresetSelected = { viewModel.setPreset(it) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 140.dp)
        )

        StyleSelector(
            currentStyle = hudState.photoStyle,
            onStyleSelected = { viewModel.setPhotoStyle(it) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 108.dp)
        )

        CaptureControls(
            activeLens = hudState.activeLens,
            lastCapturedUri = hudState.lastCapturedUri,
            isFrontCamera = hudState.isFrontCamera,
            onShutterTap = { viewModel.capturePhoto() },
            onBurstStart = { viewModel.startBurst() },
            onBurstEnd = { viewModel.stopBurst() },
            onLensCycle = { viewModel.cycleLens() },
            onGalleryClick = {
                val uri = hudState.lastCapturedUri
                val intent = if (uri != null) {
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(android.net.Uri.parse(uri), "image/*")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                } else {
                    Intent(Intent.ACTION_VIEW).apply {
                        type = "image/*"
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                }
                try { context.startActivity(intent) } catch (_: Exception) { }
            },
            onFlipCamera = { viewModel.flipCamera() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        )

        AnimatedVisibility(
            visible = !cameraReady,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Text(
                text = "Starting camera...",
                color = HudColors.textSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = FontFamily.SansSerif
            )
        }

        val lensHint = hudState.lensHint
        if (lensHint != null && !hudState.showReview) {
            Text(
                text = lensHint,
                color = HudColors.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 20.dp, bottom = 70.dp)
                    .background(HudColors.surfaceGlass, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        val tip = currentTip
        if (tip != null) {
            ReferenceCard(
                tip = tip,
                isVisible = hudState.showReferenceCard,
                onDismiss = { viewModel.dismissReferenceCard() }
            )
        }

        ReviewOverlay(
            imageUri = hudState.reviewUri,
            isVisible = hudState.showReview,
            onKeep = { viewModel.dismissReview() },
            onShare = {
                viewModel.shareReviewPhoto()
                val uri = hudState.reviewUri
                if (uri != null) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/*"
                        putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse(uri))
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    try { context.startActivity(Intent.createChooser(intent, "Share photo")) } catch (_: Exception) { }
                }
            },
            onDelete = { viewModel.deleteReviewPhoto() }
        )
    }
}
