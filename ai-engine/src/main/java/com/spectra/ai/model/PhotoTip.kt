package com.spectra.ai.model

import com.spectra.core.model.SceneType

data class PhotoTip(
    val sceneType: SceneType,
    val title: String,
    val tips: List<String>,
    val referenceImageAsset: String
)
