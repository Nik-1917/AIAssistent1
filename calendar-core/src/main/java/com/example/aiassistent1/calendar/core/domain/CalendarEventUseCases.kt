package com.example.aiassistent1.calendar.core.domain

import kotlinx.coroutines.flow.Flow

class CreateCalendarEventUseCase(
    private val repository: CalendarEventRepository,
) {
    suspend operator fun invoke(draft: CalendarEventDraft): Result<CalendarEvent> = repository.create(draft)
}

class GetCalendarEventUseCase(
    private val repository: CalendarEventRepository,
) {
    suspend operator fun invoke(id: String): Result<CalendarEvent?> = repository.getById(id)
}

class ObserveCalendarEventsUseCase(
    private val repository: CalendarEventRepository,
) {
    operator fun invoke(
        rangeStartEpochMillis: Long,
        rangeEndEpochMillis: Long,
    ): Flow<List<CalendarEvent>> = repository.observeInRange(
        rangeStartEpochMillis,
        rangeEndEpochMillis,
    )
}

class UpdateCalendarEventUseCase(
    private val repository: CalendarEventRepository,
) {
    suspend operator fun invoke(update: CalendarEventUpdate): Result<CalendarEvent> = repository.update(update)
}

class DeleteCalendarEventUseCase(
    private val repository: CalendarEventRepository,
) {
    suspend operator fun invoke(id: String): Result<Unit> = repository.delete(id)
}

class SearchCalendarEventsUseCase(
    private val repository: CalendarEventRepository,
) {
    suspend operator fun invoke(
        query: String,
        rangeStartEpochMillis: Long,
        rangeEndEpochMillis: Long,
    ): Result<List<CalendarEvent>> = repository.search(
        query,
        rangeStartEpochMillis,
        rangeEndEpochMillis,
    )
}
