package com.example.aiassistent1.calendar.core.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class CalendarDeleteExecutionTest {
    private val utc = ZoneOffset.UTC
    private val clock = Clock.fixed(Instant.parse("2026-09-18T12:00:00Z"), utc)
    private fun epoch(value: String) = CalendarTime.toEpochMillis(CalendarTime.dateTime(value), utc)
    private fun range(start: String = "2026-09-18T00:00", end: String = "2026-09-19T00:00") =
        CalendarRange(epoch(start), epoch(end))
    private fun event(id: String, start: String, end: String, title: String = "Встреча") =
        CalendarEvent(id, title, epoch(start), epoch(end), 0, 0, revision = 3, notes = "Заметка")
    private fun command(query: String? = "Встреча", period: CalendarRange? = range()) =
        CalendarCommand.Delete(CalendarDeleteTarget(query, period))
    private fun executor(repository: Repository) = CalendarCommandExecutor(repository, utc, clock)

    @Test fun `only the exact title and starting minute are automatically deleted`() = runTest {
        val exact = event("exact", "2026-09-18T11:15", "2026-09-18T11:45", " ВСТРЕЧА ")
            .let { it.copy(startsAtEpochMillis = it.startsAtEpochMillis + 30_000) }
        val repository = Repository(listOf(
            event("overlap", "2026-09-18T11:00", "2026-09-18T12:00"),
            event("later", "2026-09-18T11:30", "2026-09-18T12:00"),
            event("partial", "2026-09-18T11:15", "2026-09-18T12:00", "Встреча с врачом"),
            exact,
        ))
        val result = executor(repository).execute(
            command(" встреча ", range("2026-09-18T11:15", "2026-09-18T12:00")), "exact",
        ).getOrThrow() as CalendarCommandResult.Completed
        assertEquals("exact", result.receipt.eventId)
        assertEquals("Заметка", result.receipt.notes)
        assertEquals(listOf(CalendarMutation.Delete("exact", 3)), repository.mutations)
        assertEquals(setOf("overlap", "later", "partial"), repository.events.keys)
    }

    @Test fun `an overlapping event or partial title alone requires selection`() = runTest {
        for (candidate in listOf(
            event("overlap", "2026-09-18T10:30", "2026-09-18T12:00"),
            event("partial", "2026-09-18T11:00", "2026-09-18T12:00", "Встреча с врачом"),
            event("later", "2026-09-18T11:01", "2026-09-18T12:00"),
        )) {
            val repository = Repository(listOf(candidate))
            val result = executor(repository).execute(
                command(period = range("2026-09-18T11:00", "2026-09-18T12:00")), "select",
            ).getOrThrow() as CalendarCommandResult.Selection
            assertEquals(listOf(candidate), result.candidates)
            assertTrue(repository.mutations.isEmpty())
        }
    }

    @Test fun `duplicate exact matches require selecting one of those matches`() = runTest {
        val first = event("one", "2026-09-18T11:00", "2026-09-18T12:00")
        val second = first.copy(id = "two")
        val repository = Repository(listOf(first, second,
            event("overlap", "2026-09-18T10:30", "2026-09-18T12:00")))
        val executor = executor(repository)
        val selection = executor.execute(
            command(period = range("2026-09-18T11:00", "2026-09-18T12:00")), "duplicates",
        ).getOrThrow() as CalendarCommandResult.Selection
        assertEquals(listOf(first, second), selection.candidates)
        assertTrue(repository.mutations.isEmpty())
        executor.execute(selection.command, "duplicates", confirmed = true, selectedEvent = second).getOrThrow()
        assertEquals(setOf("one", "overlap"), repository.events.keys)
    }

    @Test fun `full day and multiple whole days require selection even for one midnight event`() = runTest {
        val candidate = event("midnight", "2026-09-18T00:00", "2026-09-18T01:00")
        for (end in listOf("2026-09-19T00:00", "2026-09-21T00:00")) {
            val repository = Repository(listOf(candidate))
            val selection = executor(repository).execute(command(period = range(end = end)), "day")
                .getOrThrow() as CalendarCommandResult.Selection
            assertEquals(listOf(candidate), selection.candidates)
            assertTrue(repository.mutations.isEmpty())
        }
    }

    @Test fun `midnight with an explicit narrow period still permits exact deletion`() = runTest {
        val repository = Repository(listOf(event("midnight", "2026-09-18T00:00", "2026-09-18T01:00")))
        assertTrue(executor(repository).execute(command(period = range(end = "2026-09-18T01:00")), "midnight")
            .getOrThrow() is CalendarCommandResult.Completed)
    }

    @Test fun `missing title lists all overlapping events in the supplied period`() = runTest {
        val first = event("one", "2026-09-18T10:30", "2026-09-18T11:30", "Работа")
        val second = event("two", "2026-09-18T11:30", "2026-09-18T12:30", "Отдых")
        val repository = Repository(listOf(first, second,
            event("before", "2026-09-18T10:00", "2026-09-18T11:00"),
            event("after", "2026-09-18T12:00", "2026-09-18T13:00")))
        val selection = executor(repository).execute(
            command(null, range("2026-09-18T11:00", "2026-09-18T12:00")), "all",
        ).getOrThrow() as CalendarCommandResult.Selection
        assertEquals(listOf(first, second), selection.candidates)
        assertTrue(repository.mutations.isEmpty())
    }

    @Test fun `missing date limits the list to today and retains a supplied name filter`() = runTest {
        val today = event("today", "2026-09-18T11:00", "2026-09-18T12:00")
        val other = today.copy(id = "other", title = "Работа")
        val repository = Repository(listOf(today, other,
            event("yesterday", "2026-09-17T11:00", "2026-09-17T12:00"),
            event("tomorrow", "2026-09-19T11:00", "2026-09-19T12:00")))
        val named = executor(repository).execute(command(period = null), "named").getOrThrow() as CalendarCommandResult.Selection
        assertEquals(listOf(today), named.candidates)
        val all = executor(repository).execute(command(null, null), "all").getOrThrow() as CalendarCommandResult.Selection
        assertEquals(setOf(today, other), all.candidates.toSet())
        assertEquals(range(), (all.command as CalendarCommand.Delete).target.range)
        assertTrue(repository.mutations.isEmpty())
    }

    @Test fun `today uses the phone zone and selection keeps its original date after midnight`() = runTest {
        val samara = ZoneId.of("Europe/Samara")
        val beforeMidnight = Clock.fixed(Instant.parse("2026-09-18T20:30:00Z"), utc)
        val candidate = event("localToday", "2026-09-18T21:00", "2026-09-18T22:00")
        val repository = Repository(listOf(candidate))
        val selection = CalendarCommandExecutor(repository, samara, beforeMidnight)
            .execute(command(null, null), "zone").getOrThrow() as CalendarCommandResult.Selection
        assertEquals(listOf(candidate), selection.candidates)
        assertEquals(range("2026-09-18T20:00", "2026-09-19T20:00"),
            (selection.command as CalendarCommand.Delete).target.range)
        val nextDay = Clock.fixed(Instant.parse("2026-09-19T20:30:00Z"), utc)
        CalendarCommandExecutor(repository, samara, nextDay)
            .execute(selection.command, "zone", confirmed = true, selectedEvent = candidate).getOrThrow()
        assertEquals(listOf(CalendarMutation.Delete(candidate.id, candidate.revision)), repository.mutations)
    }

    @Test fun `empty results return an empty card without deleting anything`() = runTest {
        val repository = Repository()
        val selection = executor(repository).execute(command(), "empty").getOrThrow() as CalendarCommandResult.Selection
        assertTrue(selection.candidates.isEmpty())
        assertTrue(repository.mutations.isEmpty())
    }

    @Test fun `selection deletes only its chosen id and repeated request cannot delete the remaining event`() = runTest {
        val first = event("one", "2026-09-18T11:00", "2026-09-18T12:00")
        val second = first.copy(id = "two")
        val repository = Repository(listOf(first, second))
        val executor = executor(repository)
        val selection = executor.execute(command(), "request").getOrThrow() as CalendarCommandResult.Selection
        assertTrue(repository.mutations.isEmpty())
        val result = executor.execute(selection.command, "request", confirmed = true, selectedEvent = second).getOrThrow()
        assertEquals(result, executor.execute(selection.command, "request", confirmed = true, selectedEvent = first).getOrThrow())
        assertEquals(setOf("one"), repository.events.keys)
        assertEquals(1, repository.mutations.size)
    }

    @Test fun `a changed event keeps the selected revision guard`() = runTest {
        val candidate = event("one", "2026-09-18T11:00", "2026-09-18T12:00")
        val repository = Repository(listOf(candidate))
        val executor = executor(repository)
        val selection = executor.execute(command(), "stale").getOrThrow() as CalendarCommandResult.Selection
        repository.events[candidate.id] = candidate.copy(revision = 4)
        val result = executor.execute(selection.command, "stale", confirmed = true, selectedEvent = candidate)
        assertTrue(result.exceptionOrNull() is CalendarConflictException)
        assertTrue(repository.mutations.isEmpty())
        assertEquals(4L, repository.events[candidate.id]!!.revision)
    }

    @Test fun `selection cannot delete an event outside the displayed period or without user selection`() = runTest {
        val outside = event("outside", "2026-09-19T11:00", "2026-09-19T12:00")
        val inside = event("inside", "2026-09-18T11:00", "2026-09-18T12:00")
        val repository = Repository(listOf(outside, inside))
        val executor = executor(repository)
        assertTrue(executor.execute(command(), "outside", confirmed = true, selectedEvent = outside).isFailure)
        assertTrue(executor.execute(command(), "unconfirmed", selectedEvent = inside).isFailure)
        assertTrue(repository.mutations.isEmpty())
    }

    @Test fun `explicit last selectors retain immediate deletion`() = runTest {
        val first = event("one", "2026-09-18T11:00", "2026-09-18T12:00").copy(createdAtEpochMillis = 2)
        val second = event("two", "2026-09-18T13:00", "2026-09-18T14:00").copy(createdAtEpochMillis = 1)
        val repository = Repository(listOf(first, second))
        val executor = executor(repository)
        val lastCreated = executor.execute(CalendarCommand.Delete(CalendarDeleteTarget(useLastCreated = true)), "created")
            .getOrThrow() as CalendarCommandResult.Completed
        assertEquals("one", lastCreated.receipt.eventId)
        val lastInRange = executor.execute(CalendarCommand.Delete(CalendarDeleteTarget(range = range(), useLastInRange = true)), "range")
            .getOrThrow() as CalendarCommandResult.Completed
        assertEquals("two", lastInRange.receipt.eventId)
    }

    @Test fun `update keeps its original overlap and partial name resolution`() = runTest {
        val candidate = event("one", "2026-09-18T10:30", "2026-09-18T12:00", "Встреча с врачом")
        val repository = Repository(listOf(candidate))
        val period = range("2026-09-18T11:00", "2026-09-18T12:00")
        val update = CalendarCommand.Update(CalendarUpdateTarget(CalendarTargetMode.BY_QUERY, "Встреча", period.start, period.end),
            CalendarEventChanges(title = "Новое"))
        val draft = executor(repository).execute(update, "update").getOrThrow() as CalendarCommandResult.UpdateDraft
        assertEquals(candidate, draft.event)
        assertTrue(repository.mutations.isEmpty())
    }

    private class Repository(initial: List<CalendarEvent> = emptyList()) : CalendarEventRepository {
        val events = initial.associateByTo(linkedMapOf()) { it.id }
        val mutations = mutableListOf<CalendarMutation>()
        private val receipts = mutableMapOf<String, CalendarReceipt>()
        override suspend fun getReceipt(requestId: String) = Result.success(receipts[requestId])
        override suspend fun commit(requestId: String, mutation: CalendarMutation): Result<CalendarReceipt> = runCatching {
            receipts[requestId]?.let { return@runCatching it }
            val deletion = mutation as CalendarMutation.Delete
            val event = events[deletion.id] ?: throw CalendarConflictException()
            if (deletion.expectedRevision != event.revision) throw CalendarConflictException()
            events.remove(event.id)
            mutations.add(deletion)
            CalendarReceipt(requestId, "calendar_delete", event.id, event.title, event.notes).also { receipts[requestId] = it }
        }
        override suspend fun search(query: String, rangeStartEpochMillis: Long, rangeEndEpochMillis: Long) = Result.success(
            events.values.filter { it.startsAtEpochMillis < rangeEndEpochMillis && it.endsAtEpochMillis > rangeStartEpochMillis &&
                it.title.contains(query.trim(), ignoreCase = true) }.sortedWith(compareBy({ it.startsAtEpochMillis }, { it.id })),
        )
        override suspend fun findForUpdate(query: String, rangeStartEpochMillis: Long?, rangeEndEpochMillis: Long?) =
            search(query, rangeStartEpochMillis ?: Long.MIN_VALUE, rangeEndEpochMillis ?: Long.MAX_VALUE)
        override suspend fun getLastCreated() = Result.success(events.values.maxByOrNull { it.createdAtEpochMillis })
        override suspend fun getLastInRange(rangeStartEpochMillis: Long, rangeEndEpochMillis: Long) =
            Result.success(search("", rangeStartEpochMillis, rangeEndEpochMillis).getOrThrow().lastOrNull())
        override suspend fun getById(id: String) = Result.success(events[id])
        override suspend fun create(draft: CalendarEventDraft): Result<CalendarEvent> = error("Not used")
        override fun observeInRange(rangeStartEpochMillis: Long, rangeEndEpochMillis: Long): Flow<List<CalendarEvent>> = emptyFlow()
        override suspend fun update(update: CalendarEventUpdate): Result<CalendarEvent> = error("Not used")
        override suspend fun delete(id: String): Result<Unit> = error("Use commit")
    }
}
