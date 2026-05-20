package com.spectra.camera.gpu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.util.Log

object ShaderPipeline {

    private const val TAG = "ShaderPipeline"

    private val TONE_MAP_SRC = """
        uniform shader inputImage;
        uniform float shadowBoostStrength;
        uniform float highlightCompress;

        half4 main(float2 coord) {
            half4 c = inputImage.eval(coord);
            float r = clamp(float(c.r), 0.0, 1.0);
            float g = clamp(float(c.g), 0.0, 1.0);
            float b = clamp(float(c.b), 0.0, 1.0);
            float lum = 0.299 * r + 0.587 * g + 0.114 * b;

            float mapped = lum;
            if (lum >= 0.01) {
                float shadow = 1.0 + shadowBoostStrength * (1.0 - lum) * (1.0 - lum);
                float highlight = 1.0 / (1.0 + lum * highlightCompress);
                mapped = lum * shadow * highlight;
            }
            float scale = (lum < 0.001) ? 1.0 : clamp(mapped / lum, 0.5, 2.5);
            return half4(half(clamp(r * scale, 0.0, 1.0)),
                         half(clamp(g * scale, 0.0, 1.0)),
                         half(clamp(b * scale, 0.0, 1.0)),
                         c.a);
        }
    """.trimIndent()

    private val SHADOW_RECOVERY_SRC = """
        uniform shader inputImage;
        uniform float strength;

        half4 main(float2 coord) {
            half4 c = inputImage.eval(coord);
            float r = clamp(float(c.r), 0.0, 1.0);
            float g = clamp(float(c.g), 0.0, 1.0);
            float b = clamp(float(c.b), 0.0, 1.0);
            float lum = 0.299 * r + 0.587 * g + 0.114 * b;

            if (lum < 0.4) {
                float shadowFactor = 1.0 - (lum / 0.4);
                float boost = 1.0 + strength * shadowFactor * 0.6;
                r = clamp(r * boost, 0.0, 1.0);
                g = clamp(g * boost, 0.0, 1.0);
                b = clamp(b * boost, 0.0, 1.0);
            }
            return half4(half(r), half(g), half(b), c.a);
        }
    """.trimIndent()

    private val HIGHLIGHT_ROLLOFF_SRC = """
        uniform shader inputImage;
        uniform float shoulderStart;
        uniform float maxOutput;
        uniform float rolloffStrength;

        float shoulder(float x) {
            float ss = shoulderStart / 255.0;
            float mo = maxOutput / 255.0;
            if (x <= ss) return x;
            float t = (x - ss) / (1.0 - ss);
            float compressed = ss + (mo - ss) * (1.0 - pow(1.0 - t, 1.0 + rolloffStrength * 2.0));
            return clamp(compressed, 0.0, 1.0);
        }

        half4 main(float2 coord) {
            half4 c = inputImage.eval(coord);
            return half4(half(shoulder(clamp(float(c.r), 0.0, 1.0))),
                         half(shoulder(clamp(float(c.g), 0.0, 1.0))),
                         half(shoulder(clamp(float(c.b), 0.0, 1.0))),
                         c.a);
        }
    """.trimIndent()

    private val WHITE_BALANCE_SRC = """
        uniform shader inputImage;
        uniform float rGain;
        uniform float gGain;
        uniform float bGain;

        half4 main(float2 coord) {
            half4 c = inputImage.eval(coord);
            return half4(half(clamp(float(c.r) * rGain, 0.0, 1.0)),
                         half(clamp(float(c.g) * gGain, 0.0, 1.0)),
                         half(clamp(float(c.b) * bGain, 0.0, 1.0)),
                         c.a);
        }
    """.trimIndent()

    private val CONTRAST_SATURATION_SRC = """
        uniform shader inputImage;
        uniform float contrastScale;
        uniform float contrastOffset;
        uniform float saturation;

        half4 main(float2 coord) {
            half4 c = inputImage.eval(coord);
            float r = clamp(float(c.r) * contrastScale + contrastOffset, 0.0, 1.0);
            float g = clamp(float(c.g) * contrastScale + contrastOffset, 0.0, 1.0);
            float b = clamp(float(c.b) * contrastScale + contrastOffset, 0.0, 1.0);

            float lum = 0.299 * r + 0.587 * g + 0.114 * b;
            r = clamp(lum + (r - lum) * saturation, 0.0, 1.0);
            g = clamp(lum + (g - lum) * saturation, 0.0, 1.0);
            b = clamp(lum + (b - lum) * saturation, 0.0, 1.0);

            return half4(half(r), half(g), half(b), c.a);
        }
    """.trimIndent()

    private val USM_SHARPEN_SRC = """
        uniform shader inputImage;
        uniform float imageWidth;
        uniform float imageHeight;
        uniform float amount;
        uniform float threshold;

        half4 main(float2 coord) {
            half4 center = inputImage.eval(coord);
            float dx = 1.0 / imageWidth;
            float dy = 1.0 / imageHeight;

            // 3x3 Laplacian approximation for luminance
            half4 top    = inputImage.eval(coord + float2(0, -dy));
            half4 bottom = inputImage.eval(coord + float2(0,  dy));
            half4 left   = inputImage.eval(coord + float2(-dx, 0));
            half4 right  = inputImage.eval(coord + float2( dx, 0));

            float lumC = 0.299 * float(center.r) + 0.587 * float(center.g) + 0.114 * float(center.b);
            float lumT = 0.299 * float(top.r) + 0.587 * float(top.g) + 0.114 * float(top.b);
            float lumB = 0.299 * float(bottom.r) + 0.587 * float(bottom.g) + 0.114 * float(bottom.b);
            float lumL = 0.299 * float(left.r) + 0.587 * float(left.g) + 0.114 * float(left.b);
            float lumR = 0.299 * float(right.r) + 0.587 * float(right.g) + 0.114 * float(right.b);

            float detail = lumC - 0.25 * (lumT + lumB + lumL + lumR);

            if (abs(detail) < threshold) {
                return center;
            }

            float boost = detail * amount;
            return half4(half(clamp(float(center.r) + boost, 0.0, 1.0)),
                         half(clamp(float(center.g) + boost, 0.0, 1.0)),
                         half(clamp(float(center.b) + boost, 0.0, 1.0)),
                         center.a);
        }
    """.trimIndent()

    fun applyToneMap(
        bitmap: Bitmap,
        shadowBoostStrength: Float = 0.4f,
        highlightCompress: Float = 0.3f
    ): Boolean = applyShader(bitmap, TONE_MAP_SRC) { shader ->
        shader.setFloatUniform("shadowBoostStrength", shadowBoostStrength)
        shader.setFloatUniform("highlightCompress", highlightCompress)
    }

    fun applyShadowRecovery(bitmap: Bitmap, strength: Float): Boolean =
        applyShader(bitmap, SHADOW_RECOVERY_SRC) { shader ->
            shader.setFloatUniform("strength", strength)
        }

    fun applyHighlightRolloff(
        bitmap: Bitmap,
        shoulderStart: Float,
        maxOutput: Float,
        strength: Float
    ): Boolean = applyShader(bitmap, HIGHLIGHT_ROLLOFF_SRC) { shader ->
        shader.setFloatUniform("shoulderStart", shoulderStart)
        shader.setFloatUniform("maxOutput", maxOutput)
        shader.setFloatUniform("rolloffStrength", strength)
    }

    fun applyWhiteBalance(
        bitmap: Bitmap,
        rGain: Float,
        gGain: Float,
        bGain: Float
    ): Boolean = applyShader(bitmap, WHITE_BALANCE_SRC) { shader ->
        shader.setFloatUniform("rGain", rGain)
        shader.setFloatUniform("gGain", gGain)
        shader.setFloatUniform("bGain", bGain)
    }

    fun applyContrastSaturation(
        bitmap: Bitmap,
        contrastScale: Float = 1.03f,
        contrastOffset: Float = -4f / 255f,
        saturation: Float = 1.05f
    ): Boolean = applyShader(bitmap, CONTRAST_SATURATION_SRC) { shader ->
        shader.setFloatUniform("contrastScale", contrastScale)
        shader.setFloatUniform("contrastOffset", contrastOffset)
        shader.setFloatUniform("saturation", saturation)
    }

    fun applyUsm(
        bitmap: Bitmap,
        amount: Float = 0.5f,
        threshold: Float = 0.02f
    ): Boolean = applyShader(bitmap, USM_SHARPEN_SRC) { shader ->
        shader.setFloatUniform("imageWidth", bitmap.width.toFloat())
        shader.setFloatUniform("imageHeight", bitmap.height.toFloat())
        shader.setFloatUniform("amount", amount)
        shader.setFloatUniform("threshold", threshold)
    }

    private fun applyShader(
        bitmap: Bitmap,
        shaderSrc: String,
        configure: (RuntimeShader) -> Unit
    ): Boolean {
        return try {
            val shader = RuntimeShader(shaderSrc)
            shader.setInputShader("inputImage", BitmapShaderCompat.create(bitmap))
            configure(shader)

            val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(result)
            val paint = Paint().apply { this.shader = shader }
            canvas.drawPaint(paint)

            val srcCanvas = Canvas(bitmap)
            srcCanvas.drawBitmap(result, 0f, 0f, null)
            result.recycle()

            true
        } catch (e: Exception) {
            Log.w(TAG, "AGSL shader failed, falling back to CPU: ${e.message}")
            false
        }
    }
}

private object BitmapShaderCompat {
    fun create(bitmap: Bitmap): android.graphics.BitmapShader {
        return android.graphics.BitmapShader(
            bitmap,
            android.graphics.Shader.TileMode.CLAMP,
            android.graphics.Shader.TileMode.CLAMP
        )
    }
}
