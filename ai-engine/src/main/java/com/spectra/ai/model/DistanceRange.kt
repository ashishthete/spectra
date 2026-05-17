package com.spectra.ai.model

enum class DistanceRange(val label: String) {
    MACRO("< 0.1M"),
    NEAR("0.1-1M"),
    MID("1-5M"),
    FAR("5-20M"),
    INFINITY("∞ FAR");
}
