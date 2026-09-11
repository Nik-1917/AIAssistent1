package com.example.aiassistent1.calendar.core.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

class CalendarNotesCommandTest {
    @Test fun `sum ignores numbers inside notes`() = runTest {
        val repository = RecordingRepository(listOf(
            CalendarEvent("one", "Встреча", 60000, 120000, 0, 0, value = 17, notes = "12500"),
            CalendarEvent("two", "Встреча", 60000, 120000, 0, 0, notes = "999999"),
        ))
        val result = CalendarCommandExecutor(repository).execute(
            CalendarCommand.Sum(null, CalendarRange(0, 180000)), "sum",
        ).getOrThrow() as CalendarCommandResult.Sum
        assertEquals(java.math.BigInteger.valueOf(17), result.total)
        assertEquals(2, result.matchedCount)
        assertEquals(1, result.valueCount)
    }

    @Test fun `notes are optional and do not satisfy missing value`() = runTest {
        for (notes in listOf(null, "", "Текст 12500")) {
            val repository = RecordingRepository()
            val executor = CalendarCommandExecutor(repository, ZoneOffset.UTC)
            val command = CalendarCommand.Add("Встреча", LocalDate.of(2026, 9, 11), LocalTime.of(14, 30), 20, notes = notes)
            val incomplete = executor.execute(command, "request", confirmed = true).getOrThrow() as CalendarCommandResult.NeedsFields
            assertEquals(listOf(MissingCalendarField.VALUE), incomplete.fields)
            assertNull(repository.created)
            assertTrue(executor.execute(command.copy(value = 0), "request").getOrThrow() is CalendarCommandResult.AddDraft)
            assertNull(repository.created)
            executor.execute(command.copy(value = 0), "request", confirmed = true).getOrThrow()
            assertEquals(notes, repository.created!!.notes)
            assertEquals(0L, repository.created!!.value)
        }
    }

    @Test fun `notes-only update preserves all other fields and revision guard`() {
        val event = CalendarEvent("id", "Встреча", 60000, 120000, 0, 0, value = 17, revision = 4, notes = "Старое")
        val update = PrepareCalendarEventUpdateUseCase(ZoneOffset.UTC)(event, CalendarEventChanges(notes = "  Новое\n  ")).getOrThrow()
        assertEquals("  Новое\n  ", update.notes)
        assertEquals(event.title, update.title)
        assertEquals(event.startsAtEpochMillis, update.startsAtEpochMillis)
        assertEquals(event.endsAtEpochMillis, update.endsAtEpochMillis)
        assertEquals(CalendarValueChange.Keep, update.valueChange)
        assertEquals(event.revision, update.expectedRevision)
        for (notes in listOf(null, "", " \n ")) {
            assertTrue(CalendarEventChanges(notes = notes).isEmpty)
            val otherChange = PrepareCalendarEventUpdateUseCase(ZoneOffset.UTC)(event, CalendarEventChanges(title = "Новое название", notes = notes)).getOrThrow()
            assertNull(otherChange.notes)
        }
    }

    private class RecordingRepository(private val events: List<CalendarEvent> = emptyList()) : CalendarEventRepository {
        var created: CalendarEventDraft? = null
        override suspend fun commit(requestId: String, mutation: CalendarMutation): Result<CalendarReceipt> {
            created = (mutation as CalendarMutation.Create).draft
            return Result.success(CalendarReceipt(requestId, "calendar_add", "id", created!!.title, created!!.notes))
        }
        override suspend fun create(draft: CalendarEventDraft): Result<CalendarEvent> = error("Not used")
        override suspend fun getById(id: String): Result<CalendarEvent?> = error("Not used")
        override fun observeInRange(rangeStartEpochMillis: Long, rangeEndEpochMillis: Long): Flow<List<CalendarEvent>> = emptyFlow()
        override suspend fun update(update: CalendarEventUpdate): Result<CalendarEvent> = error("Not used")
        override suspend fun delete(id: String): Result<Unit> = error("Not used")
        override suspend fun search(query: String, rangeStartEpochMillis: Long, rangeEndEpochMillis: Long): Result<List<CalendarEvent>> = Result.success(events)
        override suspend fun findForUpdate(query: String, rangeStartEpochMillis: Long?, rangeEndEpochMillis: Long?): Result<List<CalendarEvent>> = error("Not used")
        override suspend fun getLastCreated(): Result<CalendarEvent?> = error("Not used")
        override suspend fun getLastInRange(rangeStartEpochMillis: Long, rangeEndEpochMillis: Long): Result<CalendarEvent?> = error("Not used")
    }
}
