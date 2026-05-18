package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ColorTemperatureEstimatorTest {

    @Test
    fun `equal gains returns neutral daylight`() {
        val ct = ColorTemperatureEstimator.fromGainRatio(1.0f, 1.0f)
        assertThat(ct).isIn(5000..6500)
    }

    @Test
    fun `low ratio returns high kelvin cool light`() {
        val ct = ColorTemperatureEstimator.fromGainRatio(0.5f, 1.0f)
        assertThat(ct).isEqualTo(8500)
    }

    @Test
    fun `high ratio returns low kelvin warm light`() {
        val ct = ColorTemperatureEstimator.fromGainRatio(2.5f, 1.0f)
        assertThat(ct).isEqualTo(2400)
    }

    @Test
    fun `interpolation produces intermediate values`() {
        val ct1 = ColorTemperatureEstimator.fromGainRatio(0.80f, 1.0f)
        assertThat(ct1).isIn(6500..7500)
    }

    @Test
    fun `very small bGain clamped to avoid division by zero`() {
        val ct = ColorTemperatureEstimator.fromGainRatio(1.0f, 0.0f)
        assertThat(ct).isEqualTo(2400)
    }

    @Test
    fun `typical indoor tungsten ratio`() {
        val ct = ColorTemperatureEstimator.fromGainRatio(1.6f, 1.0f)
        assertThat(ct).isIn(3200..4000)
    }

    @Test
    fun `typical overcast ratio`() {
        val ct = ColorTemperatureEstimator.fromGainRatio(0.65f, 1.0f)
        assertThat(ct).isIn(7500..8500)
    }

    @Test
    fun `monotonic decreasing with increasing ratio`() {
        val ratios = listOf(0.5f, 0.8f, 1.0f, 1.3f, 1.6f, 2.0f, 2.5f)
        val temps = ratios.map { ColorTemperatureEstimator.fromGainRatio(it, 1.0f) }
        for (i in 1 until temps.size) {
            assertThat(temps[i]).isAtMost(temps[i - 1])
        }
    }
}
