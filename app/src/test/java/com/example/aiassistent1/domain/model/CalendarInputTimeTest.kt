package com.example.aiassistent1.domain.model

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class CalendarInputTimeTest {
    private val moscow = ZoneId.of("Europe/Moscow")
    private val samara = ZoneId.of("Europe/Samara")

    @Test fun `nine entered in Samara becomes eight in Moscow`() {
        assertEquals(LocalDateTime.parse("2026-10-20T08:00"),
            CalendarInputTime.convert(LocalDateTime.parse("2026-10-20T09:00"), samara, moscow))
    }

    @Test fun `conversion carries the date in both directions across midnight`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val entered = LocalDateTime.parse("2026-10-20T00:30")
        val converted = CalendarInputTime.convert(entered, tokyo, moscow)
        assertEquals(LocalDateTime.parse("2026-10-19T18:30"), converted)
        assertEquals(entered, CalendarInputTime.convert(converted, moscow, tokyo))
    }

    @Test fun `seasonal offset is resolved on the event date`() {
        val berlin = ZoneId.of("Europe/Berlin")
        for ((date, hour) in listOf("2026-01-20" to 11, "2026-07-20" to 10)) {
            assertEquals(LocalDateTime.parse("${date}T${hour}:00"),
                CalendarInputTime.convert(LocalDateTime.parse("${date}T09:00"), berlin, moscow))
        }
    }

    @Test fun `invalid and ambiguous source times retain strict validation`() {
        val berlin = ZoneId.of("Europe/Berlin")
        for (value in listOf("2026-03-29T02:30", "2026-10-25T02:30")) {
            assertTrue(value, runCatching { CalendarInputTime.convert(LocalDateTime.parse(value), berlin, moscow) }.isFailure)
        }
    }

    @Test fun `one immutable request can have different input and calendar dates`() {
        val context = CalendarTimeContext(moscow, Instant.parse("2027-12-31T22:30:00Z"), ZoneId.of("America/Los_Angeles"))
        assertEquals(LocalDateTime.parse("2028-01-01T01:30"), context.localDateTime)
        assertEquals(LocalDateTime.parse("2027-12-31T14:30"), context.inputLocalDateTime)
    }
}
