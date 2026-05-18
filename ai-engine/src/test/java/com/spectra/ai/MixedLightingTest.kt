package com.spectra.ai

import com.spectra.ai.model.LightingCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MixedLightingTest {

    @Test
    fun `MIXED lighting condition exists`() {
        val mixed = LightingCondition.MIXED
        assertNotNull(mixed)
        assertEquals("MIXED", mixed.label)
    }

    @Test
    fun `MIXED is distinct from other conditions`() {
        val conditions = LightingCondition.entries
        val mixedCount = conditions.count { it == LightingCondition.MIXED }
        assertEquals(1, mixedCount)
    }
}
