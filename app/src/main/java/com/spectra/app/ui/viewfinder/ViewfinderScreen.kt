package com.spectra.app.ui.viewfinder

import android.content.Intent
import android.graphics.ColorMatrixColorFilter
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
import androidx.compose.ui.graphics.ColorMatrix
import android.graphics.ColorMatrix as AndroidColorMatrix
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.spectra.core.model.PhotoStyle
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
        val previewStyle = hudState.photoStyle
        val previewFront = hudState.isFrontCamera
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { previewView ->
                    previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    viewModel.cameraController.initialize(lifecycleOwner, previewView)
                }
            },
            update = { previewView ->
                val matrix = buildPreviewMatrix(previewStyle, previewFront)
                if (matrix != null) {
                    val androidMatrix = AndroidColorMatrix(matrix.values)
                    previewView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                    previewView.setLayerPaint(android.graphics.Paint().apply {
                        colorFilter = ColorMatrixColorFilter(androidMatrix)
                    })
                } else {
                    previewView.setLayerPaint(null)
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

private fun buildPreviewMatrix(style: PhotoStyle, isFrontCamera: Boolean): ColorMatrix? {
    val combined = AndroidColorMatrix()

    // Base enhancement (matches post-processing)
    val enhance = AndroidColorMatrix(floatArrayOf(
        1.08f, 0f, 0f, 0f, -10f,
        0f, 1.08f, 0f, 0f, -10f,
        0f, 0f, 1.08f, 0f, -10f,
        0f, 0f, 0f, 1f, 0f
    ))
    val satBoost = AndroidColorMatrix().apply { setSaturation(1.1f) }
    enhance.postConcat(satBoost)
    combined.postConcat(enhance)

    if (isFrontCamera) {
        combined.postConcat(AndroidColorMatrix(floatArrayOf(
            1.05f, 0.02f, 0f, 0f, 10f,
            0f, 1.03f, 0f, 0f, 6f,
            0f, 0f, 0.98f, 0f, -2f,
            0f, 0f, 0f, 1f, 0f
        )))
    }

    when (style) {
        PhotoStyle.VIVID -> {
            val sat = AndroidColorMatrix().apply { setSaturation(1.4f) }
            val contrast = AndroidColorMatrix(floatArrayOf(
                1.15f, 0f, 0f, 0f, -20f, 0f, 1.15f, 0f, 0f, -20f,
                0f, 0f, 1.15f, 0f, -20f, 0f, 0f, 0f, 1f, 0f
            ))
            sat.postConcat(contrast)
            combined.postConcat(sat)
        }
        PhotoStyle.WARM -> {
            val sat = AndroidColorMatrix().apply { setSaturation(0.95f) }
            val warm = AndroidColorMatrix(floatArrayOf(
                1.08f, 0.05f, 0f, 0f, 8f, 0f, 1.02f, 0f, 0f, 4f,
                0f, 0f, 0.92f, 0f, -5f, 0f, 0f, 0f, 1f, 0f
            ))
            sat.postConcat(warm)
            combined.postConcat(sat)
        }
        PhotoStyle.FILM -> {
            val sat = AndroidColorMatrix().apply { setSaturation(0.7f) }
            val lifted = AndroidColorMatrix(floatArrayOf(
                0.95f, 0.05f, 0.02f, 0f, 12f, 0.02f, 0.95f, 0.03f, 0f, 10f,
                0.03f, 0.03f, 0.90f, 0f, 18f, 0f, 0f, 0f, 1f, 0f
            ))
            sat.postConcat(lifted)
            combined.postConcat(sat)
        }
        PhotoStyle.CINEMATIC -> {
            val sat = AndroidColorMatrix().apply { setSaturation(0.85f) }
            val tealOrange = AndroidColorMatrix(floatArrayOf(
                1.1f, 0f, -0.05f, 0f, 5f, -0.02f, 1.0f, 0.05f, 0f, -3f,
                -0.05f, 0.05f, 1.12f, 0f, -8f, 0f, 0f, 0f, 1f, 0f
            ))
            sat.postConcat(tealOrange)
            combined.postConcat(sat)
        }
        PhotoStyle.NATURAL -> { }
    }

    return ColorMatrix(combined.array)
}
