package com.example.aiassistent1.calendar.core.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

class CalendarUpdateCommandTest {
    @Test
    fun `resolves last created event when target has no query`() = runTest {
        val latest = event(id = "latest", createdAt = 20L)
        val resolver = ResolveCalendarUpdateTargetUseCase(FakeRepository(lastCreated = latest))

        val result = resolver(CalendarUpdateTarget(mode = CalendarTargetMode.LAST_CREATED)).getOrThrow()

        assertEquals(CalendarUpdateTargetResolution.Resolved(latest), result)
    }

    @Test
    fun `resolves the last event in a supplied calendar period`() = runTest {
        val lastToday = event(id = "last-today", startsAt = LocalDate.of(2026, 8, 24).atTime(18, 0).toEpochMillis())
        val resolver = ResolveCalendarUpdateTargetUseCase(FakeRepository(lastInRange = lastToday))

        val result = resolver(
            CalendarUpdateTarget(
                mode = CalendarTargetMode.LAST_IN_RANGE,
                rangeStartEpochMillis = LocalDate.of(2026, 8, 24).atStartOfDay().toEpochMillis(),
                rangeEndEpochMillis = LocalDate.of(2026, 8, 25).atStartOfDay().toEpochMillis(),
            ),
        ).getOrThrow()

        assertEquals(CalendarUpdateTargetResolution.Resolved(lastToday), result)
    }

    @Test
    fun `returns candidates instead of picking an ambiguous title match`() = runTest {
        val first = event(id = "first", title = "Тренировка")
        val second = event(id = "second", title = "Тренировка вечером")
        val resolver = ResolveCalendarUpdateTargetUseCase(FakeRepository(matches = listOf(first, second)))

        val result = resolver(
            CalendarUpdateTarget(mode = CalendarTargetMode.BY_QUERY, query = "тренировка"),
        ).getOrThrow()

        assertEquals(CalendarUpdateTargetResolution.Ambiguous(listOf(first, second)), result)
    }

    @Test
    fun `preserves date and duration when only time changes`() {
        val existing = event(
            startsAt = LocalDate.of(2026, 8, 24).atTime(9, 30).toEpochMillis(),
            endsAt = LocalDate.of(2026, 8, 24).atTime(10, 30).toEpochMillis(),
        )
        val prepare = PrepareCalendarEventUpdateUseCase(ZoneOffset.UTC)

        val update = prepare(existing, CalendarEventChanges(time = LocalTime.of(10, 0))).getOrThrow()

        assertEquals("Тренировка", update.title)
        assertEquals(LocalDate.of(2026, 8, 24).atTime(10, 0).toEpochMillis(), update.startsAtEpochMillis)
        assertEquals(60_000L * 60L, update.endsAtEpochMillis - update.startsAtEpochMillis)
    }

    @Test
    fun `rejects a command with no changed field`() {
        val result = PrepareCalendarEventUpdateUseCase(ZoneOffset.UTC)(event(), CalendarEventChanges())

        assertTrue(result.isFailure)
    }

    private class FakeRepository(
        private val matches: List<CalendarEvent> = emptyList(),
        private val lastCreated: CalendarEvent? = null,
        private val lastInRange: CalendarEvent? = null,
    ) : CalendarEventRepository {
        override suspend fun create(draft: CalendarEventDraft): Result<CalendarEvent> = error("Not used")
        override suspend fun getById(id: String): Result<CalendarEvent?> = error("Not used")
        override fun observeInRange(rangeStartEpochMillis: Long, rangeEndEpochMillis: Long): Flow<List<CalendarEvent>> = emptyFlow()
        override suspend fun update(update: CalendarEventUpdate): Result<CalendarEvent> = error("Not used")
        override suspend fun delete(id: String): Result<Unit> = error("Not used")
        override suspend fun search(query: String, rangeStartEpochMillis: Long, rangeEndEpochMillis: Long): Result<List<CalendarEvent>> = error("Not used")
        override suspend fun findForUpdate(query: String, rangeStartEpochMillis: Long?, rangeEndEpochMillis: Long?): Result<List<CalendarEvent>> = Result.success(matches)
        override suspend fun getLastCreated(): Result<CalendarEvent?> = Result.success(lastCreated)
        override suspend fun getLastInRange(rangeStartEpochMillis: Long, rangeEndEpochMillis: Long): Result<CalendarEvent?> = Result.success(lastInRange)
    }

    private fun event(
        id: String = "event-1",
        title: String = "Тренировка",
        startsAt: Long = LocalDate.of(2026, 8, 24).atTime(9, 30).toEpochMillis(),
        endsAt: Long = LocalDate.of(2026, 8, 24).atTime(10, 30).toEpochMillis(),
        createdAt: Long = 10L,
    ) = CalendarEvent(
        id = id,
        title = title,
        startsAtEpochMillis = startsAt,
        endsAtEpochMillis = endsAt,
        createdAtEpochMillis = createdAt,
        updatedAtEpochMillis = createdAt,
    )

    private fun java.time.LocalDateTime.toEpochMillis(): Long =
        toInstant(ZoneOffset.UTC).toEpochMilli()
}
