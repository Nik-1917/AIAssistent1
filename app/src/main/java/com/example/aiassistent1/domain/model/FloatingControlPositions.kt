package com.example.aiassistent1.domain.model

/**
 * Позиции плавающих элементов в dp, чтобы они переживали пересоздание экрана.
 */
data class FloatingControlPositions(
    val speechCardXdp: Float = 0f,
    val speechCardYdp: Float = 0f,
    val calendarButtonXdp: Float = 0f,
    val calendarButtonYdp: Float = 0f,
)
