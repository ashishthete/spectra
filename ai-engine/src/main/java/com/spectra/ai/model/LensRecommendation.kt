package com.spectra.ai.model

import com.spectra.core.model.LensId

data class LensRecommendation(
    val recommended: LensId,
    val scores: Map<LensId, Float>,
    val reason: String
)
