package com.example.aiassistent1.domain.usecase

import com.example.aiassistent1.domain.interfaces.CalendarProvider
import com.example.aiassistent1.domain.model.CalendarEvent

class SearchCalendarEventsUseCase(
    private val calendarProvider: CalendarProvider
) {
    suspend operator fun invoke(query: String, days: Int): Result<List<CalendarEvent>> =
        calendarProvider.searchEvents(query, days)
}
