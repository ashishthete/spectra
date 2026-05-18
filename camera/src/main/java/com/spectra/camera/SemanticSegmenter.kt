package com.spectra.camera

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions

class SemanticSegmenter {
    private val TAG = "SemanticSegmenter"

    private val segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask()
            .build()
    )

    data class SegmentationResult(
        val mask: FloatArray,  // 0.0 = background, 1.0 = person, per pixel
        val width: Int,
        val height: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SegmentationResult) return false
            return mask.contentEquals(other.mask) && width == other.width && height == other.height
        }
        override fun hashCode(): Int = mask.contentHashCode() * 31 + width * 31 + height
    }

    fun segment(bitmap: Bitmap, onResult: (SegmentationResult?) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        segmenter.process(image)
            .addOnSuccessListener { segMask ->
                val buffer = segMask.buffer
                buffer.rewind()
                val w = segMask.width
                val h = segMask.height
                val mask = FloatArray(w * h)
                for (i in mask.indices) {
                    mask[i] = buffer.float
                }
                onResult(SegmentationResult(mask, w, h))
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Segmentation failed", e)
                onResult(null)
            }
    }

    fun close() {
        segmenter.close()
    }

    companion object {

        /**
         * Blends [pixels] in-place: background pixels (mask confidence < 0.8) are
         * blurred proportionally, person pixels (confidence >= 0.8) are preserved.
         *
         * @param pixels  ARGB pixel array (modified in-place)
         * @param w       pixel array width
         * @param h       pixel array height
         * @param mask    segmentation confidence map (0.0 = background, 1.0 = person)
         * @param maskW   mask width (may differ from [w])
         * @param maskH   mask height (may differ from [h])
         * @param blurRadius  box-blur radius applied to the background
         */
        fun applySemanticBokeh(
            pixels: IntArray,
            w: Int,
            h: Int,
            mask: FloatArray,
            maskW: Int,
            maskH: Int,
            blurRadius: Int = 12
        ) {
            // Scale mask to match pixel dimensions if different
            val scaledMask = if (maskW == w && maskH == h) mask else {
                FloatArray(w * h) { i ->
                    val x = (i % w) * maskW / w
                    val y = (i / w) * maskH / h
                    mask[y * maskW + x]
                }
            }

            // Create blurred version of entire image (simple box blur for now)
            val blurred = boxBlur(pixels, w, h, blurRadius)

            // Blend: person pixels keep original, background gets blurred
            for (i in pixels.indices) {
                val personConf = scaledMask[i]
                if (personConf < 0.8f) {
                    val alpha = (1f - personConf).coerceIn(0f, 1f)
                    val origR = (pixels[i] shr 16) and 0xFF
                    val origG = (pixels[i] shr 8) and 0xFF
                    val origB = pixels[i] and 0xFF
                    val blurR = (blurred[i] shr 16) and 0xFF
                    val blurG = (blurred[i] shr 8) and 0xFF
                    val blurB = blurred[i] and 0xFF
                    val r = (origR + alpha * (blurR - origR)).toInt().coerceIn(0, 255)
                    val g = (origG + alpha * (blurG - origG)).toInt().coerceIn(0, 255)
                    val b = (origB + alpha * (blurB - origB)).toInt().coerceIn(0, 255)
                    pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        /**
         * Two-pass (horizontal + vertical) box blur. Returns a new array; [pixels] is
         * not modified.
         */
        fun boxBlur(pixels: IntArray, w: Int, h: Int, radius: Int): IntArray {
            val temp = IntArray(pixels.size)
            val output = IntArray(pixels.size)
            val diam = radius * 2 + 1

            // Horizontal pass
            for (y in 0 until h) {
                var rSum = 0; var gSum = 0; var bSum = 0
                for (x in -radius..radius) {
                    val px = pixels[y * w + x.coerceIn(0, w - 1)]
                    rSum += (px shr 16) and 0xFF
                    gSum += (px shr 8) and 0xFF
                    bSum += px and 0xFF
                }
                for (x in 0 until w) {
                    temp[y * w + x] = (0xFF shl 24) or
                        ((rSum / diam) shl 16) or ((gSum / diam) shl 8) or (bSum / diam)
                    val addX = (x + radius + 1).coerceAtMost(w - 1)
                    val remX = (x - radius).coerceAtLeast(0)
                    val addPx = pixels[y * w + addX]
                    val remPx = pixels[y * w + remX]
                    rSum += ((addPx shr 16) and 0xFF) - ((remPx shr 16) and 0xFF)
                    gSum += ((addPx shr 8) and 0xFF) - ((remPx shr 8) and 0xFF)
                    bSum += (addPx and 0xFF) - (remPx and 0xFF)
                }
            }

            // Vertical pass
            for (x in 0 until w) {
                var rSum = 0; var gSum = 0; var bSum = 0
                for (y in -radius..radius) {
                    val px = temp[y.coerceIn(0, h - 1) * w + x]
                    rSum += (px shr 16) and 0xFF
                    gSum += (px shr 8) and 0xFF
                    bSum += px and 0xFF
                }
                for (y in 0 until h) {
                    output[y * w + x] = (0xFF shl 24) or
                        ((rSum / diam) shl 16) or ((gSum / diam) shl 8) or (bSum / diam)
                    val addY = (y + radius + 1).coerceAtMost(h - 1)
                    val remY = (y - radius).coerceAtLeast(0)
                    val addPx = temp[addY * w + x]
                    val remPx = temp[remY * w + x]
                    rSum += ((addPx shr 16) and 0xFF) - ((remPx shr 16) and 0xFF)
                    gSum += ((addPx shr 8) and 0xFF) - ((remPx shr 8) and 0xFF)
                    bSum += (addPx and 0xFF) - (remPx and 0xFF)
                }
            }

            return output
        }
    }
}
