package com.example.aiassistent1.calendar.core.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CalendarSearchWithoutRangeTest {
    @Test fun `search by title does not require a period`() = runTest {
        val repository = RecordingSearchRepository()
        val result = CalendarCommandExecutor(repository).execute(
            CalendarCommand.Search("поход за грибами", null), "search",
        ).getOrThrow()
        assertTrue(result is CalendarCommandResult.Found)
        assertEquals("поход за грибами", repository.query)
        assertEquals(Long.MIN_VALUE + 1, repository.start)
        assertEquals(Long.MAX_VALUE, repository.end)
    }

    @Test fun `search with period keeps the supplied range`() = runTest {
        val repository = RecordingSearchRepository()
        CalendarCommandExecutor(repository).execute(
            CalendarCommand.Search("книга", CalendarRange(100, 200)), "search",
        ).getOrThrow()
        assertEquals(100L, repository.start)
        assertEquals(200L, repository.end)
    }

    @Test fun `missing query still requires clarification`() = runTest {
        val result = CalendarCommandExecutor(RecordingSearchRepository()).execute(
            CalendarCommand.Search(null, null), "search",
        ).getOrThrow() as CalendarCommandResult.NeedsFields
        assertEquals(listOf(MissingCalendarField.QUERY), result.fields)
    }

    private class RecordingSearchRepository : CalendarEventRepository {
        var query: String? = null
        var start: Long? = null
        var end: Long? = null
        override suspend fun search(query: String, rangeStartEpochMillis: Long, rangeEndEpochMillis: Long): Result<List<CalendarEvent>> {
            this.query = query; start = rangeStartEpochMillis; end = rangeEndEpochMillis
            return Result.success(emptyList())
        }
        override suspend fun create(draft: CalendarEventDraft): Result<CalendarEvent> = error("Not used")
        override suspend fun getById(id: String): Result<CalendarEvent?> = error("Not used")
        override fun observeInRange(rangeStartEpochMillis: Long, rangeEndEpochMillis: Long): Flow<List<CalendarEvent>> = emptyFlow()
        override suspend fun update(update: CalendarEventUpdate): Result<CalendarEvent> = error("Not used")
        override suspend fun delete(id: String): Result<Unit> = error("Not used")
        override suspend fun findForUpdate(query: String, rangeStartEpochMillis: Long?, rangeEndEpochMillis: Long?): Result<List<CalendarEvent>> = error("Not used")
        override suspend fun getLastCreated(): Result<CalendarEvent?> = error("Not used")
        override suspend fun getLastInRange(rangeStartEpochMillis: Long, rangeEndEpochMillis: Long): Result<CalendarEvent?> = error("Not used")
    }
}
