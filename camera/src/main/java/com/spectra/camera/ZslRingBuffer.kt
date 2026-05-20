package com.spectra.camera

import android.media.Image
import android.util.Log
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ZslRingBuffer(private val capacity: Int = 5) {

    private val TAG = "ZslRingBuffer"

    data class ZslFrame(
        val timestampNs: Long,
        val iso: Int,
        val exposureNs: Long,
        val jpegBytes: ByteArray?,
        val yuvBytes: ByteArray?,
        val width: Int,
        val height: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ZslFrame) return false
            return timestampNs == other.timestampNs
        }
        override fun hashCode(): Int = timestampNs.hashCode()
    }

    private val buffer = arrayOfNulls<ZslFrame>(capacity)
    private var writeIndex = 0
    private var count = 0
    private val lock = ReentrantLock()

    fun push(frame: ZslFrame) = lock.withLock {
        buffer[writeIndex] = frame
        writeIndex = (writeIndex + 1) % capacity
        if (count < capacity) count++
    }

    fun getLatest(): ZslFrame? = lock.withLock {
        if (count == 0) return null
        val idx = (writeIndex - 1 + capacity) % capacity
        buffer[idx]
    }

    fun getFramesWithin(windowMs: Long): List<ZslFrame> = lock.withLock {
        if (count == 0) return emptyList()
        val latest = getLatest() ?: return emptyList()
        val cutoffNs = latest.timestampNs - windowMs * 1_000_000
        val frames = mutableListOf<ZslFrame>()
        for (i in 0 until count) {
            val idx = (writeIndex - 1 - i + capacity * 2) % capacity
            val frame = buffer[idx] ?: continue
            if (frame.timestampNs >= cutoffNs) frames.add(frame)
        }
        frames.sortBy { it.timestampNs }
        return frames
    }

    fun getLastFrames(numFrames: Int): List<ZslFrame> = lock.withLock {
        val n = kotlin.math.min(numFrames, count)
        val frames = mutableListOf<ZslFrame>()
        for (i in 0 until n) {
            val idx = (writeIndex - 1 - i + capacity * 2) % capacity
            val frame = buffer[idx] ?: continue
            frames.add(frame)
        }
        return frames.reversed()
    }

    fun getBestFrame(targetExposureNs: Long): ZslFrame? = lock.withLock {
        if (count == 0) return null
        var best: ZslFrame? = null
        var bestScore = Float.MAX_VALUE
        for (i in 0 until count) {
            val idx = (writeIndex - 1 - i + capacity * 2) % capacity
            val frame = buffer[idx] ?: continue
            val exposureDiff = kotlin.math.abs(frame.exposureNs - targetExposureNs).toFloat()
            val agePenalty = i * 0.1f
            val score = exposureDiff / targetExposureNs + agePenalty
            if (score < bestScore) {
                bestScore = score
                best = frame
            }
        }
        return best
    }

    fun clear() = lock.withLock {
        buffer.fill(null)
        writeIndex = 0
        count = 0
    }

    fun size(): Int = lock.withLock { count }

    fun isFull(): Boolean = lock.withLock { count >= capacity }
}
