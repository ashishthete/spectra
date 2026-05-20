package com.spectra.camera

import com.spectra.camera.gpu.GpuMertensFusion
import org.junit.Assert.assertNull
import org.junit.Test

class GpuMertensFusionTest {

    @Test
    fun `fuseFromPixels returns null for 2-frame input`() {
        val frame = IntArray(16) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }
        assertNull(GpuMertensFusion.fuseFromPixels(null, listOf(frame, frame), 4, 4))
    }

    @Test
    fun `fuseFromPixels returns null for 4-frame input`() {
        val frame = IntArray(16) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }
        assertNull(GpuMertensFusion.fuseFromPixels(null, listOf(frame, frame, frame, frame), 4, 4))
    }

    @Test
    fun `fuse returns null with null GPU context`() {
        val frame = FloatArray(48) { 128f }
        assertNull(GpuMertensFusion.fuse(null, listOf(frame, frame, frame), 4, 4))
    }

    @Test
    fun `fuseFromPixels returns null with null GPU context`() {
        val n = 16
        val frame = IntArray(n) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }
        assertNull(GpuMertensFusion.fuseFromPixels(null, listOf(frame, frame, frame), 4, 4))
    }
}
