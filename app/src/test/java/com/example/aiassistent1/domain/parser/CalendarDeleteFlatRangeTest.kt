package com.example.aiassistent1.domain.parser

import com.example.aiassistent1.domain.mapper.CalendarCommandMapper
import com.example.aiassistent1.domain.model.CalendarDeleteParams
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset

class CalendarDeleteFlatRangeTest {
    private val parser = AssistantResponseParser(ZoneOffset.UTC)
    private val range = """"range_start":"2026-09-16T00:00","range_end":"2026-09-17T00:00" """
    private fun parse(params: String) = parser.parseResult(
        """{"intent":"calendar_delete","reply":"События удалены за шестнадцатое число.","params":{$params}}""",
    )

    @Test fun `flat range and nested target map to the same selection command`() {
        val flat = parse(range).getOrThrow()
        val nested = parse(""""target":{$range}""").getOrThrow()
        assertEquals(nested, flat)
        val params = flat.params as CalendarDeleteParams
        assertNull(params.target.query)
        assertEquals("2026-09-16T00:00", params.target.rangeStart)
        assertEquals("2026-09-17T00:00", params.target.rangeEnd)
        assertFalse(params.target.useLastCreated)
        assertFalse(params.target.useLastInRange)
        val mapper = CalendarCommandMapper(ZoneOffset.UTC)
        assertEquals(mapper.map(nested.params!!).getOrThrow(), mapper.map(params).getOrThrow())
        assertEquals(flat, parse("""$range,"changes":{}""").getOrThrow())
    }

    @Test fun `mixed flat and nested targets are rejected even with agreeing dates`() {
        for (target in listOf("{}", """{$range}""", """{"query":"Встреча"}""", """{"use_last_created":true}""")) {
            assertTrue(target, parse("""$range,"target":$target""").isFailure)
        }
    }

    @Test fun `flat range still requires two valid ordered timestamps`() {
        for (fields in listOf(
            """"range_start":"2026-09-16T00:00" """,
            """"range_end":"2026-09-17T00:00" """,
            """"range_start":"2026-09-16T00:00","range_end":"2026-09-16T00:00" """,
            """"range_start":"2026-09-17T00:00","range_end":"2026-09-16T00:00" """,
            """"range_start":"2026-02-30T00:00","range_end":"2026-03-01T00:00" """,
            """"range_start":"2026-09-16","range_end":"2026-09-17" """,
            """"range_start":null,"range_end":"2026-09-17T00:00" """,
            """"range_start":16,"range_end":"2026-09-17T00:00" """,
        )) {
            org.json.JSONObject("{$fields}")
            assertTrue(fields, parse(fields).isFailure)
        }
    }

    @Test fun `flat range does not admit unrelated fields or nonempty changes`() {
        for (extra in listOf(
            """"query":"Встреча" """, """"use_last_created":true""",
            """"use_last_in_range":true""", """"time_min":11,"time_max":12""",
            """"unknown":0""", """"changes":{"title":"Встреча"}""", """"changes":null""",
        )) assertTrue(extra, parse("$range,$extra").isFailure)
        assertTrue(parse("").isFailure)
        for (intent in listOf("calendar_add", "calendar_update", "chat")) {
            assertTrue(parser.parseResult("""{"intent":"$intent","reply":"x","params":{$range}}""").isFailure)
        }
    }
}
