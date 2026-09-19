package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.calendar.core.domain.*
import com.example.aiassistent1.domain.mapper.CalendarCommandMapper
import com.example.aiassistent1.domain.parser.AssistantResponseParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset

class CalendarDeletePeriodFlowTest {
    private val zone = ZoneOffset.UTC
    private val rawRange = """"range_start":"2026-09-16T00:00","range_end":"2026-09-17T00:00" """
    private fun command(nested: Boolean = false): CalendarCommand.Delete {
        val params = if (nested) """"target":{$rawRange}""" else rawRange
        val parsed = AssistantResponseParser(zone).parseResult(
            """{"intent":"calendar_delete","reply":"События удалены за шестнадцатое число.","params":{$params}}""",
        ).getOrThrow()
        return CalendarCommandMapper(zone).map(parsed.params!!).getOrThrow() as CalendarCommand.Delete
    }
    private fun event(id: String, day: Int = 16): CalendarEvent {
        val start = CalendarTime.toEpochMillis(CalendarTime.dateTime("2026-09-${day}T12:00"), zone)
        return CalendarEvent(id, "Событие $id", start, start + 60_000L, 0, 0, revision = 2)
    }
    private suspend fun card(executor: CalendarCommandExecutor, nested: Boolean = false): CalendarDeleteTargetSelectionUiState {
        val result = executor.execute(command(nested), "message").getOrThrow() as CalendarCommandResult.Selection
        return CalendarDeleteTargetSelectionUiState(result.candidates, result.command as CalendarCommand.Delete, "message")
    }

    @Test fun `flat and nested ranges allow sequential deletion with stable independent receipts`() = runTest {
        for (nested in listOf(false, true)) {
            val first = event("one")
            val second = event("two")
            val outside = event("outside", 17)
            val repository = Repository(listOf(first, second, outside))
            val executor = CalendarCommandExecutor(repository, zone)
            var selection = card(executor, nested)
            assertTrue(selection.allowsMultipleDeletes)
            assertEquals(listOf(first, second), selection.candidates)
            assertTrue(repository.deleted.isEmpty())

            val pending = selection.beginDelete(first.id)!!
            assertNull(pending.beginDelete(first.id))
            assertNull(pending.beginDelete(second.id))
            assertNotEquals(pending.deletionRequestId(first.id), pending.deletionRequestId(second.id))
            val result = executor.execute(pending.command, pending.deletionRequestId(first.id),
                confirmed = true, selectedEvent = first).getOrThrow() as CalendarCommandResult.Completed
            selection = pending.finishDelete(result.receipt.eventId, succeeded = true)
            assertEquals(listOf(second), selection.candidates)
            assertEquals(1, selection.deletedCount)
            assertNull(selection.deletingEventId)
            assertNull(selection.beginDelete(first.id))
            assertEquals(result, executor.execute(pending.command, pending.deletionRequestId(first.id),
                confirmed = true, selectedEvent = first).getOrThrow())
            assertEquals(listOf("one"), repository.deleted)

            val next = selection.beginDelete(second.id)!!
            val secondResult = executor.execute(next.command, next.deletionRequestId(second.id),
                confirmed = true, selectedEvent = second).getOrThrow() as CalendarCommandResult.Completed
            selection = next.finishDelete(secondResult.receipt.eventId, succeeded = true)
            assertTrue(selection.candidates.isEmpty())
            assertEquals(2, selection.deletedCount)
            assertEquals(listOf("one", "two"), repository.deleted)
            assertEquals(listOf(outside), repository.events.values.toList())
        }
    }

    @Test fun `range intent selects September 16 through 18 and deletes them only one at a time`() = runTest {
        verifyRangeIntentSelection("2026-09-16T00:00", "2026-09-19T00:00")
    }

    @Test fun `date-only range selects September 16 through 18 without including September 19`() = runTest {
        verifyRangeIntentSelection("2026-09-16", "2026-09-19")
    }

    private suspend fun verifyRangeIntentSelection(startValue: String, endValue: String) {
        val parsed = AssistantResponseParser(zone).parseResult("""
            {
              "intent": "calendar_delete_range",
              "reply": "События удалены: все события с шестнадцатого по восемнадцатое число.",
              "params": {
                "start": "$startValue",
                "end": "$endValue"
              }
            }
        """.trimIndent()).getOrThrow()
        val command = CalendarCommandMapper(zone).map(parsed.params!!).getOrThrow()
        val start = CalendarTime.toEpochMillis(CalendarTime.dateTime("2026-09-16T00:00"), zone)
        val end = CalendarTime.toEpochMillis(CalendarTime.dateTime("2026-09-19T00:00"), zone)
        val first = event("first").copy(startsAtEpochMillis = start, endsAtEpochMillis = start + 60_000L)
        val middle = event("middle", 17)
        val last = event("last", 18).copy(startsAtEpochMillis = end - 60_000L, endsAtEpochMillis = end)
        val before = event("before", 15).copy(endsAtEpochMillis = start)
        val after = event("after", 19).copy(startsAtEpochMillis = end)
        val repository = Repository(listOf(before, first, middle, last, after))
        val executor = CalendarCommandExecutor(repository, zone)
        val result = executor.execute(command, "range-message").getOrThrow() as CalendarCommandResult.Selection
        var selection = CalendarDeleteTargetSelectionUiState(result.candidates,
            result.command as CalendarCommand.Delete, "range-message")
        assertEquals(listOf(first, middle, last), selection.candidates)
        assertTrue(selection.allowsMultipleDeletes)
        assertTrue(repository.deleted.isEmpty())
        for ((index, candidate) in listOf(first, middle, last).withIndex()) {
            val pending = selection.beginDelete(candidate.id)!!
            val deletion = executor.execute(pending.command, pending.deletionRequestId(candidate.id),
                confirmed = true, selectedEvent = candidate).getOrThrow() as CalendarCommandResult.Completed
            selection = pending.finishDelete(deletion.receipt.eventId, succeeded = true)
            assertEquals(index + 1, repository.deleted.size)
            assertEquals(2 - index, selection.candidates.size)
        }
        assertEquals(listOf(before, after), repository.events.values.toList())
    }

    @Test fun `failed deletion retains the row and releases the busy state`() = runTest {
        val first = event("one")
        val repository = Repository(listOf(first, event("two")))
        val executor = CalendarCommandExecutor(repository, zone)
        val pending = card(executor).beginDelete(first.id)!!
        repository.events[first.id] = first.copy(revision = 3)
        val result = executor.execute(pending.command, pending.deletionRequestId(first.id),
            confirmed = true, selectedEvent = first)
        assertTrue(result.exceptionOrNull() is CalendarConflictException)
        val retained = pending.finishDelete(first.id, succeeded = result.isSuccess)
        assertEquals(pending.candidates, retained.candidates)
        assertEquals(0, retained.deletedCount)
        assertNull(retained.deletingEventId)
        assertEquals(pending.deletionRequestId(first.id), retained.deletionRequestId(first.id))
        assertNotNull(retained.beginDelete("two"))
        assertTrue(repository.deleted.isEmpty())
    }

    @Test fun `empty period and unknown ids cannot start deletion`() = runTest {
        val repository = Repository()
        val selection = card(CalendarCommandExecutor(repository, zone))
        assertTrue(selection.candidates.isEmpty())
        assertEquals(0, selection.deletedCount)
        assertNull(selection.beginDelete("unknown"))
        assertEquals(selection, selection.finishDelete("unknown", succeeded = true))
        assertTrue(repository.deleted.isEmpty())
    }

    @Test fun `named and last-event targets keep their original operation identity`() {
        val base = command()
        for (target in listOf(
            base.target.copy(query = "Встреча"),
            CalendarDeleteTarget(useLastCreated = true),
            CalendarDeleteTarget(range = base.target.range, useLastInRange = true),
        )) {
            val selection = CalendarDeleteTargetSelectionUiState(listOf(event("one")), base.copy(target = target), "message")
            assertFalse(selection.allowsMultipleDeletes)
            assertEquals("message", selection.deletionRequestId("one"))
        }
    }

    private class Repository(initial: List<CalendarEvent> = emptyList()) : CalendarEventRepository {
        val events = initial.associateByTo(linkedMapOf()) { it.id }
        val deleted = mutableListOf<String>()
        private val receipts = mutableMapOf<String, CalendarReceipt>()
        override suspend fun getReceipt(requestId: String) = Result.success(receipts[requestId])
        override suspend fun commit(requestId: String, mutation: CalendarMutation) = runCatching {
            receipts[requestId]?.let { return@runCatching it }
            val deletion = mutation as CalendarMutation.Delete
            val current = events[deletion.id] ?: throw CalendarConflictException()
            if (current.revision != deletion.expectedRevision) throw CalendarConflictException()
            events.remove(current.id)
            deleted.add(current.id)
            CalendarReceipt(requestId, "calendar_delete", current.id, current.title).also { receipts[requestId] = it }
        }
        override suspend fun search(query: String, rangeStartEpochMillis: Long, rangeEndEpochMillis: Long) = Result.success(
            events.values.filter { it.startsAtEpochMillis < rangeEndEpochMillis && it.endsAtEpochMillis > rangeStartEpochMillis &&
                it.title.contains(query.trim(), ignoreCase = true) },
        )
        override suspend fun getById(id: String) = Result.success(events[id])
        override suspend fun create(draft: CalendarEventDraft): Result<CalendarEvent> = error("Not used")
        override fun observeInRange(rangeStartEpochMillis: Long, rangeEndEpochMillis: Long): Flow<List<CalendarEvent>> = emptyFlow()
        override suspend fun update(update: CalendarEventUpdate): Result<CalendarEvent> = error("Not used")
        override suspend fun delete(id: String): Result<Unit> = error("Use commit")
        override suspend fun findForUpdate(query: String, rangeStartEpochMillis: Long?, rangeEndEpochMillis: Long?): Result<List<CalendarEvent>> = error("Not used")
        override suspend fun getLastCreated(): Result<CalendarEvent?> = error("Not used")
        override suspend fun getLastInRange(rangeStartEpochMillis: Long, rangeEndEpochMillis: Long): Result<CalendarEvent?> = error("Not used")
    }
}
