package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Camera2SettingsApplierTest {

    private val applier = Camera2SettingsApplier()

    @Test
    fun `shutterDenominatorToNanos converts 1000th to 1ms`() {
        val nanos = invokeShutterToNanos(1000)
        assertThat(nanos).isEqualTo(1_000_000L)
    }

    @Test
    fun `shutterDenominatorToNanos converts 1 to 1 second`() {
        val nanos = invokeShutterToNanos(1)
        assertThat(nanos).isEqualTo(1_000_000_000L)
    }

    @Test
    fun `shutterDenominatorToNanos handles zero denominator`() {
        val nanos = invokeShutterToNanos(0)
        assertThat(nanos).isEqualTo(1_000_000_000L)
    }

    @Test
    fun `shutterDenominatorToNanos converts 8000th correctly`() {
        val nanos = invokeShutterToNanos(8000)
        assertThat(nanos).isEqualTo(125_000L)
    }

    @Test
    fun `shutterDenominatorToNanos converts 30th for night mode`() {
        val nanos = invokeShutterToNanos(30)
        assertThat(nanos).isEqualTo(33_333_333L)
    }

    @Test
    fun `negative denominator returns 1 second`() {
        val nanos = invokeShutterToNanos(-5)
        assertThat(nanos).isEqualTo(1_000_000_000L)
    }

    @Test
    fun `clampIso clamps to valid range`() {
        val method = Camera2SettingsApplier::class.java.getDeclaredMethod(
            "clampIso", Int::class.java, Int::class.java, Int::class.java
        )
        method.isAccessible = true
        assertThat(method.invoke(applier, 50, 50, 3200)).isEqualTo(50)
        assertThat(method.invoke(applier, 25, 50, 3200)).isEqualTo(50)
        assertThat(method.invoke(applier, 5000, 50, 3200)).isEqualTo(3200)
        assertThat(method.invoke(applier, 400, 50, 3200)).isEqualTo(400)
    }

    @Test
    fun `clampExposureNs clamps to valid range`() {
        val method = Camera2SettingsApplier::class.java.getDeclaredMethod(
            "clampExposureNs", Long::class.java, Long::class.java, Long::class.java
        )
        method.isAccessible = true
        val oneMs = 1_000_000L
        val oneSecond = 1_000_000_000L
        val tenMs = 10_000_000L
        assertThat(method.invoke(applier, tenMs, oneMs, oneSecond)).isEqualTo(tenMs)
        assertThat(method.invoke(applier, 500L, oneMs, oneSecond)).isEqualTo(oneMs)
        assertThat(method.invoke(applier, 2_000_000_000L, oneMs, oneSecond)).isEqualTo(oneSecond)
    }

    private fun invokeShutterToNanos(denominator: Int): Long {
        val method = Camera2SettingsApplier::class.java.getDeclaredMethod("shutterDenominatorToNanos", Int::class.java)
        method.isAccessible = true
        return method.invoke(applier, denominator) as Long
    }
}
