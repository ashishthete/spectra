package com.spectra.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spectra.camera.CaptureManager
import com.spectra.camera.SpectraCameraController
import com.spectra.core.model.CameraMode
import com.spectra.core.model.HudState
import com.spectra.core.model.LensId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    val cameraController: SpectraCameraController,
    private val captureManager: CaptureManager
) : ViewModel() {

    private val _hudState = MutableStateFlow(HudState())
    val hudState: StateFlow<HudState> = _hudState.asStateFlow()

    private val _captureInProgress = MutableStateFlow(false)
    val captureInProgress: StateFlow<Boolean> = _captureInProgress.asStateFlow()

    init {
        viewModelScope.launch {
            cameraController.activeLens.collect { lens ->
                _hudState.update { it.copy(
                    activeLens = lens,
                    lensMatchScores = LensId.entries.associateWith {
                        if (it == lens) 1.0f else 0f
                    }
                )}
            }
        }
    }

    fun cycleLens() {
        cameraController.cycleLens()
    }

    fun switchLens(lens: LensId) {
        cameraController.switchLens(lens)
    }

    fun setMode(mode: CameraMode) {
        _hudState.update { it.copy(mode = mode) }
    }

    fun toggleHud() {
        _hudState.update { it.copy(isHudVisible = !it.isHudVisible) }
    }

    fun capturePhoto() {
        val imageCapture = cameraController.getImageCapture() ?: return
        if (_captureInProgress.value) return

        viewModelScope.launch {
            _captureInProgress.value = true
            try {
                val uri = captureManager.capturePhoto(imageCapture)
                _hudState.update { it.copy(lastCapturedUri = uri) }
            } catch (_: Exception) {
            } finally {
                _captureInProgress.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cameraController.release()
    }
}
