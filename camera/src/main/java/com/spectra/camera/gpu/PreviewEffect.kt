package com.spectra.camera.gpu

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import com.spectra.camera.ToneCurveEngine
import com.spectra.core.model.PhotoStyle

object PreviewEffect {

    private val PREVIEW_SHADER_SRC = """
        uniform shader inputImage;
        uniform float shoulderStart;
        uniform float maxOutput;
        uniform float rolloffStrength;
        uniform float shadowStrength;

        float hableFilmic(float x) {
            float A = 0.15; float B = 0.50; float C = 0.10;
            float D = 0.20; float E = 0.02; float F = 0.30;
            return ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - E / F;
        }

        float shoulder(float v) {
            float ss = shoulderStart / 255.0;
            float mo = maxOutput / 255.0;
            if (v <= ss || rolloffStrength <= 0.0) return v;
            float range = max(1.0 - ss, 0.004);
            float t = (v - ss) / range;
            float whiteScale = hableFilmic(1.0);
            float tFilmic = hableFilmic(t) / whiteScale;
            float shoulderOut = ss + (mo - ss) * tFilmic;
            return v + rolloffStrength * (shoulderOut - v);
        }

        half4 main(float2 coord) {
            half4 c = inputImage.eval(coord);
            float r = clamp(float(c.r), 0.0, 1.0);
            float g = clamp(float(c.g), 0.0, 1.0);
            float b = clamp(float(c.b), 0.0, 1.0);

            // Shadow recovery
            if (shadowStrength > 0.0) {
                float lum = 0.299 * r + 0.587 * g + 0.114 * b;
                if (lum < 0.4) {
                    float factor = 1.0 - (lum / 0.4);
                    float boost = 1.0 + shadowStrength * factor * 0.6;
                    r = clamp(r * boost, 0.0, 1.0);
                    g = clamp(g * boost, 0.0, 1.0);
                    b = clamp(b * boost, 0.0, 1.0);
                }
            }

            // Highlight rolloff
            r = clamp(shoulder(r), 0.0, 1.0);
            g = clamp(shoulder(g), 0.0, 1.0);
            b = clamp(shoulder(b), 0.0, 1.0);

            return half4(half(r), half(g), half(b), c.a);
        }
    """.trimIndent()

    fun create(
        style: PhotoStyle,
        sceneContrast: Float = 0f
    ): RenderEffect? {
        return try {
            val params = ToneCurveEngine.styleHighlightParams(style)
            val shadowStr = if (sceneContrast > 0.15f) {
                com.spectra.camera.HdrProcessor.computeShadowBoostStrength(sceneContrast)
            } else 0f

            if (params.strength <= 0f && shadowStr <= 0f) return null

            val shader = RuntimeShader(PREVIEW_SHADER_SRC)
            shader.setFloatUniform("shoulderStart", params.shoulderStart.toFloat())
            shader.setFloatUniform("maxOutput", params.maxOutput.toFloat())
            shader.setFloatUniform("rolloffStrength", params.strength)
            shader.setFloatUniform("shadowStrength", shadowStr)

            RenderEffect.createRuntimeShaderEffect(shader, "inputImage")
        } catch (_: Exception) {
            null
        }
    }
}
