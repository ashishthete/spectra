package com.spectra.core.model

enum class AspectRatio(val label: String, val ratioWidth: Int, val ratioHeight: Int) {
    RATIO_4_3("4:3", 4, 3),
    RATIO_16_9("16:9", 16, 9),
    RATIO_1_1("1:1", 1, 1),
    FULL("Full", 0, 0);
}
