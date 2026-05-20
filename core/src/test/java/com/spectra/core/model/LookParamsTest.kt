package com.spectra.core.model

import org.junit.Assert.*
import org.junit.Test

class LookParamsTest {

    @Test
    fun `natural style produces identity look`() {
        val params = LookParams.fromState(
            style = PhotoStyle.NATURAL,
            preset = CameraPreset.AUTO,
            wbKelvin = 5500,
            isHdr = false,
            isPortrait = false,
            beautyLevel = 0,
            sceneContrast = 0f,
            faceCount = 0
        )
        assertEquals(0f, params.contrastStrength, 0.01f)
        assertEquals(1.0f, params.saturationScale, 0.01f)
        assertEquals(0f, params.highlightShoulder, 0.01f)
        assertEquals(0f, params.shadowLift, 0.01f)
        assertFalse(params.skinHueProtection)
        assertFalse(params.bokehEnabled)
    }

    @Test
    fun `vivid style increases saturation and contrast`() {
        val params = LookParams.fromState(
            style = PhotoStyle.VIVID,
            preset = CameraPreset.AUTO,
            wbKelvin = 5500,
            isHdr = false,
            isPortrait = false,
            beautyLevel = 0,
            sceneContrast = 0f,
            faceCount = 0
        )
        assertTrue(params.saturationScale > 1.0f)
        assertTrue(params.contrastStrength > 0f)
    }

    @Test
    fun `film style has lifted blacks and highlight rolloff`() {
        val params = LookParams.fromState(
            style = PhotoStyle.FILM,
            preset = CameraPreset.AUTO,
            wbKelvin = 5500,
            isHdr = false,
            isPortrait = false,
            beautyLevel = 0,
            sceneContrast = 0f,
            faceCount = 0
        )
        assertTrue(params.shadowLift > 0f)
        assertTrue(params.highlightShoulder > 0.5f)
    }

    @Test
    fun `cinematic style has desaturated look`() {
        val params = LookParams.fromState(
            style = PhotoStyle.CINEMATIC,
            preset = CameraPreset.AUTO,
            wbKelvin = 5500,
            isHdr = false,
            isPortrait = false,
            beautyLevel = 0,
            sceneContrast = 0f,
            faceCount = 0
        )
        assertTrue(params.saturationScale < 1.0f)
        assertTrue(params.shadowLift > 0f)
    }

    @Test
    fun `faces enable skin hue protection`() {
        val params = LookParams.fromState(
            style = PhotoStyle.NATURAL,
            preset = CameraPreset.PORTRAIT,
            wbKelvin = 5500,
            isHdr = false,
            isPortrait = true,
            beautyLevel = 0,
            sceneContrast = 0f,
            faceCount = 2
        )
        assertTrue(params.skinHueProtection)
    }

    @Test
    fun `portrait mode with faces enables bokeh`() {
        val params = LookParams.fromState(
            style = PhotoStyle.NATURAL,
            preset = CameraPreset.PORTRAIT,
            wbKelvin = 5500,
            isHdr = false,
            isPortrait = true,
            beautyLevel = 0,
            sceneContrast = 0f,
            faceCount = 1
        )
        assertTrue(params.bokehEnabled)
        assertTrue(params.isPortraitMode)
    }

    @Test
    fun `portrait without faces does not enable bokeh`() {
        val params = LookParams.fromState(
            style = PhotoStyle.NATURAL,
            preset = CameraPreset.PORTRAIT,
            wbKelvin = 5500,
            isHdr = false,
            isPortrait = true,
            beautyLevel = 0,
            sceneContrast = 0f,
            faceCount = 0
        )
        assertFalse(params.bokehEnabled)
    }

    @Test
    fun `HDR with high contrast enables chroma compression`() {
        val params = LookParams.fromState(
            style = PhotoStyle.NATURAL,
            preset = CameraPreset.AUTO,
            wbKelvin = 5500,
            isHdr = true,
            isPortrait = false,
            beautyLevel = 0,
            sceneContrast = 0.5f,
            faceCount = 0
        )
        assertTrue(params.chromaCompression > 0f)
        assertTrue(params.isHdrActive)
    }

    @Test
    fun `warm style preserves white balance`() {
        val params = LookParams.fromState(
            style = PhotoStyle.WARM,
            preset = CameraPreset.AUTO,
            wbKelvin = 6500,
            isHdr = false,
            isPortrait = false,
            beautyLevel = 0,
            sceneContrast = 0f,
            faceCount = 0
        )
        assertEquals(6500, params.whiteBalanceKelvin)
        assertEquals(1.05f, params.saturationScale, 0.01f)
    }
}
