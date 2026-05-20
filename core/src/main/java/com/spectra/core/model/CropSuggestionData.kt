package com.spectra.core.model

data class CropSuggestionData(
    val aspectRatio: String,
    val reason: String,
    val score: Float,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)
