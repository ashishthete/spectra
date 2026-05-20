package com.spectra.camera.gpu

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.util.Log

object ProOverlayShaders {

    private const val TAG = "ProOverlayShaders"

    private val FOCUS_PEAKING_SRC = """
        uniform shader inputImage;
        uniform float imageWidth;
        uniform float imageHeight;
        uniform float threshold;
        uniform float peakR;
        uniform float peakG;
        uniform float peakB;

        half4 main(float2 coord) {
            half4 center = inputImage.eval(coord);
            float dx = 1.0 / imageWidth;
            float dy = 1.0 / imageHeight;

            float lumC = 0.299 * float(center.r) + 0.587 * float(center.g) + 0.114 * float(center.b);

            half4 top    = inputImage.eval(coord + float2(0, -dy));
            half4 bottom = inputImage.eval(coord + float2(0,  dy));
            half4 left   = inputImage.eval(coord + float2(-dx, 0));
            half4 right  = inputImage.eval(coord + float2( dx, 0));

            float lumT = 0.299 * float(top.r) + 0.587 * float(top.g) + 0.114 * float(top.b);
            float lumB = 0.299 * float(bottom.r) + 0.587 * float(bottom.g) + 0.114 * float(bottom.b);
            float lumL = 0.299 * float(left.r) + 0.587 * float(left.g) + 0.114 * float(left.b);
            float lumR = 0.299 * float(right.r) + 0.587 * float(right.g) + 0.114 * float(right.b);

            float edge = abs(4.0 * lumC - lumT - lumB - lumL - lumR);

            if (edge > threshold) {
                float alpha = clamp(edge * 3.0, 0.0, 0.8);
                float r = mix(float(center.r), peakR, alpha);
                float g = mix(float(center.g), peakG, alpha);
                float b = mix(float(center.b), peakB, alpha);
                return half4(half(r), half(g), half(b), center.a);
            }
            return center;
        }
    """.trimIndent()

    private val ZEBRA_STRIPES_SRC = """
        uniform shader inputImage;
        uniform float zebraThreshold;
        uniform float time;

        half4 main(float2 coord) {
            half4 c = inputImage.eval(coord);
            float lum = 0.299 * float(c.r) + 0.587 * float(c.g) + 0.114 * float(c.b);

            if (lum > zebraThreshold) {
                float stripe = mod(coord.x + coord.y + time * 50.0, 8.0);
                if (stripe < 4.0) {
                    return half4(half(mix(float(c.r), 1.0, 0.5)),
                                half(mix(float(c.g), 0.0, 0.4)),
                                half(mix(float(c.b), 0.0, 0.4)),
                                c.a);
                }
            }
            return c;
        }
    """.trimIndent()

    fun createFocusPeakingEffect(
        width: Float,
        height: Float,
        threshold: Float = 0.12f,
        peakColorR: Float = 0f,
        peakColorG: Float = 1f,
        peakColorB: Float = 0f
    ): RenderEffect? {
        return try {
            val shader = RuntimeShader(FOCUS_PEAKING_SRC)
            shader.setFloatUniform("imageWidth", width)
            shader.setFloatUniform("imageHeight", height)
            shader.setFloatUniform("threshold", threshold)
            shader.setFloatUniform("peakR", peakColorR)
            shader.setFloatUniform("peakG", peakColorG)
            shader.setFloatUniform("peakB", peakColorB)
            RenderEffect.createRuntimeShaderEffect(shader, "inputImage")
        } catch (e: Exception) {
            Log.w(TAG, "Focus peaking shader failed: ${e.message}")
            null
        }
    }

    fun createZebraEffect(
        threshold: Int = 235,
        animTime: Float = 0f
    ): RenderEffect? {
        return try {
            val shader = RuntimeShader(ZEBRA_STRIPES_SRC)
            shader.setFloatUniform("zebraThreshold", threshold / 255f)
            shader.setFloatUniform("time", animTime)
            RenderEffect.createRuntimeShaderEffect(shader, "inputImage")
        } catch (e: Exception) {
            Log.w(TAG, "Zebra shader failed: ${e.message}")
            null
        }
    }

    fun createCombinedEffect(
        width: Float,
        height: Float,
        peakingEnabled: Boolean,
        zebraEnabled: Boolean,
        peakingThreshold: Float = 0.12f,
        zebraThreshold: Int = 235,
        animTime: Float = 0f
    ): RenderEffect? {
        val effects = mutableListOf<RenderEffect>()

        if (peakingEnabled) {
            createFocusPeakingEffect(width, height, peakingThreshold)?.let { effects.add(it) }
        }
        if (zebraEnabled) {
            createZebraEffect(zebraThreshold, animTime)?.let { effects.add(it) }
        }

        if (effects.isEmpty()) return null
        if (effects.size == 1) return effects[0]
        return RenderEffect.createChainEffect(effects[1], effects[0])
    }
}
