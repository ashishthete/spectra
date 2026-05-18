package com.spectra.core.model

enum class PhotoStyle(val label: String, val icon: String) {
    NATURAL("Natural", "○"),
    VIVID("Vivid", "◉"),
    WARM("Warm", "◎"),
    FILM("Film", "◐"),
    CINEMATIC("Cine", "◑");

    val isIdentity: Boolean get() = this == NATURAL

    val hasLiftedBlacks: Boolean get() = this == FILM || this == CINEMATIC

    val hasCrossChannelTint: Boolean get() = this == CINEMATIC || this == FILM
}
