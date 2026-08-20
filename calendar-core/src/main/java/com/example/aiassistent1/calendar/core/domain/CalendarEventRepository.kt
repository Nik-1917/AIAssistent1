package com.example.aiassistent1.calendar.core.domain

import kotlinx.coroutines.flow.Flow

interface CalendarEventRepository {
    suspend fun create(draft: CalendarEventDraft): Result<CalendarEvent>

    suspend fun getById(id: String): Result<CalendarEvent?>

    fun observeInRange(
        rangeStartEpochMillis: Long,
        rangeEndEpochMillis: Long,
    ): Flow<List<CalendarEvent>>

    suspend fun update(update: CalendarEventUpdate): Result<CalendarEvent>

    suspend fun delete(id: String): Result<Unit>

    suspend fun search(
        query: String,
        rangeStartEpochMillis: Long,
        rangeEndEpochMillis: Long,
    ): Result<List<CalendarEvent>>
}
