// camera/src/test/java/com/spectra/camera/PortraitLightingTest.kt
package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PortraitLightingTest {

    companion object {
        private const val W = 8
        private const val H = 8
        private const val N = W * H
        /** Mid-gray packed as ARGB: fully opaque, R=G=B=0x80. */
        private const val MID_GRAY = (0xFF shl 24) or (0x80 shl 16) or (0x80 shl 8) or 0x80

        /** Build an 8x8 mask: left half = 1.0 (subject), right half = 0.0 (background). */
        private fun buildMask(): FloatArray = FloatArray(N) { i ->
            if (i % W < W / 2) 1.0f else 0.0f
        }

        /** Build an 8x8 pixel array filled with mid-gray. */
        private fun buildPixels(): IntArray = IntArray(N) { MID_GRAY }

        private fun isSubject(i: Int): Boolean = i % W < W / 2
        private fun alpha(px: Int): Int = (px ushr 24) and 0xFF
        private fun red(px: Int): Int = (px shr 16) and 0xFF
        private fun green(px: Int): Int = (px shr 8) and 0xFF
        private fun blue(px: Int): Int = px and 0xFF
    }

    // ---------------------------------------------------------------
    // 1. NATURAL mode doesn't modify pixels
    // ---------------------------------------------------------------

    @Test
    fun `NATURAL mode does not modify any pixels`() {
        val pixels = buildPixels()
        val original = pixels.copyOf()
        PortraitLighting.apply(pixels, W, H, buildMask(), null, PortraitLighting.LightingMode.NATURAL)
        assertThat(pixels).isEqualTo(original)
    }

    // ---------------------------------------------------------------
    // 2. STUDIO mode brightens subject pixels (mask > 0.5)
    // ---------------------------------------------------------------

    @Test
    fun `STUDIO mode brightens subject pixels`() {
        val pixels = buildPixels()
        PortraitLighting.apply(pixels, W, H, buildMask(), null, PortraitLighting.LightingMode.STUDIO)

        val brightenedSubject = (0 until N).filter { isSubject(it) }.any { i ->
            red(pixels[i]) > 0x80 || green(pixels[i]) > 0x80 || blue(pixels[i]) > 0x80
        }
        assertThat(brightenedSubject).isTrue()
    }

    // ---------------------------------------------------------------
    // 3. CONTOUR mode modifies subject pixels based on luminance
    // ---------------------------------------------------------------

    @Test
    fun `CONTOUR mode modifies subject pixels`() {
        val pixels = buildPixels()
        PortraitLighting.apply(pixels, W, H, buildMask(), null, PortraitLighting.LightingMode.CONTOUR)

        // Mid-gray (0x80=128) has luminance = (128*77 + 128*150 + 128*29) >> 8 = 128.
        // 128 >= 100 so factor = 1.05, channels should be brightened.
        for (i in 0 until N) {
            if (isSubject(i)) {
                assertThat(red(pixels[i])).isNotEqualTo(0x80)
            }
        }
    }

    @Test
    fun `CONTOUR mode darkens low-luminance subject pixels`() {
        // Build dark pixels (luminance < 100) so factor = 0.85
        val darkPixel = (0xFF shl 24) or (0x30 shl 16) or (0x30 shl 8) or 0x30
        val pixels = IntArray(N) { darkPixel }
        PortraitLighting.apply(pixels, W, H, buildMask(), null, PortraitLighting.LightingMode.CONTOUR)

        for (i in 0 until N) {
            if (isSubject(i)) {
                assertThat(red(pixels[i])).isLessThan(0x30)
            }
        }
    }

    @Test
    fun `CONTOUR mode brightens high-luminance subject pixels`() {
        val pixels = buildPixels() // luminance 128 >= 100 -> factor 1.05
        PortraitLighting.apply(pixels, W, H, buildMask(), null, PortraitLighting.LightingMode.CONTOUR)

        for (i in 0 until N) {
            if (isSubject(i)) {
                assertThat(red(pixels[i])).isGreaterThan(0x80)
            }
        }
    }

    // ---------------------------------------------------------------
    // 4. STAGE mode sets background pixels (mask < 0.5) to black
    // ---------------------------------------------------------------

    @Test
    fun `STAGE mode sets background pixels to black`() {
        val pixels = buildPixels()
        PortraitLighting.apply(pixels, W, H, buildMask(), null, PortraitLighting.LightingMode.STAGE)

        for (i in 0 until N) {
            if (!isSubject(i)) {
                assertThat(red(pixels[i])).isEqualTo(0)
                assertThat(green(pixels[i])).isEqualTo(0)
                assertThat(blue(pixels[i])).isEqualTo(0)
            }
        }
    }

    @Test
    fun `STAGE mode preserves subject pixels`() {
        val pixels = buildPixels()
        PortraitLighting.apply(pixels, W, H, buildMask(), null, PortraitLighting.LightingMode.STAGE)

        for (i in 0 until N) {
            if (isSubject(i)) {
                assertThat(pixels[i]).isEqualTo(MID_GRAY)
            }
        }
    }

    // ---------------------------------------------------------------
    // 5. HIGH_KEY mode sets background pixels to near-white (0xF0F0F0)
    // ---------------------------------------------------------------

    @Test
    fun `HIGH_KEY mode sets background pixels to near-white`() {
        val pixels = buildPixels()
        PortraitLighting.apply(pixels, W, H, buildMask(), null, PortraitLighting.LightingMode.HIGH_KEY)

        for (i in 0 until N) {
            if (!isSubject(i)) {
                assertThat(red(pixels[i])).isEqualTo(0xF0)
                assertThat(green(pixels[i])).isEqualTo(0xF0)
                assertThat(blue(pixels[i])).isEqualTo(0xF0)
            }
        }
    }

    @Test
    fun `HIGH_KEY mode brightens subject pixels`() {
        val pixels = buildPixels()
        PortraitLighting.apply(pixels, W, H, buildMask(), null, PortraitLighting.LightingMode.HIGH_KEY)

        for (i in 0 until N) {
            if (isSubject(i)) {
                // factor 1.08 on 0x80 (128) -> 138 which is > 128
                assertThat(red(pixels[i])).isGreaterThan(0x80)
                assertThat(green(pixels[i])).isGreaterThan(0x80)
                assertThat(blue(pixels[i])).isGreaterThan(0x80)
            }
        }
    }

    // ---------------------------------------------------------------
    // 6. All modes leave alpha channel at 0xFF
    // ---------------------------------------------------------------

    @Test
    fun `NATURAL mode preserves alpha at 0xFF`() {
        val pixels = buildPixels()
        PortraitLighting.apply(pixels, W, H, buildMask(), null, PortraitLighting.LightingMode.NATURAL)
        for (i in 0 until N) {
            assertThat(alpha(pixels[i])).isEqualTo(0xFF)
        }
    }

    @Test
    fun `STUDIO mode preserves alpha at 0xFF`() {
        val pixels = buildPixels()
        PortraitLighting.apply(pixels, W, H, buildMask(), null, PortraitLighting.LightingMode.STUDIO)
        for (i in 0 until N) {
            assertThat(alpha(pixels[i])).isEqualTo(0xFF)
        }
    }

    @Test
    fun `CONTOUR mode preserves alpha at 0xFF`() {
        val pixels = buildPixels()
        PortraitLighting.apply(pixels, W, H, buildMask(), null, PortraitLighting.LightingMode.CONTOUR)
        for (i in 0 until N) {
            assertThat(alpha(pixels[i])).isEqualTo(0xFF)
        }
    }

    @Test
    fun `STAGE mode preserves alpha at 0xFF`() {
        val pixels = buildPixels()
        PortraitLighting.apply(pixels, W, H, buildMask(), null, PortraitLighting.LightingMode.STAGE)
        for (i in 0 until N) {
            assertThat(alpha(pixels[i])).isEqualTo(0xFF)
        }
    }

    @Test
    fun `HIGH_KEY mode preserves alpha at 0xFF`() {
        val pixels = buildPixels()
        PortraitLighting.apply(pixels, W, H, buildMask(), null, PortraitLighting.LightingMode.HIGH_KEY)
        for (i in 0 until N) {
            assertThat(alpha(pixels[i])).isEqualTo(0xFF)
        }
    }

    // ---------------------------------------------------------------
    // 7. Background pixels unchanged for STUDIO and CONTOUR
    // ---------------------------------------------------------------

    @Test
    fun `STUDIO mode does not modify background pixels`() {
        val pixels = buildPixels()
        PortraitLighting.apply(pixels, W, H, buildMask(), null, PortraitLighting.LightingMode.STUDIO)

        for (i in 0 until N) {
            if (!isSubject(i)) {
                assertThat(pixels[i]).isEqualTo(MID_GRAY)
            }
        }
    }

    @Test
    fun `CONTOUR mode does not modify background pixels`() {
        val pixels = buildPixels()
        PortraitLighting.apply(pixels, W, H, buildMask(), null, PortraitLighting.LightingMode.CONTOUR)

        for (i in 0 until N) {
            if (!isSubject(i)) {
                assertThat(pixels[i]).isEqualTo(MID_GRAY)
            }
        }
    }
}
