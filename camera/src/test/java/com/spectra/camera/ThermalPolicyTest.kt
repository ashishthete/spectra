package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThermalPolicyTest {

    @Test
    fun `FULL quality allows 5-frame HDR`() {
        assertThat(ThermalPolicy.QualityLevel.FULL.ordinal).isEqualTo(0)
    }

    @Test
    fun `REDUCED quality level exists`() {
        assertThat(ThermalPolicy.QualityLevel.REDUCED.ordinal).isEqualTo(1)
    }

    @Test
    fun `MINIMAL quality level exists`() {
        assertThat(ThermalPolicy.QualityLevel.MINIMAL.ordinal).isEqualTo(2)
    }

    @Test
    fun `quality levels ordered from best to worst`() {
        val levels = ThermalPolicy.QualityLevel.entries
        assertThat(levels).hasSize(3)
        assertThat(levels[0]).isEqualTo(ThermalPolicy.QualityLevel.FULL)
        assertThat(levels[1]).isEqualTo(ThermalPolicy.QualityLevel.REDUCED)
        assertThat(levels[2]).isEqualTo(ThermalPolicy.QualityLevel.MINIMAL)
    }
}
