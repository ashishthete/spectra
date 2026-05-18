package com.spectra.camera.gpu

import android.opengl.GLES31
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wraps a single GLES 3.1 compute shader program.
 *
 * Typical usage:
 * ```kotlin
 * val shader = ComputeShader(glslSource)
 * shader.setBuffer(0, inputFloats)
 * shader.setBuffer(1, outputFloats)
 * shader.setUniform("uWidth",  width)
 * shader.setUniform("uHeight", height)
 * shader.dispatch(groupsX, groupsY)
 * shader.readBuffer(1, outputFloats)
 * shader.release()
 * ```
 *
 * All methods assume the owning [GpuContext] is already current on the
 * calling thread.  Error handling is defensive: compilation/link failures
 * leave [programId] as 0 and subsequent GL calls become no-ops guarded by
 * the validity check.
 */
class ComputeShader(source: String) {

    /** GLES program object.  0 if compilation/linking failed. */
    val programId: Int

    /**
     * Map from SSBO binding point → GL buffer object name.
     * Tracked here so [release] can delete every buffer we allocate.
     */
    private val ssbos = mutableMapOf<Int, Int>()

    // -------------------------------------------------------------------------
    // Initialisation — compile & link
    // -------------------------------------------------------------------------

    init {
        programId = compileAndLink(source)
        if (programId == 0) {
            Log.e(TAG, "ComputeShader: program creation failed — GPU path disabled for this shader")
        } else {
            Log.d(TAG, "ComputeShader: program $programId created successfully")
        }
    }

    // -------------------------------------------------------------------------
    // Buffer helpers
    // -------------------------------------------------------------------------

    /**
     * Upload [data] to an SSBO bound at [binding].
     *
     * If no buffer object exists for this binding yet, one is created and
     * cached.  Subsequent calls with the same binding re-upload the data
     * (useful for updating uniform-like small buffers between dispatches).
     */
    fun setBuffer(binding: Int, data: FloatArray) {
        if (programId == 0) return

        val ssboId = ssbos.getOrPut(binding) {
            val ids = IntArray(1)
            GLES31.glGenBuffers(1, ids, 0)
            checkGlError("glGenBuffers binding=$binding")
            ids[0]
        }

        val byteBuffer = ByteBuffer
            .allocateDirect(data.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(data)
        byteBuffer.rewind()

        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssboId)
        GLES31.glBufferData(
            GLES31.GL_SHADER_STORAGE_BUFFER,
            data.size * Float.SIZE_BYTES,
            byteBuffer,
            GLES31.GL_DYNAMIC_COPY   // written by CPU, read by GPU, then read back by CPU
        )
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, binding, ssboId)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
        checkGlError("setBuffer binding=$binding size=${data.size}")
    }

    // -------------------------------------------------------------------------
    // Uniform setters
    // -------------------------------------------------------------------------

    /** Set an integer uniform by name. */
    fun setUniform(name: String, value: Int) {
        if (programId == 0) return
        val loc = GLES31.glGetUniformLocation(programId, name)
        if (loc < 0) {
            Log.w(TAG, "setUniform: uniform '$name' not found in program $programId")
            return
        }
        GLES31.glUseProgram(programId)
        GLES31.glUniform1i(loc, value)
        checkGlError("setUniform $name=$value")
    }

    /** Set a float uniform by name. */
    fun setUniformFloat(name: String, value: Float) {
        if (programId == 0) return
        val loc = GLES31.glGetUniformLocation(programId, name)
        if (loc < 0) {
            Log.w(TAG, "setUniformFloat: uniform '$name' not found in program $programId")
            return
        }
        GLES31.glUseProgram(programId)
        GLES31.glUniform1f(loc, value)
        checkGlError("setUniformFloat $name=$value")
    }

    // -------------------------------------------------------------------------
    // Dispatch
    // -------------------------------------------------------------------------

    /**
     * Dispatch the compute shader with the given number of work groups.
     *
     * Inserts a [GLES31.GL_SHADER_STORAGE_BARRIER_BIT] memory barrier after the
     * dispatch so that subsequent [readBuffer] calls see the completed results.
     *
     * @param groupsX Number of work groups in X (≥ 1).
     * @param groupsY Number of work groups in Y (≥ 1).
     * @param groupsZ Number of work groups in Z (default 1 for 2-D work).
     */
    fun dispatch(groupsX: Int, groupsY: Int, groupsZ: Int = 1) {
        if (programId == 0) return
        GLES31.glUseProgram(programId)
        GLES31.glDispatchCompute(groupsX, groupsY, groupsZ)
        checkGlError("glDispatchCompute ${groupsX}x${groupsY}x${groupsZ}")

        // Memory barrier: ensure SSBO writes are visible before any subsequent read.
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        checkGlError("glMemoryBarrier")
    }

    // -------------------------------------------------------------------------
    // Read-back
    // -------------------------------------------------------------------------

    /**
     * Download the contents of the SSBO at [binding] into [output].
     *
     * [output] must be pre-allocated with the correct size.
     * The buffer must have been previously uploaded via [setBuffer].
     */
    fun readBuffer(binding: Int, output: FloatArray) {
        if (programId == 0) return

        val ssboId = ssbos[binding]
        if (ssboId == null) {
            Log.w(TAG, "readBuffer: no SSBO at binding $binding")
            return
        }

        val byteBuffer = ByteBuffer
            .allocateDirect(output.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())

        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssboId)
        val mapped = GLES31.glMapBufferRange(
            GLES31.GL_SHADER_STORAGE_BUFFER,
            0,
            output.size * Float.SIZE_BYTES,
            GLES31.GL_MAP_READ_BIT
        ) as? ByteBuffer
        if (mapped != null) {
            mapped.order(ByteOrder.nativeOrder()).asFloatBuffer().get(output)
            GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
        } else {
            Log.w(TAG, "readBuffer: glMapBufferRange returned null for binding=$binding")
        }
        checkGlError("readBuffer binding=$binding size=${output.size}")
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    /**
     * Delete the GLES program and all SSBOs allocated by this instance.
     * Safe to call even if initialisation failed.
     */
    fun release() {
        if (ssbos.isNotEmpty()) {
            val ids = ssbos.values.toIntArray()
            GLES31.glDeleteBuffers(ids.size, ids, 0)
            ssbos.clear()
            Log.d(TAG, "release: deleted ${ids.size} SSBO(s)")
        }
        if (programId != 0) {
            GLES31.glDeleteProgram(programId)
            Log.d(TAG, "release: deleted program $programId")
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun compileAndLink(source: String): Int {
        // 1. Create shader object.
        val shaderId = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
        if (shaderId == 0) {
            Log.e(TAG, "compileAndLink: glCreateShader returned 0")
            return 0
        }

        // 2. Upload source and compile.
        GLES31.glShaderSource(shaderId, source)
        GLES31.glCompileShader(shaderId)

        val compileStatus = IntArray(1)
        GLES31.glGetShaderiv(shaderId, GLES31.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == GLES31.GL_FALSE) {
            val log = GLES31.glGetShaderInfoLog(shaderId)
            Log.e(TAG, "compileAndLink: compile error:\n$log")
            GLES31.glDeleteShader(shaderId)
            return 0
        }

        // 3. Create program and attach shader.
        val progId = GLES31.glCreateProgram()
        if (progId == 0) {
            Log.e(TAG, "compileAndLink: glCreateProgram returned 0")
            GLES31.glDeleteShader(shaderId)
            return 0
        }

        GLES31.glAttachShader(progId, shaderId)
        GLES31.glLinkProgram(progId)

        // Shader object no longer needed once linked.
        GLES31.glDetachShader(progId, shaderId)
        GLES31.glDeleteShader(shaderId)

        val linkStatus = IntArray(1)
        GLES31.glGetProgramiv(progId, GLES31.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == GLES31.GL_FALSE) {
            val log = GLES31.glGetProgramInfoLog(progId)
            Log.e(TAG, "compileAndLink: link error:\n$log")
            GLES31.glDeleteProgram(progId)
            return 0
        }

        return progId
    }

    private fun checkGlError(op: String) {
        val error = GLES31.glGetError()
        if (error != GLES31.GL_NO_ERROR) {
            Log.e(TAG, "$op: GL error 0x${error.toString(16)}")
        }
    }

    companion object {
        private const val TAG = "ComputeShader"
    }
}
