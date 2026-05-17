package com.spectra.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.spectra.core.model.LensId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpectraCameraController @Inject constructor(
    @ApplicationContext private val context: Context,
    val lensManager: LensManager,
    val frameProvider: FrameProvider,
    val settingsApplier: Camera2SettingsApplier
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val _activeLens = MutableStateFlow(LensId.MAIN)
    val activeLens: StateFlow<LensId> = _activeLens.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private var _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    private val _sensorMetadata = MutableStateFlow(SensorMetadata())
    val sensorMetadata: StateFlow<SensorMetadata> = _sensorMetadata.asStateFlow()

    private val _zoomRatio = MutableStateFlow(1f)
    val zoomRatio: StateFlow<Float> = _zoomRatio.asStateFlow()

    private val _maxZoomRatio = MutableStateFlow(10f)
    val maxZoomRatio: StateFlow<Float> = _maxZoomRatio.asStateFlow()

    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            val rotation = when (orientation) {
                in 45..134 -> Surface.ROTATION_270
                in 135..224 -> Surface.ROTATION_180
                in 225..314 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
            imageCapture?.targetRotation = rotation
            imageAnalysis?.targetRotation = rotation
        }
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private val metadataCallback = object : CameraCaptureSession.CaptureCallback() {
        private var frameSkip = 0
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            frameSkip++
            if (frameSkip % 5 != 0) return
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
            _sensorMetadata.value = SensorMetadata(iso, exposureNs, focusDiopters)
        }
    }

    fun initialize(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        this.lifecycleOwner = lifecycleOwner
        this.previewView = previewView
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        lensManager.initialize()
        orientationListener.enable()

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            Log.d("SpectraCamera", "CameraProvider ready, binding camera")
            bindCamera(_activeLens.value)
        }, ContextCompat.getMainExecutor(context))
    }

    fun switchLens(lens: LensId) {
        if (lens == _activeLens.value) return
        _activeLens.value = lens
        bindCamera(lens)
    }

    fun cycleLens() {
        val steps = listOf(
            LensId.ULTRAWIDE to 0.6f,
            LensId.MAIN to 1f,
            LensId.TELEPHOTO_3X to 3f,
            LensId.TELEPHOTO_5X to 5f
        )
        val currentIdx = steps.indexOfFirst { it.first == _activeLens.value }
        val nextIdx = (currentIdx + 1) % steps.size
        val (nextLens, nextZoom) = steps[nextIdx]
        _activeLens.value = nextLens
        setZoomRatio(nextZoom)
    }

    fun flipCamera() {
        _isFrontCamera.value = !_isFrontCamera.value
        if (_isFrontCamera.value) {
            bindFrontCamera()
        } else {
            bindCamera(_activeLens.value)
        }
    }

    private val _aeAfLocked = MutableStateFlow(false)
    val aeAfLocked: StateFlow<Boolean> = _aeAfLocked.asStateFlow()

    fun tapToFocus(x: Float, y: Float) {
        if (_aeAfLocked.value) {
            unlockAeAf()
            return
        }
        val view = previewView ?: return
        val cam = camera ?: return
        val factory = view.meteringPointFactory
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point)
            .setAutoCancelDuration(4, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    fun focusOnFace(normalizedX: Float, normalizedY: Float) {
        if (_aeAfLocked.value) return
        val view = previewView ?: return
        val cam = camera ?: return
        val pixelX = normalizedX * view.width
        val pixelY = normalizedY * view.height
        val factory = view.meteringPointFactory
        val point = factory.createPoint(pixelX, pixelY)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    fun lockAeAf(x: Float, y: Float) {
        val view = previewView ?: return
        val cam = camera ?: return
        val factory = view.meteringPointFactory
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .disableAutoCancel()
            .build()
        cam.cameraControl.startFocusAndMetering(action)
        _aeAfLocked.value = true
    }

    fun unlockAeAf() {
        val cam = camera ?: return
        cam.cameraControl.cancelFocusAndMetering()
        _aeAfLocked.value = false
    }

    fun setZoomRatio(ratio: Float) {
        val cam = camera ?: return
        val max = _maxZoomRatio.value
        val clamped = ratio.coerceIn(1f, max)
        cam.cameraControl.setZoomRatio(clamped)
        _zoomRatio.value = clamped
    }

    fun setFlashMode(mode: Int) {
        imageCapture?.flashMode = mode
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun bindFrontCamera() {
        val provider = cameraProvider ?: return
        val owner = lifecycleOwner ?: return
        val view = previewView ?: return
        val frontId = lensManager.getFrontCameraId() ?: return

        provider.unbindAll()
        val rotation = view.display?.rotation ?: Surface.ROTATION_0

        val cameraSelector = CameraSelector.Builder()
            .addCameraFilter { cameras ->
                cameras.filter { Camera2CameraInfo.from(it).cameraId == frontId }
            }
            .build()

        val preview = Preview.Builder()
            .setTargetResolution(Size(1440, 1920))
            .setTargetRotation(rotation)
            .build()
            .also { it.surfaceProvider = view.surfaceProvider }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetRotation(rotation)
            .build()

        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)

        Camera2Interop.Extender(analysisBuilder)
            .setSessionCaptureCallback(metadataCallback)

        imageAnalysis = analysisBuilder.build()
            .also { it.setAnalyzer(analysisExecutor, frameProvider) }

        camera = provider.bindToLifecycle(owner, cameraSelector, preview, imageCapture, imageAnalysis)
        updateZoomBounds()
        _isReady.value = true
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun bindCamera(lens: LensId) {
        val provider = cameraProvider ?: run {
            Log.w("SpectraCamera", "bindCamera: provider is null")
            return
        }
        val owner = lifecycleOwner ?: run {
            Log.w("SpectraCamera", "bindCamera: lifecycleOwner is null")
            return
        }
        val view = previewView ?: run {
            Log.w("SpectraCamera", "bindCamera: previewView is null")
            return
        }

        provider.unbindAll()
        val rotation = view.display?.rotation ?: Surface.ROTATION_0
        val cameraId = lensManager.getCameraId(lens)
        Log.d("SpectraCamera", "bindCamera: lens=$lens, cameraId=$cameraId")

        val cameraSelector = if (cameraId != null) {
            CameraSelector.Builder()
                .addCameraFilter { cameras ->
                    cameras.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
                }
                .build()
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val preview = Preview.Builder()
            .setTargetResolution(Size(1440, 1920))
            .setTargetRotation(rotation)
            .build()
            .also { it.surfaceProvider = view.surfaceProvider }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetRotation(rotation)
            .build()

        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)

        Camera2Interop.Extender(analysisBuilder)
            .setSessionCaptureCallback(metadataCallback)

        imageAnalysis = analysisBuilder.build()
            .also { it.setAnalyzer(analysisExecutor, frameProvider) }

        try {
            camera = provider.bindToLifecycle(owner, cameraSelector, preview, imageCapture, imageAnalysis)
            updateZoomBounds()
            _isReady.value = true
            Log.d("SpectraCamera", "bindCamera: success")
        } catch (e: Exception) {
            Log.e("SpectraCamera", "bindCamera: failed", e)
        }
    }

    private fun updateZoomBounds() {
        val zoomState = camera?.cameraInfo?.zoomState?.value
        _maxZoomRatio.value = zoomState?.maxZoomRatio ?: 10f
        _zoomRatio.value = zoomState?.zoomRatio ?: 1f
    }

    fun getImageCapture(): ImageCapture? {
        val capture = imageCapture ?: return null
        val rotation = previewView?.display?.rotation ?: Surface.ROTATION_0
        capture.targetRotation = rotation
        return capture
    }

    fun getCamera(): Camera? = camera

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun applySettings(settings: com.spectra.core.model.CameraSettings, manual: Boolean = false) {
        val cam = camera ?: return
        if (manual) {
            settingsApplier.applyManual(cam, settings)
        } else {
            settingsApplier.applyAutoWithHints(cam, settings)
        }
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun resetToAuto() {
        val cam = camera ?: return
        settingsApplier.applyAuto(cam)
    }

    fun release() {
        orientationListener.disable()
        cameraProvider?.unbindAll()
        _isReady.value = false
    }
}
