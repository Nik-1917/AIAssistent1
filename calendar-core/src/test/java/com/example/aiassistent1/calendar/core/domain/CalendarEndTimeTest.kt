package com.example.aiassistent1.calendar.core.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

class CalendarEndTimeTest {
    @Test fun `duration measures actual elapsed minutes across a clock change`() {
        assertEquals(60, CalendarTime.durationMinutes(CalendarTime.dateTime("2026-03-29T01:30"),
            CalendarTime.dateTime("2026-03-29T03:30"), null, ZoneId.of("Europe/Berlin")))
    }
    @Test fun `ambiguous or nonexistent local endpoints remain invalid`() {
        for (end in listOf("2026-03-29T02:30", "2026-10-25T02:30")) {
            assertTrue(runCatching { CalendarTime.durationMinutes(null, CalendarTime.dateTime(end), null, ZoneId.of("Europe/Berlin")) }.isFailure)
        }
    }
    @Test fun `one minute and midnight intervals remain exact`() {
        assertEquals(1, CalendarTime.durationMinutes(CalendarTime.dateTime("2026-09-11T23:59"),
            CalendarTime.dateTime("2026-09-12T00:00"), 1, ZoneOffset.UTC))
    }
}
