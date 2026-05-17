package com.spectra.ai.model

enum class ArrowDirection {
    UP, DOWN, LEFT, RIGHT, STEADY, NONE;
}

data class CoachingHint(
    val text: String,
    val arrow: ArrowDirection = ArrowDirection.NONE,
    val priority: Int = 0
)
