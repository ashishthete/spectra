package com.spectra.ai.cloud

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CloudCoachingClientTest {

    @Test
    fun buildPrompt_includesSceneType() {
        val prompt = CloudCoachingClient.buildPrompt("LANDSCAPE", "GOLDEN_HOUR")
        assertThat(prompt).contains("LANDSCAPE")
        assertThat(prompt).contains("GOLDEN_HOUR")
    }

    @Test
    fun buildPrompt_includesFormatInstructions() {
        val prompt = CloudCoachingClient.buildPrompt("PORTRAIT", "BRIGHT_DAYLIGHT")
        assertThat(prompt).contains("directive")
    }

    @Test
    fun buildRequest_createsValidStructure() {
        val base64 = "dGVzdA=="
        val request = CloudCoachingClient.buildRequest(base64, "FOOD", "ARTIFICIAL")
        assertThat(request.messages).hasSize(1)
        assertThat(request.messages[0].content).hasSize(2)
        assertThat(request.messages[0].content[0].type).isEqualTo("image")
        assertThat(request.messages[0].content[0].source?.data).isEqualTo(base64)
        assertThat(request.messages[0].content[1].type).isEqualTo("text")
    }

    @Test
    fun parseResponse_extractsFirstDirective() {
        val responseText = "TILT UP 15° · RULE OF THIRDS\nMOVE LEFT · LEADING LINES"
        val hints = CloudCoachingClient.parseResponseText(responseText)
        assertThat(hints).isNotEmpty()
        assertThat(hints[0].text).isEqualTo("TILT UP 15° · RULE OF THIRDS")
    }

    @Test
    fun parseResponse_emptyText_returnsEmpty() {
        val hints = CloudCoachingClient.parseResponseText("")
        assertThat(hints).isEmpty()
    }
}
