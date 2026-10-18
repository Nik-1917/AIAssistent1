package com.example.aiassistent1.domain.model

enum class ScreenOrientation {
    PORTRAIT,
    LANDSCAPE,
}

data class ScreenPreferences(
    val orientationLocked: Boolean = false,
    val orientation: ScreenOrientation = ScreenOrientation.PORTRAIT,
    val proximityScreenOffEnabled: Boolean = true,
)
