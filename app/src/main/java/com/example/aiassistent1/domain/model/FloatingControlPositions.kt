package com.example.aiassistent1.domain.model

/**
 * Позиции плавающих элементов в dp. Y отсчитывается от нижнего края хедера.
 * Начальная расстановка снята с телефона: календарь выше карточки озвучивания.
 */
data class FloatingControlPositions(
    val speechCardXdp: Float = 0f,
    val speechCardYdp: Float = 120.5f,
    val calendarButtonXdp: Float = -5.5f,
    val calendarButtonYdp: Float = 47f,
    val isRelativeToHeader: Boolean = true,
)
