package com.example.aiassistent1.domain.model

object SpeechRate {
    const val MINIMUM = 0.5f
    const val MAXIMUM = 1.0f
    const val DEFAULT = 0.95f

    fun normalize(value: Float): Float =
        if (value.isFinite()) value.coerceIn(MINIMUM, MAXIMUM) else DEFAULT
}
