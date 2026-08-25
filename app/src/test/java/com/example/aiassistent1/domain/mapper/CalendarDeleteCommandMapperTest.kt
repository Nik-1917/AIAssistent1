package com.example.aiassistent1.domain.mapper

import com.example.aiassistent1.calendar.core.domain.CalendarTargetMode
import com.example.aiassistent1.domain.model.CalendarDeleteParams
import com.example.aiassistent1.domain.model.CalendarDeleteTargetParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class CalendarDeleteCommandMapperTest {
    private val mapper = CalendarDeleteCommandMapper(ZoneOffset.UTC)

    @Test
    fun `maps one query target with its source period`() {
        val command = mapper.map(
            CalendarDeleteParams(
                target = CalendarDeleteTargetParams(
                    query = "стоматолог",
                    rangeStart = "2026-08-25T00:00",
                    rangeEnd = "2026-08-26T00:00",
                ),
            ),
        ).getOrThrow()

        assertEquals(CalendarTargetMode.BY_QUERY, command.target.mode)
        assertEquals("стоматолог", command.target.query)
        assertEquals(1_787_616_000_000L, command.target.rangeStartEpochMillis)
        assertEquals(1_787_702_400_000L, command.target.rangeEndEpochMillis)
    }

    @Test
    fun `maps explicit last created target`() {
        val command = mapper.map(
            CalendarDeleteParams(
                target = CalendarDeleteTargetParams(useLastCreated = true),
            ),
        ).getOrThrow()

        assertEquals(CalendarTargetMode.LAST_CREATED, command.target.mode)
    }

    @Test
    fun `maps the last event of a supplied period`() {
        val command = mapper.map(
            CalendarDeleteParams(
                target = CalendarDeleteTargetParams(
                    useLastInRange = true,
                    rangeStart = "2026-08-25T00:00",
                    rangeEnd = "2026-08-26T00:00",
                ),
            ),
        ).getOrThrow()

        assertEquals(CalendarTargetMode.LAST_IN_RANGE, command.target.mode)
        assertEquals(1_787_616_000_000L, command.target.rangeStartEpochMillis)
        assertEquals(1_787_702_400_000L, command.target.rangeEndEpochMillis)
    }

    @Test
    fun `rejects missing or conflicting delete targets`() {
        val missingTarget = mapper.map(CalendarDeleteParams())
        val conflictingTarget = mapper.map(
            CalendarDeleteParams(
                target = CalendarDeleteTargetParams(
                    query = "стоматолог",
                    useLastCreated = true,
                ),
            ),
        )
        val periodLessLastInRange = mapper.map(
            CalendarDeleteParams(
                target = CalendarDeleteTargetParams(useLastInRange = true),
            ),
        )

        assertTrue(missingTarget.isFailure)
        assertTrue(conflictingTarget.isFailure)
        assertTrue(periodLessLastInRange.isFailure)
    }
}
