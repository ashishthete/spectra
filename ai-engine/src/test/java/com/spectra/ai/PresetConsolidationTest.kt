package com.spectra.ai

import com.spectra.core.model.CameraPreset
import com.spectra.core.model.LensId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetConsolidationTest {

    @Test
    fun `exactly 8 presets exist`() {
        assertEquals(8, CameraPreset.entries.size)
    }

    @Test
    fun `AUTO preset exists`() {
        val auto = CameraPreset.AUTO
        assertEquals("Auto", auto.label)
    }

    @Test
    fun `PORTRAIT preset uses telephoto 3x`() {
        assertEquals(LensId.TELEPHOTO_3X, CameraPreset.PORTRAIT.preferredLens)
    }

    @Test
    fun `PORTRAIT is not front camera default`() {
        assertFalse(CameraPreset.PORTRAIT.isFrontCameraDefault)
    }

    @Test
    fun `ACTION preset exists`() {
        val action = CameraPreset.ACTION
        assertEquals("Action", action.label)
    }

    @Test
    fun `FOOD preset exists`() {
        val food = CameraPreset.FOOD
        assertEquals("Food", food.label)
    }

    @Test
    fun `LANDSCAPE preset uses main lens`() {
        assertEquals(LensId.MAIN, CameraPreset.LANDSCAPE.preferredLens)
    }

    @Test
    fun `PRO preset still exists`() {
        val pro = CameraPreset.PRO
        assertEquals("Pro", pro.label)
    }
}
