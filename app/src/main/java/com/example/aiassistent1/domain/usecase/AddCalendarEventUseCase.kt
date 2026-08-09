package com.example.aiassistent1.domain.usecase

import com.example.aiassistent1.domain.interfaces.CalendarProvider

class AddCalendarEventUseCase(
    private val calendarProvider: CalendarProvider
) {
    suspend operator fun invoke(title: String, dateTime: String, durationMin: Int): Result<Unit> {
        return calendarProvider.addEvent(title, dateTime, durationMin)
    }
}
