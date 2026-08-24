package com.example.aiassistent1.domain.mapper

import com.example.aiassistent1.calendar.core.domain.CalendarTargetMode
import com.example.aiassistent1.domain.model.CalendarUpdateChangesParams
import com.example.aiassistent1.domain.model.CalendarUpdateParams
import com.example.aiassistent1.domain.model.CalendarUpdateTargetParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class CalendarUpdateCommandMapperTest {
    private val mapper = CalendarUpdateCommandMapper(ZoneOffset.UTC)

    @Test
    fun `keeps source period separate from destination date and lets query win`() {
        val command = mapper.map(
            CalendarUpdateParams(
                target = CalendarUpdateTargetParams(
                    query = "тренировка",
                    rangeStart = "2026-08-25T00:00",
                    rangeEnd = "2026-08-26T00:00",
                    useLastCreated = true,
                ),
                changes = CalendarUpdateChangesParams(date = "2026-08-28"),
            ),
        ).getOrThrow()

        assertEquals(CalendarTargetMode.BY_QUERY, command.target.mode)
        assertEquals("тренировка", command.target.query)
        assertEquals(1_787_616_000_000L, command.target.rangeStartEpochMillis)
        assertEquals(1_787_702_400_000L, command.target.rangeEndEpochMillis)
        assertEquals(LocalDate.of(2026, 8, 28), command.changes.date)
        assertNull(command.changes.time)
    }

    @Test
    fun `uses last created event when model omitted the target`() {
        val command = mapper.map(
            CalendarUpdateParams(changes = CalendarUpdateChangesParams(time = "10:00")),
        ).getOrThrow()

        assertEquals(CalendarTargetMode.LAST_CREATED, command.target.mode)
        assertNull(command.target.query)
        assertEquals(10, command.changes.time?.hour)
    }

    @Test
    fun `rejects source period without event query`() {
        val result = mapper.map(
            CalendarUpdateParams(
                target = CalendarUpdateTargetParams(
                    rangeStart = "2026-08-25T00:00",
                    rangeEnd = "2026-08-26T00:00",
                ),
                changes = CalendarUpdateChangesParams(time = "10:00"),
            ),
        )

        assertTrue(result.isFailure)
    }
}
