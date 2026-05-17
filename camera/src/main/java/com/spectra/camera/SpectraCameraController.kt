package com.spectra.camera

import android.content.Context
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.spectra.core.model.LensId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpectraCameraController @Inject constructor(
    @ApplicationContext private val context: Context,
    val lensManager: LensManager,
    val frameProvider: FrameProvider
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null

    private val _activeLens = MutableStateFlow(LensId.MAIN)
    val activeLens: StateFlow<LensId> = _activeLens.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private var _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    fun initialize(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        this.lifecycleOwner = lifecycleOwner
        this.previewView = previewView
        lensManager.initialize()

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindCamera(_activeLens.value)
        }, { it.run() })
    }

    fun switchLens(lens: LensId) {
        if (lens == _activeLens.value) return
        _activeLens.value = lens
        bindCamera(lens)
    }

    fun cycleLens() {
        val next = lensManager.getNextLens(_activeLens.value)
        switchLens(next)
    }

    fun flipCamera() {
        _isFrontCamera.value = !_isFrontCamera.value
        if (_isFrontCamera.value) {
            bindFrontCamera()
        } else {
            bindCamera(_activeLens.value)
        }
    }

    private fun bindFrontCamera() {
        val provider = cameraProvider ?: return
        val owner = lifecycleOwner ?: return
        val view = previewView ?: return

        val frontId = lensManager.getFrontCameraId() ?: return

        provider.unbindAll()

        val cameraSelector = CameraSelector.Builder()
            .addCameraFilter { cameras ->
                cameras.filter {
                    Camera2CameraInfo.from(it).cameraId == frontId
                }
            }
            .build()

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = view.surfaceProvider
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetRotation(Surface.ROTATION_0)
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer({ it.run() }, frameProvider) }

        camera = provider.bindToLifecycle(owner, cameraSelector, preview, imageCapture, imageAnalysis)
        _isReady.value = true
    }

    private fun bindCamera(lens: LensId) {
        val provider = cameraProvider ?: return
        val owner = lifecycleOwner ?: return
        val view = previewView ?: return

        provider.unbindAll()

        val cameraId = lensManager.getCameraId(lens) ?: return

        val cameraSelector = CameraSelector.Builder()
            .addCameraFilter { cameras ->
                cameras.filter {
                    Camera2CameraInfo.from(it).cameraId == cameraId
                }
            }
            .build()

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = view.surfaceProvider
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetRotation(Surface.ROTATION_0)
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer({ it.run() }, frameProvider) }

        camera = provider.bindToLifecycle(owner, cameraSelector, preview, imageCapture, imageAnalysis)
        _isReady.value = true
    }

    fun getImageCapture(): ImageCapture? = imageCapture

    fun release() {
        cameraProvider?.unbindAll()
        _isReady.value = false
    }
}
