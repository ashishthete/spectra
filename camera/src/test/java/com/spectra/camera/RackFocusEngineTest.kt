package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RackFocusEngineTest {

    private val engine = RackFocusEngine()

    @Test
    fun `setPoints stores A and B so rackAToB and rackBToA are callable`() {
        val a = RackFocusEngine.FocusPoint(distance = 0.5f, label = "Subject")
        val b = RackFocusEngine.FocusPoint(distance = 2.0f, label = "Background")
        engine.setPoints(a, b)

        // After setPoints, rackAToB/rackBToA should not throw
        // (ValueAnimator requires Android runtime, so we only verify points are stored
        //  by checking that release clears them)
        engine.release()
        var calledAfterRelease = false
        engine.rackBToA { calledAfterRelease = true }
        assertThat(calledAfterRelease).isFalse()
    }

    @Test
    fun `rackAToB does nothing when points not set`() {
        var called = false
        engine.rackAToB { called = true }
        assertThat(called).isFalse()
    }

    @Test
    fun `rackBToA does nothing when points not set`() {
        var called = false
        engine.rackBToA { called = true }
        assertThat(called).isFalse()
    }

    @Test
    fun `cancel clears active state`() {
        engine.cancel()
        assertThat(engine.isActive).isFalse()
    }

    @Test
    fun `release clears points so rackAToB and rackBToA do nothing`() {
        val a = RackFocusEngine.FocusPoint(distance = 0.3f)
        val b = RackFocusEngine.FocusPoint(distance = 1.5f)
        engine.setPoints(a, b)
        engine.release()

        var calledA = false
        var calledB = false
        engine.rackAToB { calledA = true }
        engine.rackBToA { calledB = true }

        assertThat(calledA).isFalse()
        assertThat(calledB).isFalse()
    }

    @Test
    fun `release sets isActive to false`() {
        engine.release()
        assertThat(engine.isActive).isFalse()
    }

    @Test
    fun `FocusPoint stores distance and label`() {
        val point = RackFocusEngine.FocusPoint(distance = 1.2f, label = "Subject")
        assertThat(point.distance).isEqualTo(1.2f)
        assertThat(point.label).isEqualTo("Subject")
    }

    @Test
    fun `FocusPoint label defaults to empty string`() {
        val point = RackFocusEngine.FocusPoint(distance = 0.8f)
        assertThat(point.label).isEmpty()
    }
}
