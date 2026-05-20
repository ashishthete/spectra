package com.spectra.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RawCaptureHelper(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val handlerThread = HandlerThread("RawCapture").also { it.start() }
    private val handler = Handler(handlerThread.looper)

    fun supportsRaw(cameraId: String): Boolean {
        return try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: return false
            val hasRaw = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
            if (hasRaw) {
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
                rawSizes != null && rawSizes.isNotEmpty()
            } else false
        } catch (e: Exception) {
            Log.w(TAG, "RAW support check failed: ${e.message}")
            false
        }
    }

    fun getRawSize(cameraId: String): Size? {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = map.getOutputSizes(ImageFormat.RAW_SENSOR) ?: return null
        return sizes.maxByOrNull { it.width * it.height }
    }

    @Suppress("MissingPermission")
    suspend fun captureDng(cameraId: String): String = suspendCancellableCoroutine { cont ->
        try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val rawSize = getRawSize(cameraId)
            if (rawSize == null) {
                cont.resumeWithException(IllegalStateException("No RAW sizes available"))
                return@suspendCancellableCoroutine
            }

            val imageReader = ImageReader.newInstance(
                rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2
            )

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    val surfaces = listOf(imageReader.surface)
                    device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                addTarget(imageReader.surface)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }.build()

                            session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureCompleted(
                                    session: CameraCaptureSession,
                                    request: CaptureRequest,
                                    result: TotalCaptureResult
                                ) {
                                    val image = imageReader.acquireLatestImage()
                                    if (image != null) {
                                        try {
                                            val uri = saveDng(chars, result, image, rawSize)
                                            if (cont.isActive) cont.resume(uri)
                                        } catch (e: Exception) {
                                            if (cont.isActive) cont.resumeWithException(e)
                                        } finally {
                                            image.close()
                                        }
                                    } else {
                                        if (cont.isActive) cont.resumeWithException(
                                            IllegalStateException("No image from RAW capture")
                                        )
                                    }
                                    session.close()
                                    device.close()
                                    imageReader.close()
                                }
                            }, handler)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            device.close()
                            imageReader.close()
                            if (cont.isActive) cont.resumeWithException(
                                IllegalStateException("RAW session config failed")
                            )
                        }
                    }, handler)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    imageReader.close()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    imageReader.close()
                    if (cont.isActive) cont.resumeWithException(
                        IllegalStateException("Camera error: $error")
                    )
                }
            }, handler)
        } catch (e: Exception) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    private fun saveDng(
        chars: CameraCharacteristics,
        result: TotalCaptureResult,
        image: android.media.Image,
        size: Size
    ): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "SPECTRA_$timestamp.dng")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Spectra/RAW")
        }

        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
        ) ?: throw IllegalStateException("Failed to create DNG MediaStore entry")

        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            val dngCreator = DngCreator(chars, result)
            dngCreator.setDescription("SPECTRA RAW")
            dngCreator.writeImage(outputStream, image)
            dngCreator.close()
        }

        Log.d(TAG, "DNG saved: $uri (${size.width}x${size.height})")
        return uri.toString()
    }

    fun release() {
        handlerThread.quitSafely()
    }

    companion object {
        private const val TAG = "RawCaptureHelper"
    }
}
