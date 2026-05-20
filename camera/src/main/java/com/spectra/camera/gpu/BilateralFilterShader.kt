package com.spectra.camera.gpu

import android.util.Log

object BilateralFilterShader {

    private const val TAG = "BilateralFilterGPU"

    private fun shaderSource(spatialRadius: Int) = """
        #version 310 es
        layout(local_size_x = 16, local_size_y = 16) in;

        layout(std430, binding = 0) readonly buffer Input  { float inData[];  };
        layout(std430, binding = 1) writeonly buffer Output { float outData[]; };

        uniform int uWidth;
        uniform int uHeight;
        uniform float uRangeSigmaSq2;
        uniform float uSpatialSigmaSq2;

        void main() {
            int x = int(gl_GlobalInvocationID.x);
            int y = int(gl_GlobalInvocationID.y);
            if (x >= uWidth || y >= uHeight) return;

            int idx = y * uWidth + x;
            float centerVal = inData[idx];
            float weightSum = 0.0;
            float valueSum = 0.0;

            int r = $spatialRadius;
            for (int ny = max(0, y - r); ny <= min(uHeight - 1, y + r); ny++) {
                for (int nx = max(0, x - r); nx <= min(uWidth - 1, x + r); nx++) {
                    float nVal = inData[ny * uWidth + nx];
                    float dx = float(nx - x);
                    float dy = float(ny - y);
                    float spatialW = exp(-(dx * dx + dy * dy) / uSpatialSigmaSq2);
                    float rangeDiff = nVal - centerVal;
                    float rangeW = exp(-(rangeDiff * rangeDiff) / uRangeSigmaSq2);
                    float w = spatialW * rangeW;
                    weightSum += w;
                    valueSum += nVal * w;
                }
            }

            outData[idx] = (weightSum > 0.0) ? (valueSum / weightSum) : centerVal;
        }
    """.trimIndent()

    fun filter(
        gpuContext: GpuContext,
        channel: IntArray,
        w: Int, h: Int,
        spatialRadius: Int,
        rangeSigma: Float
    ): IntArray? {
        if (!gpuContext.isAvailable) return null

        return try {
            gpuContext.makeCurrent()

            val src = shaderSource(spatialRadius)
            val shader = ComputeShader(src)
            if (shader.programId == 0) return null

            val n = w * h
            val inputF = FloatArray(n) { channel[it].toFloat() }
            val outputF = FloatArray(n)

            shader.setBuffer(0, inputF)
            shader.setBuffer(1, outputF)
            shader.setUniform("uWidth", w)
            shader.setUniform("uHeight", h)
            shader.setUniformFloat("uRangeSigmaSq2", 2f * rangeSigma * rangeSigma)
            val spatialSigma = spatialRadius / 2f
            shader.setUniformFloat("uSpatialSigmaSq2", 2f * spatialSigma * spatialSigma)

            val groupsX = (w + 15) / 16
            val groupsY = (h + 15) / 16
            shader.dispatch(groupsX, groupsY)
            shader.readBuffer(1, outputF)
            shader.release()

            val result = IntArray(n) { outputF[it].toInt().coerceIn(0, 255) }
            Log.d(TAG, "GPU bilateral: ${w}x${h}, r=$spatialRadius, sigma=$rangeSigma")
            result
        } catch (e: Exception) {
            Log.w(TAG, "GPU bilateral failed: ${e.message}")
            null
        }
    }
}
