package com.spectra.camera.gpu

import android.hardware.HardwareBuffer
import android.opengl.EGL14
import android.opengl.EGL15
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES31
import android.util.Log

/**
 * Manages an EGL context for GLES 3.1 compute shader execution.
 *
 * Creates a headless (pbuffer) context — no window surface is needed for
 * pure-compute workloads. Call [init] once before dispatching shaders, and
 * [release] when done.  All GPU operations degrade gracefully: if initialisation
 * fails, [isAvailable] returns false and callers should fall back to the CPU path.
 */
class GpuContext {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Initialise the EGL display, config, pbuffer surface and GLES 3.1 context.
     *
     * @return true on success; false if GLES 3.1 compute is not available on
     *         this device (caller should use the CPU fallback path).
     */
    fun init(): Boolean {
        // 1. Get the default display.
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.w(TAG, "init: eglGetDisplay failed")
            return false
        }

        // 2. Initialise EGL.
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.w(TAG, "init: eglInitialize failed — error ${EGL14.eglGetError()}")
            return false
        }
        Log.d(TAG, "init: EGL version ${version[0]}.${version[1]}")

        // 3. Choose a config that supports OpenGL ES 3.x.
        //    EGL_OPENGL_ES3_BIT_KHR = 0x40 (same value as EGLExt.EGL_OPENGL_ES3_BIT_KHR)
        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE,    EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE,        8,
            EGL14.EGL_GREEN_SIZE,      8,
            EGL14.EGL_BLUE_SIZE,       8,
            EGL14.EGL_ALPHA_SIZE,      8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
            || numConfigs[0] == 0 || configs[0] == null
        ) {
            Log.w(TAG, "init: eglChooseConfig failed — GLES 3.1 not available on this device")
            release()
            return false
        }

        // 4. Create a 1×1 pbuffer surface (no window required for compute).
        val pbufferAttribs = intArrayOf(
            EGL14.EGL_WIDTH,  1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], pbufferAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "init: eglCreatePbufferSurface failed — error ${EGL14.eglGetError()}")
            release()
            return false
        }

        // 5. Create a GLES 3.x context.
        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.w(TAG, "init: eglCreateContext failed — error ${EGL14.eglGetError()}")
            release()
            return false
        }

        // 6. Make current so we can issue GL commands.
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.w(TAG, "init: eglMakeCurrent failed — error ${EGL14.eglGetError()}")
            release()
            return false
        }

        // 7. Verify actual GLES version is at least 3.1.
        val glVersion = GLES31.glGetString(GLES31.GL_VERSION) ?: ""
        Log.d(TAG, "init: GL_VERSION = $glVersion")

        // Parse major.minor from "OpenGL ES 3.2 ..."
        val versionRegex = Regex("""OpenGL ES (\d+)\.(\d+)""")
        val match = versionRegex.find(glVersion)
        val major = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val minor = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
        if (major < 3 || (major == 3 && minor < 1)) {
            Log.w(TAG, "init: GLES $major.$minor < 3.1 — compute shaders not supported")
            release()
            return false
        }

        // 8. Quick sanity-check: confirm compute shader support is actually present
        //    by checking max work group sizes (will return > 0 on a real GLES 3.1+ device).
        val maxGroups = IntArray(1)
        GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 0, maxGroups, 0)
        if (maxGroups[0] == 0) {
            Log.w(TAG, "init: GL_MAX_COMPUTE_WORK_GROUP_COUNT == 0 — compute not supported")
            release()
            return false
        }

        Log.d(TAG, "init: GLES 3.1 compute context ready (max dispatch x=${maxGroups[0]})")
        return true
    }

    /** True once a valid context has been created. */
    val isAvailable: Boolean
        get() = eglContext != EGL14.EGL_NO_CONTEXT

    /** Bind this context on the calling thread. */
    fun makeCurrent() {
        if (!isAvailable) return
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.w(TAG, "makeCurrent: failed — error ${EGL14.eglGetError()}")
        }
    }

    /** Destroy the EGL surface, context and display. Safe to call multiple times. */
    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
        Log.d(TAG, "release: EGL context destroyed")
    }

    // -------------------------------------------------------------------------
    // HardwareBuffer zero-copy import (API 33+)
    // -------------------------------------------------------------------------

    /**
     * Import a [HardwareBuffer] (e.g. from an ImageReader with USAGE_GPU_SAMPLED_IMAGE)
     * as a GLES 2D texture, using the EGL_ANDROID_image_native_buffer extension.
     *
     * This enables a zero-copy camera → GPU path: the camera HAL writes directly
     * into the HardwareBuffer and the GPU reads it as a sampler2D.
     *
     * @return A positive GLES texture ID on success, -1 if the extension is not
     *         supported or the import fails.
     */
    fun importHardwareBuffer(hardwareBuffer: HardwareBuffer): Int {
        if (!isAvailable) {
            Log.w(TAG, "importHardwareBuffer: GPU context not available")
            return -1
        }

        // Check that the required EGL extension is present.
        val extensions = EGL14.eglQueryString(eglDisplay, EGL14.EGL_EXTENSIONS) ?: ""
        if (!extensions.contains("EGL_ANDROID_image_native_buffer") &&
            !extensions.contains("EGL_KHR_image_base")
        ) {
            Log.w(TAG, "importHardwareBuffer: EGL_ANDROID_image_native_buffer not supported")
            return -1
        }

        val EGL_NATIVE_BUFFER_ANDROID = 0x3140
        val nativeHandle = try {
            val method = HardwareBuffer::class.java.getMethod("toNativeHandle")
            method.invoke(hardwareBuffer) as Long
        } catch (e: Exception) {
            Log.w(TAG, "importHardwareBuffer: toNativeHandle not available on this API level")
            return -1
        }
        val eglImage = EGL15.eglCreateImage(
            eglDisplay,
            EGL14.EGL_NO_CONTEXT,
            EGL_NATIVE_BUFFER_ANDROID,
            nativeHandle,
            longArrayOf(EGL14.EGL_NONE.toLong()),
            0
        )
        if (eglImage == EGL15.EGL_NO_IMAGE) {
            Log.w(TAG, "importHardwareBuffer: eglCreateImage failed — error ${EGL14.eglGetError()}")
            return -1
        }

        val texIds = IntArray(1)
        GLES31.glGenTextures(1, texIds, 0)
        val texId = texIds[0]

        val GL_TEXTURE_EXTERNAL_OES = 0x8D65
        GLES31.glBindTexture(GL_TEXTURE_EXTERNAL_OES, texId)
        GLES31.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_LINEAR)
        GLES31.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_LINEAR)
        GLES31.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE)
        GLES31.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE)

        val glError = GLES31.glGetError()
        if (glError != GLES31.GL_NO_ERROR) {
            Log.w(TAG, "importHardwareBuffer: texture setup error 0x${glError.toString(16)}")
            GLES31.glDeleteTextures(1, texIds, 0)
            EGL15.eglDestroyImage(eglDisplay, eglImage)
            return -1
        }

        EGL15.eglDestroyImage(eglDisplay, eglImage)

        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, 0)
        Log.d(TAG, "importHardwareBuffer: texture $texId created from HardwareBuffer")
        return texId
    }

    companion object {
        private const val TAG = "GpuContext"
    }
}
