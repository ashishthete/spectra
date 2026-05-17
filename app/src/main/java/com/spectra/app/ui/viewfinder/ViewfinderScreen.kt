package com.spectra.app.ui.viewfinder

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.spectra.app.ui.controls.CaptureControls
import com.spectra.app.ui.controls.ModeSelector
import com.spectra.app.ui.pro.ProModePanel
import com.spectra.core.model.CameraMode
import com.spectra.app.ui.hud.HudOverlay
import com.spectra.app.ui.theme.HudColors
import com.spectra.app.ui.tips.ReferenceCard
import com.spectra.app.viewmodel.CameraViewModel

@Composable
fun ViewfinderScreen(
    viewModel: CameraViewModel = hiltViewModel()
) {
    val hudState by viewModel.hudState.collectAsState()
    val currentTip by viewModel.currentTip.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HudColors.background)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { viewModel.toggleHud() }
                )
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { previewView ->
                    previewView.implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                    viewModel.cameraController.initialize(lifecycleOwner, previewView)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        HudOverlay(
            state = hudState,
            onCoachingDismiss = { },
            onTipsClick = { viewModel.showReferenceCard() }
        )

        ModeSelector(
            currentMode = hudState.mode,
            onModeSelected = { viewModel.setMode(it) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp)
        )

        ProModePanel(
            isVisible = hudState.mode == CameraMode.PRO,
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
                .padding(bottom = 130.dp)
        )

        CaptureControls(
            activeLens = hudState.activeLens,
            lastCapturedUri = hudState.lastCapturedUri,
            isFrontCamera = hudState.isFrontCamera,
            onShutterTap = { viewModel.capturePhoto() },
            onBurstStart = { },
            onBurstEnd = { },
            onLensCycle = { viewModel.cycleLens() },
            onGalleryClick = {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    type = "image/*"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try { context.startActivity(intent) } catch (_: Exception) { }
            },
            onFlipCamera = { viewModel.flipCamera() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        )

        val tip = currentTip
        if (tip != null) {
            ReferenceCard(
                tip = tip,
                isVisible = hudState.showReferenceCard,
                onDismiss = { viewModel.dismissReferenceCard() }
            )
        }
    }
}
