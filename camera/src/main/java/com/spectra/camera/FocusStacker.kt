package com.spectra.camera

class FocusStacker {

    /**
     * Stack multiple frames into one all-in-focus result.
     * Each frame is an IntArray of ARGB pixels, all same dimensions.
     * Uses Laplacian variance in a local window to pick the sharpest frame per pixel.
     */
    fun stack(frames: List<IntArray>, width: Int, height: Int): IntArray {
        if (frames.isEmpty()) return IntArray(0)
        if (frames.size == 1) return frames[0].copyOf()

        val numPixels = width * height
        val result = IntArray(numPixels)

        // Compute luminance for each frame
        val luminances = frames.map { pixels ->
            FloatArray(numPixels) { i ->
                val r = (pixels[i] shr 16) and 0xFF
                val g = (pixels[i] shr 8) and 0xFF
                val b = pixels[i] and 0xFF
                0.299f * r + 0.587f * g + 0.114f * b
            }
        }

        // Compute Laplacian variance per pixel per frame using a window
        val windowRadius = 4
        val sharpnessMaps = luminances.map { lum ->
            computeSharpnessMap(lum, width, height, windowRadius)
        }

        // For each pixel, pick from the frame with highest sharpness
        for (i in 0 until numPixels) {
            var bestFrame = 0
            var bestSharpness = sharpnessMaps[0][i]
            for (f in 1 until frames.size) {
                if (sharpnessMaps[f][i] > bestSharpness) {
                    bestSharpness = sharpnessMaps[f][i]
                    bestFrame = f
                }
            }
            result[i] = frames[bestFrame][i]
        }

        return result
    }

    private fun computeSharpnessMap(
        luminance: FloatArray,
        width: Int,
        height: Int,
        windowRadius: Int
    ): FloatArray {
        // First compute per-pixel Laplacian (approximated via second derivatives)
        val laplacian = FloatArray(width * height)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                val center = luminance[idx]
                val lap = luminance[idx - 1] + luminance[idx + 1] +
                          luminance[idx - width] + luminance[idx + width] -
                          4f * center
                laplacian[idx] = lap * lap // squared Laplacian
            }
        }

        // Box filter the squared Laplacian for local variance
        // Use integral image for O(1) per-pixel window sum
        val integral = DoubleArray(width * height)
        for (y in 0 until height) {
            var rowSum = 0.0
            for (x in 0 until width) {
                val idx = y * width + x
                rowSum += laplacian[idx]
                integral[idx] = rowSum + if (y > 0) integral[(y - 1) * width + x] else 0.0
            }
        }

        val sharpness = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val y1 = (y - windowRadius).coerceAtLeast(0)
                val y2 = (y + windowRadius).coerceAtMost(height - 1)
                val x1 = (x - windowRadius).coerceAtLeast(0)
                val x2 = (x + windowRadius).coerceAtMost(width - 1)

                var sum = integral[y2 * width + x2]
                if (x1 > 0) sum -= integral[y2 * width + (x1 - 1)]
                if (y1 > 0) sum -= integral[(y1 - 1) * width + x2]
                if (x1 > 0 && y1 > 0) sum += integral[(y1 - 1) * width + (x1 - 1)]

                sharpness[y * width + x] = sum.toFloat()
            }
        }

        return sharpness
    }
}
