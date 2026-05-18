package com.spectra.ai

import com.google.common.truth.Truth.assertThat
import com.spectra.core.model.SceneType
import org.junit.Test

class SceneClassifierTest {

    @Test
    fun `initial scene is UNKNOWN before any classifications`() {
        val hysteresis = SceneHysteresis()
        // First call with a single LANDSCAPE should not switch yet
        val result = hysteresis.smooth(SceneType.LANDSCAPE)
        assertThat(result).isEqualTo(SceneType.UNKNOWN)
    }

    @Test
    fun `scene smoothing requires 3 consistent classifications to switch`() {
        val hysteresis = SceneHysteresis()

        // Feed 1 LANDSCAPE -- not enough (1 of 1)
        assertThat(hysteresis.smooth(SceneType.LANDSCAPE)).isEqualTo(SceneType.UNKNOWN)

        // Feed 2 LANDSCAPE -- not enough (2 of 2)
        assertThat(hysteresis.smooth(SceneType.LANDSCAPE)).isEqualTo(SceneType.UNKNOWN)

        // Feed 3 LANDSCAPE -- should switch (3 of 3)
        assertThat(hysteresis.smooth(SceneType.LANDSCAPE)).isEqualTo(SceneType.LANDSCAPE)
    }

    @Test
    fun `alternating scenes do not cause switch`() {
        val hysteresis = SceneHysteresis()

        // Alternate between LANDSCAPE and INDOOR -- no majority
        hysteresis.smooth(SceneType.LANDSCAPE)
        hysteresis.smooth(SceneType.INDOOR)
        hysteresis.smooth(SceneType.LANDSCAPE)
        hysteresis.smooth(SceneType.INDOOR)
        val result = hysteresis.smooth(SceneType.LANDSCAPE)

        // 3 LANDSCAPE vs 2 INDOOR in buffer of 5 -- should switch to LANDSCAPE
        assertThat(result).isEqualTo(SceneType.LANDSCAPE)
    }

    @Test
    fun `stays on current scene when no clear majority`() {
        val hysteresis = SceneHysteresis()

        // First establish LANDSCAPE as stable
        hysteresis.smooth(SceneType.LANDSCAPE)
        hysteresis.smooth(SceneType.LANDSCAPE)
        hysteresis.smooth(SceneType.LANDSCAPE)
        assertThat(hysteresis.smooth(SceneType.LANDSCAPE)).isEqualTo(SceneType.LANDSCAPE)

        // Now feed mixed scenes -- 2 INDOOR + 1 NIGHT among last 5
        hysteresis.smooth(SceneType.INDOOR)
        val result = hysteresis.smooth(SceneType.NIGHT)

        // Buffer: [LANDSCAPE, LANDSCAPE, LANDSCAPE, INDOOR, NIGHT]
        // LANDSCAPE has 3, still majority -- stays LANDSCAPE
        assertThat(result).isEqualTo(SceneType.LANDSCAPE)
    }

    @Test
    fun `switches scene when new type reaches majority in window`() {
        val hysteresis = SceneHysteresis()

        // Establish LANDSCAPE
        repeat(3) { hysteresis.smooth(SceneType.LANDSCAPE) }
        assertThat(hysteresis.smooth(SceneType.LANDSCAPE)).isEqualTo(SceneType.LANDSCAPE)

        // Now push INDOOR until it reaches majority
        hysteresis.smooth(SceneType.INDOOR) // buffer: [L, L, L, L, I] -- L has 4
        hysteresis.smooth(SceneType.INDOOR) // buffer: [L, L, L, I, I] -- L has 3
        hysteresis.smooth(SceneType.INDOOR) // buffer: [L, L, I, I, I] -- I has 3, switch!

        assertThat(hysteresis.smooth(SceneType.INDOOR)).isEqualTo(SceneType.INDOOR)
    }

    @Test
    fun `ring buffer does not grow beyond window size`() {
        val hysteresis = SceneHysteresis(windowSize = 5, threshold = 3)

        // Feed 10 scenes -- buffer should only track last 5
        repeat(5) { hysteresis.smooth(SceneType.LANDSCAPE) }
        repeat(5) { hysteresis.smooth(SceneType.NIGHT) }

        // Buffer now: [NIGHT, NIGHT, NIGHT, NIGHT, NIGHT]
        // Should have switched to NIGHT
        assertThat(hysteresis.smooth(SceneType.NIGHT)).isEqualTo(SceneType.NIGHT)
    }

    @Test
    fun `reset clears state back to UNKNOWN`() {
        val hysteresis = SceneHysteresis()

        // Establish LANDSCAPE
        repeat(3) { hysteresis.smooth(SceneType.LANDSCAPE) }
        assertThat(hysteresis.smooth(SceneType.LANDSCAPE)).isEqualTo(SceneType.LANDSCAPE)

        // Reset
        hysteresis.reset()

        // Should be UNKNOWN again
        assertThat(hysteresis.smooth(SceneType.INDOOR)).isEqualTo(SceneType.UNKNOWN)
    }

    @Test
    fun `custom window size and threshold work correctly`() {
        val hysteresis = SceneHysteresis(windowSize = 3, threshold = 2)

        // Only need 2 of 3 to switch
        hysteresis.smooth(SceneType.FOOD)
        val result = hysteresis.smooth(SceneType.FOOD)
        assertThat(result).isEqualTo(SceneType.FOOD)
    }

    @Test
    fun `same scene as stable does not re-trigger`() {
        val hysteresis = SceneHysteresis()

        // Establish LANDSCAPE
        repeat(3) { hysteresis.smooth(SceneType.LANDSCAPE) }

        // Continue feeding LANDSCAPE -- should stay LANDSCAPE without issues
        repeat(10) {
            assertThat(hysteresis.smooth(SceneType.LANDSCAPE)).isEqualTo(SceneType.LANDSCAPE)
        }
    }
}
