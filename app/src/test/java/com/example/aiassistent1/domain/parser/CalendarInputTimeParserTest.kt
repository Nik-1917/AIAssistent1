package com.example.aiassistent1.domain.parser

import com.example.aiassistent1.domain.model.*
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CalendarInputTimeParserTest {
    private val parser = AssistantResponseParser()
    private val target = ZoneId.of("Europe/Samara")

    @Test fun `creation endpoints use home zone while retaining the strict JSON contract`() {
        val raw = """{"intent":"calendar_add","reply":"Встреча","params":{"starts_at":"2026-03-29T01:30","ends_at":"2026-03-29T03:30","duration_min":60}}"""
        assertTrue(parser.parseResult(raw, timeZone = target).isFailure)
        val params = parser.parseResult(raw, timeZone = target, creationTimeZone = ZoneId.of("Europe/Berlin")).getOrThrow().params as CalendarAddParams
        assertEquals(60, params.durationMin)
        assertEquals("2026-03-29T01:30", params.startsAt)
        assertEquals("2026-03-29T03:30", params.endsAt)
        assertTrue(parser.parseResult(raw.replace("\"duration_min\":60", "\"duration_min\":60,\"time_zone\":\"Europe/Berlin\""),
            timeZone = target, creationTimeZone = ZoneId.of("Europe/Berlin")).isFailure)
    }

    @Test fun `creation and search use their own date contexts at the year boundary`() {
        val calendar = CalendarDateContext(LocalDate.of(2028, 1, 1))
        val input = CalendarDateContext(LocalDate.of(2027, 12, 31))
        val add = parser.parseResult("""{"intent":"calendar_add","reply":"Встреча","params":{"date":"29 февраля"}}""",
            calendar, target, input).getOrThrow().params as CalendarAddParams
        assertEquals("2027-03-01", add.date)
        for (intent in listOf("calendar_search", "calendar_sum")) {
            val params = parser.parseResult("""{"intent":"$intent","reply":"Поиск","params":{"range_start":"29 февраляT00:00","range_end":"30 февраляT00:00"}}""",
                calendar, target, input).getOrThrow().params
            val start = when (params) {
                is CalendarSearchParams -> params.rangeStart
                is CalendarSumParams -> params.rangeStart
                else -> error("Unexpected command")
            }
            assertEquals("2028-02-29T00:00", start)
        }
    }
}
