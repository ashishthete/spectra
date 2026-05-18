package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NoiseReducerTest {

    @Test fun `sigma increases with higher ISO`() {
        val sigma100 = NoiseReducer.computeSigma(100)
        val sigma800 = NoiseReducer.computeSigma(800)
        val sigma3200 = NoiseReducer.computeSigma(3200)
        assertThat(sigma100).isLessThan(sigma800)
        assertThat(sigma800).isLessThan(sigma3200)
    }

    @Test fun `sigma at ISO 100 is close to baseline`() {
        assertThat(NoiseReducer.computeSigma(100)).isWithin(0.01f).of(0.75f)
    }

    @Test fun `sigma at ISO 3200 is strong`() {
        assertThat(NoiseReducer.computeSigma(3200)).isWithin(0.01f).of(8.5f)
    }

    @Test fun `tiled bilateral matches single-pass for small images`() {
        val w = 64; val h = 64
        val channel = IntArray(w * h) { (it * 3 + 17) % 256 }
        val singlePass = NoiseReducer.bilateralFilter(channel.copyOf(), w, h, 3, 1.0f)
        val tiledPass = NoiseReducer.tiledBilateralFilter(channel.copyOf(), w, h, 3, 1.0f, tileSize = 128)
        for (i in singlePass.indices) {
            assertThat(tiledPass[i]).isWithin(1).of(singlePass[i])
        }
    }

    @Test fun `tiled bilateral matches single-pass with multiple tiles`() {
        val w = 64; val h = 64
        val channel = IntArray(w * h) { (it * 7 + 31) % 256 }
        val singlePass = NoiseReducer.bilateralFilter(channel.copyOf(), w, h, 3, 1.0f)
        val tiledPass = NoiseReducer.tiledBilateralFilter(channel.copyOf(), w, h, 3, 1.0f, tileSize = 16)
        for (i in singlePass.indices) {
            assertThat(tiledPass[i]).isWithin(1).of(singlePass[i])
        }
    }

    @Test fun `bilateral filter smooths uniform channel`() {
        val w = 8; val h = 8
        val channel = IntArray(w * h) { 128 }
        val result = NoiseReducer.bilateralFilter(channel, w, h, 3, 5.0f)
        assertThat(result[w * h / 2 + w / 2]).isWithin(1).of(128)
    }

    @Test fun `bilateral filter reduces noise variance`() {
        val w = 16; val h = 16
        val channel = IntArray(w * h) { if (it % 2 == 0) 158 else 98 }
        val result = NoiseReducer.bilateralFilter(channel, w, h, 5, 50.0f)

        val variance = result.map { it.toDouble() }.let { vals ->
            val mean = vals.average()
            vals.sumOf { (it - mean) * (it - mean) } / vals.size
        }
        val inputVariance = channel.map { it.toDouble() }.let { vals ->
            val mean = vals.average()
            vals.sumOf { (it - mean) * (it - mean) } / vals.size
        }

        assertThat(variance).isLessThan(inputVariance)
    }
}
