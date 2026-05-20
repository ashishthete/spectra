package com.spectra.core.model

import org.junit.Assert.*
import org.junit.Test

class UserTierTest {

    @Test
    fun `everyday tier hides pro tools`() {
        val tier = UserTier.EVERYDAY
        assertFalse(tier.showHistogram)
        assertFalse(tier.showRawToggle)
        assertFalse(tier.showFocusPeaking)
        assertFalse(tier.showZebra)
        assertFalse(tier.showWaveform)
        assertFalse(tier.showFalseColor)
        assertFalse(tier.showManualSettings)
        assertFalse(tier.showProcessingStrength)
        assertFalse(tier.showStyleSelector)
        assertEquals(1, tier.maxCoachingDirectives)
    }

    @Test
    fun `creator tier shows styles and histogram`() {
        val tier = UserTier.CREATOR
        assertTrue(tier.showHistogram)
        assertTrue(tier.showProcessingStrength)
        assertTrue(tier.showStyleSelector)
        assertFalse(tier.showRawToggle)
        assertFalse(tier.showFocusPeaking)
        assertEquals(2, tier.maxCoachingDirectives)
    }

    @Test
    fun `pro tier shows everything`() {
        val tier = UserTier.PRO
        assertTrue(tier.showHistogram)
        assertTrue(tier.showRawToggle)
        assertTrue(tier.showFocusPeaking)
        assertTrue(tier.showZebra)
        assertTrue(tier.showWaveform)
        assertTrue(tier.showFalseColor)
        assertTrue(tier.showManualSettings)
        assertTrue(tier.showProcessingStrength)
        assertTrue(tier.showStyleSelector)
        assertEquals(0, tier.maxCoachingDirectives)
    }
}
