package com.spectra.ai.cloud

import com.google.common.truth.Truth.assertThat
import com.spectra.ai.model.ArrowDirection
import org.junit.Test

class CloudCoachingModelsTest {

    @Test
    fun parseDirective_validLine_parsesTextAndArrow() {
        val line = "TILT UP 10° · GOLDEN RATIO ALIGN"
        val directive = CloudDirectiveParser.parse(line)
        assertThat(directive.text).isEqualTo("TILT UP 10° · GOLDEN RATIO ALIGN")
        assertThat(directive.arrow).isEqualTo(ArrowDirection.UP)
    }

    @Test
    fun parseDirective_downKeyword_returnsDown() {
        val line = "LOWER ANGLE · DRAMATIC FOREGROUND"
        val directive = CloudDirectiveParser.parse(line)
        assertThat(directive.arrow).isEqualTo(ArrowDirection.DOWN)
    }

    @Test
    fun parseDirective_leftKeyword_returnsLeft() {
        val line = "STEP LEFT 0.5M · LEADING LINES"
        val directive = CloudDirectiveParser.parse(line)
        assertThat(directive.arrow).isEqualTo(ArrowDirection.LEFT)
    }

    @Test
    fun parseDirective_rightKeyword_returnsRight() {
        val line = "MOVE RIGHT · BALANCE COMPOSITION"
        val directive = CloudDirectiveParser.parse(line)
        assertThat(directive.arrow).isEqualTo(ArrowDirection.RIGHT)
    }

    @Test
    fun parseDirective_steadyKeyword_returnsSteady() {
        val line = "HOLD STEADY · PERFECT FRAMING"
        val directive = CloudDirectiveParser.parse(line)
        assertThat(directive.arrow).isEqualTo(ArrowDirection.STEADY)
    }

    @Test
    fun parseDirective_noDirectionKeyword_returnsNone() {
        val line = "NICE COMPOSITION · SHOOT NOW"
        val directive = CloudDirectiveParser.parse(line)
        assertThat(directive.arrow).isEqualTo(ArrowDirection.NONE)
    }
}
