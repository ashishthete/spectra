package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import com.spectra.camera.ZslRingBuffer.ZslFrame
import org.junit.Test

class ZslRingBufferTest {

    private fun frame(
        timestampNs: Long,
        iso: Int = 100,
        exposureNs: Long = 10_000_000L,
        width: Int = 4000,
        height: Int = 3000
    ) = ZslFrame(
        timestampNs = timestampNs,
        iso = iso,
        exposureNs = exposureNs,
        jpegBytes = byteArrayOf(1, 2, 3),
        yuvBytes = null,
        width = width,
        height = height
    )

    @Test
    fun `push and getLatest returns the last pushed frame`() {
        val buffer = ZslRingBuffer(capacity = 5)
        val f1 = frame(timestampNs = 1_000_000L)
        val f2 = frame(timestampNs = 2_000_000L)

        buffer.push(f1)
        buffer.push(f2)

        assertThat(buffer.getLatest()).isEqualTo(f2)
    }

    @Test
    fun `ring buffer wraps around correctly at capacity`() {
        val buffer = ZslRingBuffer(capacity = 5)
        val frames = (1..7).map { frame(timestampNs = it * 1_000_000L) }

        frames.forEach { buffer.push(it) }

        // After pushing 7 frames into a capacity-5 buffer, size stays at 5
        assertThat(buffer.size()).isEqualTo(5)
        // Latest should be the 7th frame
        assertThat(buffer.getLatest()).isEqualTo(frames[6])
        // Oldest two (frames[0], frames[1]) should be evicted;
        // getFramesWithin with a huge window should return exactly the 5 surviving frames
        val all = buffer.getFramesWithin(windowMs = Long.MAX_VALUE / 1_000_000)
        assertThat(all).hasSize(5)
        assertThat(all).containsExactly(frames[2], frames[3], frames[4], frames[5], frames[6])
            .inOrder()
    }

    @Test
    fun `getFramesWithin returns only frames within the time window`() {
        val buffer = ZslRingBuffer(capacity = 5)
        // Timestamps at 0ms, 50ms, 100ms, 150ms, 200ms (in nanoseconds)
        val f1 = frame(timestampNs = 0L)
        val f2 = frame(timestampNs = 50_000_000L)
        val f3 = frame(timestampNs = 100_000_000L)
        val f4 = frame(timestampNs = 150_000_000L)
        val f5 = frame(timestampNs = 200_000_000L)

        listOf(f1, f2, f3, f4, f5).forEach { buffer.push(it) }

        // Window of 100ms from latest (200ms) => cutoff at 100ms, so 100/150/200 qualify
        val result = buffer.getFramesWithin(windowMs = 100)
        assertThat(result).hasSize(3)
        assertThat(result).containsExactly(f3, f4, f5).inOrder()
    }

    @Test
    fun `getBestFrame returns the frame closest to target exposure`() {
        val buffer = ZslRingBuffer(capacity = 5)
        val f1 = frame(timestampNs = 1_000_000L, exposureNs = 5_000_000L)
        val f2 = frame(timestampNs = 2_000_000L, exposureNs = 10_000_000L)
        val f3 = frame(timestampNs = 3_000_000L, exposureNs = 20_000_000L)

        listOf(f1, f2, f3).forEach { buffer.push(it) }

        // Target exposure 9ms — closest is f2 at 10ms
        val best = buffer.getBestFrame(targetExposureNs = 9_000_000L)
        assertThat(best).isEqualTo(f2)
    }

    @Test
    fun `getBestFrame prefers recent frame when exposures are equal`() {
        val buffer = ZslRingBuffer(capacity = 5)
        val f1 = frame(timestampNs = 1_000_000L, exposureNs = 10_000_000L)
        val f2 = frame(timestampNs = 2_000_000L, exposureNs = 10_000_000L)

        listOf(f1, f2).forEach { buffer.push(it) }

        // Both have identical exposure diff, but f2 is more recent (lower age penalty)
        val best = buffer.getBestFrame(targetExposureNs = 10_000_000L)
        assertThat(best).isEqualTo(f2)
    }

    @Test
    fun `clear empties the buffer`() {
        val buffer = ZslRingBuffer(capacity = 5)
        buffer.push(frame(timestampNs = 1_000_000L))
        buffer.push(frame(timestampNs = 2_000_000L))

        buffer.clear()

        assertThat(buffer.size()).isEqualTo(0)
        assertThat(buffer.getLatest()).isNull()
    }

    @Test
    fun `size tracks count correctly`() {
        val buffer = ZslRingBuffer(capacity = 5)
        assertThat(buffer.size()).isEqualTo(0)

        buffer.push(frame(timestampNs = 1_000_000L))
        assertThat(buffer.size()).isEqualTo(1)

        buffer.push(frame(timestampNs = 2_000_000L))
        assertThat(buffer.size()).isEqualTo(2)

        buffer.push(frame(timestampNs = 3_000_000L))
        assertThat(buffer.size()).isEqualTo(3)

        // Push beyond capacity
        buffer.push(frame(timestampNs = 4_000_000L))
        buffer.push(frame(timestampNs = 5_000_000L))
        assertThat(buffer.size()).isEqualTo(5)

        buffer.push(frame(timestampNs = 6_000_000L))
        assertThat(buffer.size()).isEqualTo(5)  // capped at capacity
    }

    @Test
    fun `getLatest returns null on empty buffer`() {
        val buffer = ZslRingBuffer(capacity = 5)
        assertThat(buffer.getLatest()).isNull()
    }

    @Test
    fun `getFramesWithin returns empty list on empty buffer`() {
        val buffer = ZslRingBuffer(capacity = 5)
        assertThat(buffer.getFramesWithin(windowMs = 100)).isEmpty()
    }

    @Test
    fun `getBestFrame returns null on empty buffer`() {
        val buffer = ZslRingBuffer(capacity = 5)
        assertThat(buffer.getBestFrame(targetExposureNs = 10_000_000L)).isNull()
    }

    @Test
    fun `isFull returns true only when at capacity`() {
        val buffer = ZslRingBuffer(capacity = 3)
        assertThat(buffer.isFull()).isFalse()

        buffer.push(frame(timestampNs = 1_000_000L))
        buffer.push(frame(timestampNs = 2_000_000L))
        assertThat(buffer.isFull()).isFalse()

        buffer.push(frame(timestampNs = 3_000_000L))
        assertThat(buffer.isFull()).isTrue()
    }
}
