package com.example.aiassistent1.domain.model

/**
 * Позиции плавающих элементов в dp, чтобы они переживали пересоздание экрана.
 */
data class FloatingControlPositions(
    val speechCardXdp: Float = -7f,
    val speechCardYdp: Float = 72f,
    val calendarButtonXdp: Float = -7f,
    val calendarButtonYdp: Float = 0f,
)
