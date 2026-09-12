package com.example.aiassistent1.domain.parser

import com.example.aiassistent1.calendar.core.domain.CalendarCommand
import com.example.aiassistent1.domain.mapper.CalendarCommandMapper
import com.example.aiassistent1.domain.model.CalendarAddParams
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class CalendarStartDuplicatesTest {
    private val parser = AssistantResponseParser()
    private val mapper = CalendarCommandMapper()
    private fun parse(extra: String, start: String = "2026-10-03T21:10") = parser.parseResult(
        """{"intent":"calendar_add","reply":"Чтение книги.","params":{"title":"Чтение книги","starts_at":"$start","duration_min":30,"value":4,$extra}}""",
    )

    @Test fun `actual response and matching date time duplicates normalize to one start`() {
        for (fields in listOf("\"date\":\"2026-10-03\"", "\"time\":\"21:10\"", "\"date\":\"2026-10-03\",\"time\":\"21:10\"")) {
            val params = parse(fields).getOrThrow().params as CalendarAddParams
            assertEquals("2026-10-03T21:10", params.startsAt)
            assertNull(params.date)
            assertNull(params.time)
            val command = mapper.map(params).getOrThrow() as CalendarCommand.Add
            assertEquals(LocalDate.of(2026, 10, 3), command.date)
            assertEquals(LocalTime.of(21, 10), command.time)
            assertEquals(30, command.durationMinutes)
            assertEquals(4L, command.value)
        }
    }

    @Test fun `conflicting or malformed redundant fields are not ignored`() {
        for (fields in listOf("\"date\":\"2026-10-04\"", "\"time\":\"21:11\"",
            "\"date\":\"2026-10-03\",\"time\":\"22:10\"", "\"date\":\"2026-02-30\"",
            "\"time\":\"25:10\"", "\"date\":4", "\"time\":null")) {
            assertTrue(fields, parse(fields).isFailure)
        }
    }

    @Test fun `mapper checks duplicates even when parser is bypassed`() {
        val params = CalendarAddParams("Чтение книги", "2026-10-03T21:10", 30,
            date = "2026-10-03", time = "21:10", value = 4)
        assertTrue(mapper.map(params).isSuccess)
        assertTrue(mapper.map(params.copy(date = "2026-10-04")).isFailure)
        assertTrue(mapper.map(params.copy(time = "22:10")).isFailure)
        assertTrue(mapper.map(params.copy(date = "invalid")).isFailure)
        assertTrue(mapper.map(params.copy(time = "invalid")).isFailure)
    }

    @Test fun `shorthand permits only matching redundant time`() {
        val params = parse("\"date\":\"2026-10-03\",\"time\":\"21:10\"", "21:10").getOrThrow().params as CalendarAddParams
        assertNull(params.startsAt)
        assertEquals("2026-10-03", params.date)
        assertEquals("21:10", params.time)
        assertTrue(parse("\"time\":\"22:10\"", "21:10").isFailure)
    }

    @Test fun `normalization preserves end notes and value alias`() {
        val response = parser.parseResult("""{"intent":"calendar_add","reply":"x","params":{"starts_at":"2026-10-03T23:50","date":"2026-10-03","time":"23:50","ends_at":"2026-10-04T00:20","date_value":0,"notes":"  Текст  "}}""").getOrThrow()
        val params = response.params as CalendarAddParams
        assertNull(params.date)
        assertNull(params.time)
        assertEquals(30, params.durationMin)
        assertEquals("2026-10-04T00:20", params.endsAt)
        assertEquals("  Текст  ", params.notes)
        assertEquals(0L, params.value)
    }
}
