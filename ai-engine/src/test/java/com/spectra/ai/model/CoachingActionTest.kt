package com.spectra.ai.model

import com.spectra.core.model.CameraPreset
import com.spectra.core.model.LensId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachingActionTest {

    @Test
    fun `SWITCH_LENS carries target lens`() {
        val action = CoachingAction.SwitchLens(LensId.TELEPHOTO_3X)
        assertEquals(LensId.TELEPHOTO_3X, action.lensId)
        assertTrue(action is CoachingAction)
    }

    @Test
    fun `ENABLE_BURST is a singleton`() {
        val action = CoachingAction.EnableBurst
        assertTrue(action is CoachingAction)
    }

    @Test
    fun `SWITCH_PRESET carries target preset`() {
        val action = CoachingAction.SwitchPreset(CameraPreset.NIGHT)
        assertEquals(CameraPreset.NIGHT, action.preset)
    }

    @Test
    fun `display labels are descriptive`() {
        assertTrue(CoachingAction.SwitchLens(LensId.TELEPHOTO_3X).label.contains("3"))
        assertEquals("Enable Burst", CoachingAction.EnableBurst.label)
        assertTrue(CoachingAction.SwitchPreset(CameraPreset.NIGHT).label.contains("Night"))
    }
}
