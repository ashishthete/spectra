package com.spectra.ai

import com.google.common.truth.Truth.assertThat
import com.spectra.core.model.SceneType
import org.junit.Test

class SceneClassifierModelTest {

    @Test
    fun `softmax normalization sums to 1`() {
        val raw = floatArrayOf(1.0f, 2.0f, 0.5f, 3.0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f)
        val normalized = applySoftmax(raw)
        val sum = normalized.sum()
        assertThat(sum).isWithin(0.001f).of(1.0f)
    }

    @Test
    fun `softmax preserves ordering`() {
        val raw = floatArrayOf(1.0f, 5.0f, 0.5f, 3.0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f)
        val normalized = applySoftmax(raw)
        val maxIdx = normalized.indices.maxByOrNull { normalized[it] }!!
        assertThat(maxIdx).isEqualTo(1)
    }

    @Test
    fun `softmax handles uniform inputs`() {
        val raw = FloatArray(11) { 1.0f }
        val normalized = applySoftmax(raw)
        for (v in normalized) {
            assertThat(v).isWithin(0.01f).of(1.0f / 11f)
        }
    }

    @Test
    fun `topKConfident returns top result correctly`() {
        val scores = floatArrayOf(0.1f, 0.6f, 0.05f, 0.15f, 0.02f, 0.01f, 0.01f, 0.02f, 0.01f, 0.02f, 0.01f)
        val labels = SceneType.entries.toList()
        val result = topKConfident(scores, labels, 1)
        assertThat(result).hasSize(1)
        assertThat(result[0].first).isEqualTo(SceneType.PORTRAIT)
        assertThat(result[0].second).isWithin(0.001f).of(0.6f)
    }

    private fun applySoftmax(input: FloatArray): FloatArray {
        val maxVal = input.max()
        val exps = FloatArray(input.size) { kotlin.math.exp((input[it] - maxVal).toDouble()).toFloat() }
        val sum = exps.sum()
        return FloatArray(exps.size) { exps[it] / sum }
    }

    private fun topKConfident(scores: FloatArray, labels: List<SceneType>, k: Int): List<Pair<SceneType, Float>> {
        return scores.indices
            .sortedByDescending { scores[it] }
            .take(k)
            .map { idx -> Pair(if (idx < labels.size) labels[idx] else SceneType.UNKNOWN, scores[idx]) }
    }
}
