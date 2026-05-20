package com.spectra.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.provider.MediaStore
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
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.LifecycleOwner
import com.spectra.core.model.LensId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    val capabilityMatrix: CameraCapabilityMatrix by lazy {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        CameraCapabilityMatrix(cm)
    }

    private val _videoEvent = MutableSharedFlow<VideoRecordEvent>(extraBufferCapacity = 1)
    val videoEvent: SharedFlow<VideoRecordEvent> = _videoEvent.asSharedFlow()

    private val _activeLens = MutableStateFlow(LensId.MAIN)
    val activeLens: StateFlow<LensId> = _activeLens.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private var _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    private val _sensorMetadata = MutableStateFlow(SensorMetadata())
    val sensorMetadata: StateFlow<SensorMetadata> = _sensorMetadata.asStateFlow()

    private var captureMegapixels: Int = 12

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
            val aperture = result.get(CaptureResult.LENS_APERTURE) ?: 1.7f
            val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
            val ctK = if (gains != null) {
                val rGain = gains.red
                val bGain = gains.blue
                estimateCtFromGains(rGain, bGain)
            } else 0
            val lux = if (iso > 0 && exposureNs > 0) {
                val exposureSec = exposureNs / 1_000_000_000.0
                val nSquared = (aperture * aperture).toDouble()
                val ev100 = kotlin.math.log2(nSquared * 100.0 / (iso * exposureSec))
                (2.5 * Math.pow(2.0, ev100)).toFloat().coerceIn(0f, 200_000f)
            } else -1f
            _sensorMetadata.value = SensorMetadata(iso, exposureNs, focusDiopters, ctK, lux)
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
            LensId.ULTRAWIDE,
            LensId.MAIN,
            LensId.TELEPHOTO_3X,
            LensId.TELEPHOTO_5X
        )
        val currentIdx = steps.indexOf(_activeLens.value)
        val nextIdx = (currentIdx + 1) % steps.size
        val nextLens = steps[nextIdx]
        _activeLens.value = nextLens
        bindCamera(nextLens)
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

    private val _focusConfidence = MutableStateFlow(1f)
    val focusConfidence: StateFlow<Float> = _focusConfidence.asStateFlow()

    private val _eyeFocusActive = MutableStateFlow(false)
    val eyeFocusActive: StateFlow<Boolean> = _eyeFocusActive.asStateFlow()

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
        cam.cameraControl.startFocusAndMetering(action).addListener({
            try {
                _focusConfidence.value = 1f
            } catch (_: Exception) {
                _focusConfidence.value = 0.5f
            }
        }, java.util.concurrent.Executors.newSingleThreadExecutor())
    }

    fun focusOnEye(eyeX: Float, eyeY: Float) {
        if (_aeAfLocked.value) return
        val view = previewView ?: return
        val cam = camera ?: return
        val pixelX = eyeX * view.width
        val pixelY = eyeY * view.height
        val factory = view.meteringPointFactory
        val point = factory.createPoint(pixelX, pixelY)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
        _eyeFocusActive.value = true
        _focusConfidence.value = 1f
    }

    fun lockAeAf() {
        val view = previewView ?: return
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        if (w > 0 && h > 0) lockAeAf(w / 2f, h / 2f) else _aeAfLocked.value = true
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
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetResolution(Size(4032, 3024))
            .setTargetRotation(rotation)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
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

        val captureSize = try {
            capabilityMatrix.selectCaptureSize(cameraId ?: "0", captureMegapixels)
        } catch (_: Exception) {
            when (captureMegapixels) {
                200 -> Size(16320, 12240)
                50 -> Size(8160, 6120)
                else -> Size(4032, 3024)
            }
        }
        Log.d("SpectraCameraController", "Selected capture size: ${captureSize.width}x${captureSize.height} for ${captureMegapixels}MP")
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(if (captureMegapixels > 12) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetResolution(captureSize)
            .setTargetRotation(rotation)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .build()

        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)

        Camera2Interop.Extender(analysisBuilder)
            .setSessionCaptureCallback(metadataCallback)

        imageAnalysis = analysisBuilder.build()
            .also { it.setAnalyzer(analysisExecutor, frameProvider) }

        if (cameraId != null) {
            try {
                val canSupport = capabilityMatrix.canSupportStreamCombo(cameraId, needsRaw = false, needsAnalysis = true)
                if (!canSupport) {
                    Log.w("SpectraCamera", "Stream combo preview+still+analysis may not be supported on $cameraId")
                }
            } catch (_: Exception) {}
        }

        try {
            camera = provider.bindToLifecycle(owner, cameraSelector, preview, imageCapture, imageAnalysis)
            updateZoomBounds()
            _isReady.value = true
            Log.d("SpectraCamera", "bindCamera: success, capture=${captureSize.width}x${captureSize.height}")
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
    fun setCaptureResolution(megapixels: Int) {
        captureMegapixels = megapixels
        if (_isFrontCamera.value) return
        val currentLens = _activeLens.value
        bindCamera(currentLens)
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun applySettings(settings: com.spectra.core.model.CameraSettings, manual: Boolean = false, motionLevel: Int = 0, semiAuto: Boolean = false, constraints: com.spectra.core.model.CameraConstraints? = null) {
        val cam = camera ?: return
        val cameraId = lensManager.getCameraId(_activeLens.value)
        val specs = lensManager.getSpecs(cameraId)
        when {
            manual -> settingsApplier.applyManual(cam, settings)
            constraints != null -> settingsApplier.applyAutoWithConstraints(
                cam, settings, constraints, motionLevel,
                aeCompensationStep = specs.aeCompensationStep,
                aeCompensationRange = specs.aeCompensationRange
            )
            semiAuto -> settingsApplier.applySemiAuto(
                cam, settings, specs.isoRange, specs.exposureTimeRange
            )
            else -> settingsApplier.applyAutoWithHints(
                cam, settings, motionLevel,
                aeCompensationStep = specs.aeCompensationStep,
                aeCompensationRange = specs.aeCompensationRange
            )
        }
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun applyFaceMetering(faceRects: List<android.graphics.RectF>) {
        val cam = camera ?: return
        val cameraId = lensManager.getCameraId(_activeLens.value)
        val specs = lensManager.getSpecs(cameraId)
        settingsApplier.applyFaceMetering(cam, faceRects, specs.sensorArrayWidth, specs.sensorArrayHeight)
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun resetToAuto() {
        val cam = camera ?: return
        settingsApplier.applyAuto(cam)
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun applyBracketExposure(exposureNs: Long, iso: Int) {
        val cam = camera ?: return
        val settings = com.spectra.core.model.CameraSettings(
            iso = iso,
            shutterSpeedDenominator = if (exposureNs > 0) (1_000_000_000L / exposureNs).toInt().coerceIn(1, 32000) else 125,
            whiteBalanceKelvin = _sensorMetadata.value.colorTemperatureK.takeIf { it > 0 } ?: 5500
        )
        settingsApplier.applyManual(cam, settings)
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun setManualFocusDistance(distanceMeters: Float) {
        val cam = camera ?: return
        val camera2Control = androidx.camera.camera2.interop.Camera2CameraControl.from(cam.cameraControl)
        val builder = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
        if (distanceMeters > 0f) {
            builder.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_OFF
            )
            builder.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.LENS_FOCUS_DISTANCE,
                1f / distanceMeters
            )
        } else {
            builder.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )
        }
        camera2Control.captureRequestOptions = builder.build()
    }

    fun applyEvCompensation(evSteps: Int) {
        val cam = camera ?: return
        val cameraId = lensManager.getCameraId(_activeLens.value)
        val specs = lensManager.getSpecs(cameraId)
        try {
            cam.cameraControl.setExposureCompensationIndex(
                evSteps.coerceIn(specs.aeCompensationRange.lower, specs.aeCompensationRange.upper)
            )
        } catch (_: Exception) { }
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun bindForVideo() {
        val provider = cameraProvider ?: return
        val owner = lifecycleOwner ?: return
        val view = previewView ?: return

        provider.unbindAll()
        val rotation = view.display?.rotation ?: Surface.ROTATION_0
        val lens = _activeLens.value
        val cameraId = lensManager.getCameraId(lens)

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

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetResolution(Size(4032, 3024))
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
            camera = provider.bindToLifecycle(owner, cameraSelector, preview, videoCapture, imageCapture, imageAnalysis)
            enableVideoStabilization()
            updateZoomBounds()
            _isReady.value = true
            Log.d("SpectraCamera", "bindForVideo: success")
        } catch (e: Exception) {
            Log.e("SpectraCamera", "bindForVideo: failed, trying without analysis", e)
            try {
                camera = provider.bindToLifecycle(owner, cameraSelector, preview, videoCapture, imageCapture)
                enableVideoStabilization()
                updateZoomBounds()
                _isReady.value = true
            } catch (e2: Exception) {
                Log.e("SpectraCamera", "bindForVideo: failed completely", e2)
            }
        }
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun enableVideoStabilization() {
        val cam = camera ?: return
        val camera2Control = androidx.camera.camera2.interop.Camera2CameraControl.from(cam.cameraControl)
        val builder = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
        builder.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
        )
        camera2Control.captureRequestOptions = builder.build()
    }

    @androidx.annotation.OptIn(androidx.camera.video.ExperimentalPersistentRecording::class)
    fun startRecording(): Boolean {
        val vc = videoCapture ?: return false

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "SPECTRA_${System.currentTimeMillis()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Spectra")
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        val hasAudio = PermissionChecker.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PermissionChecker.PERMISSION_GRANTED

        val pendingRecording = vc.output
            .prepareRecording(context, outputOptions)
            .let { if (hasAudio) it.withAudioEnabled() else it }

        activeRecording = pendingRecording.start(ContextCompat.getMainExecutor(context)) { event ->
            _videoEvent.tryEmit(event)
        }

        Log.d("SpectraCamera", "Recording started, audio=$hasAudio")
        return true
    }

    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
        Log.d("SpectraCamera", "Recording stopped")
    }

    fun isRecordingActive(): Boolean = activeRecording != null

    fun rebindCamera() {
        if (_isFrontCamera.value) {
            bindFrontCamera()
        } else {
            bindCamera(_activeLens.value)
        }
    }

    private var rawCaptureHelper: RawCaptureHelper? = null

    fun supportsRaw(): Boolean {
        val cameraId = lensManager.getCameraId(_activeLens.value) ?: return false
        if (rawCaptureHelper == null) rawCaptureHelper = RawCaptureHelper(context)
        return rawCaptureHelper?.supportsRaw(cameraId) == true
    }

    suspend fun captureDng(): String {
        val cameraId = lensManager.getCameraId(_activeLens.value)
            ?: throw IllegalStateException("No camera ID for active lens")
        if (rawCaptureHelper == null) rawCaptureHelper = RawCaptureHelper(context)
        cameraProvider?.unbindAll()
        return try {
            rawCaptureHelper!!.captureDng(cameraId)
        } finally {
            val lo = lifecycleOwner
            val pv = previewView
            if (lo != null && pv != null) {
                initialize(lo, pv)
            }
        }
    }

    fun release() {
        orientationListener.disable()
        cameraProvider?.unbindAll()
        rawCaptureHelper?.release()
        rawCaptureHelper = null
        _isReady.value = false
    }

    private fun estimateCtFromGains(rGain: Float, bGain: Float): Int =
        ColorTemperatureEstimator.fromGainRatio(rGain, bGain)
}
