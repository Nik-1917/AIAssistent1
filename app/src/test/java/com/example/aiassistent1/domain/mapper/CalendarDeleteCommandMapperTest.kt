package com.example.aiassistent1.domain.mapper

import com.example.aiassistent1.calendar.core.domain.CalendarDeleteTarget
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

        assertEquals("стоматолог", command.target.query)
        assertEquals(1_787_616_000_000L, command.target.range!!.start)
        assertEquals(1_787_702_400_000L, command.target.range!!.end)
    }

    @Test
    fun `maps explicit last created target`() {
        val command = mapper.map(
            CalendarDeleteParams(
                target = CalendarDeleteTargetParams(useLastCreated = true),
            ),
        ).getOrThrow()

        assertTrue(command.target.useLastCreated)
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

        assertTrue(command.target.useLastInRange)
        assertEquals(1_787_616_000_000L, command.target.range!!.start)
        assertEquals(1_787_702_400_000L, command.target.range!!.end)
    }

    @Test
    fun `missing target can select today while conflicting targets remain invalid`() {
        assertEquals(CalendarDeleteTarget(), mapper.map(CalendarDeleteParams()).getOrThrow().target)
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

        assertTrue(conflictingTarget.isFailure)
        assertTrue(periodLessLastInRange.isFailure)
    }

    @Test
    fun `maps a period without a name for selection`() {
        val target = mapper.map(CalendarDeleteParams(CalendarDeleteTargetParams(
            rangeStart = "2026-08-25T11:00", rangeEnd = "2026-08-25T12:00",
        ))).getOrThrow().target
        assertEquals(null, target.query)
        assertEquals(3_600_000L, target.range!!.end - target.range!!.start)
    }
}
