package com.example.aiassistent1.presentation.formatting

import com.example.aiassistent1.calendar.core.domain.CalendarTimeMode
import com.example.aiassistent1.domain.model.CalendarInputTime
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CalendarInputTimePresentationTest {
    private val moscow = ZoneId.of("Europe/Moscow")
    private val samara = ZoneId.of("Europe/Samara")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    @Test fun `identical zones and their aliases do not need a conversion block`() {
        assertFalse(CalendarInputTimePresentation.showsConversion(CalendarTimeMode.FIXED, moscow, moscow))
        assertFalse(CalendarInputTimePresentation.showsConversion(CalendarTimeMode.FIXED, ZoneId.of("UTC"), ZoneId.of("Etc/UTC")))
    }

    @Test fun `equal seasonal offsets do not hide different zone policies`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val lagos = ZoneId.of("Africa/Lagos")
        val winter = Instant.parse("2026-01-20T08:00:00Z")
        assertEquals(berlin.rules.getOffset(winter), lagos.rules.getOffset(winter))
        assertTrue(CalendarInputTimePresentation.showsConversion(CalendarTimeMode.FIXED, berlin, lagos))
        val summer = Instant.parse("2026-07-20T08:00:00Z")
        assertNotEquals(berlin.rules.getOffset(summer), lagos.rules.getOffset(summer))
    }

    @Test fun `floating time never advertises a timezone conversion`() {
        assertFalse(CalendarInputTimePresentation.showsConversion(CalendarTimeMode.FLOATING, samara, moscow))
    }

    @Test fun `ordinary conversion is one compact line`() {
        val start = LocalDateTime.parse("2026-10-20T09:00")
        assertEquals("09:00 Самара → 08:00 Москва", CalendarInputTimePresentation.conversionText(
            start, CalendarInputTime.convert(start, samara, moscow), "Самара", "Москва"))
    }

    @Test fun `crossing midnight shows both dates`() {
        val start = LocalDateTime.parse("2026-10-20T00:30")
        assertEquals("20.10 00:30 Токио → 19.10 18:30 Москва", CalendarInputTimePresentation.conversionText(
            start, CalendarInputTime.convert(start, tokyo, moscow), "Токио", "Москва"))
    }

    @Test fun `crossing the year shows both full dates`() {
        val start = LocalDateTime.parse("2027-01-01T00:30")
        assertEquals("01.01.2027 00:30 Токио → 31.12.2026 18:30 Москва", CalendarInputTimePresentation.conversionText(
            start, CalendarInputTime.convert(start, tokyo, moscow), "Токио", "Москва"))
    }
}
