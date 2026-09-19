package com.example.aiassistent1.domain.parser

import com.example.aiassistent1.domain.model.CalendarSearchParams
import com.example.aiassistent1.domain.model.CalendarSumParams
import org.junit.Assert.*
import org.junit.Test

class CalendarSearchPointTest {
    private val parser = AssistantResponseParser()

    @Test fun `range without query reaches the executor as a search without a title filter`() {
        val params = parser.parseResult("""{"intent":"calendar_search","reply":"Проверяю","params":{"range_start":"2026-09-16T00:00","range_end":"2026-09-19T00:00"}}""")
            .getOrThrow().params as CalendarSearchParams
        assertNull(params.query)
        val command = com.example.aiassistent1.domain.mapper.CalendarCommandMapper(java.time.ZoneOffset.UTC)
            .map(params).getOrThrow() as com.example.aiassistent1.calendar.core.domain.CalendarCommand.Search
        assertNull(command.query)
        assertEquals(java.time.Instant.parse("2026-09-16T00:00:00Z").toEpochMilli(), command.range!!.start)
        assertEquals(java.time.Instant.parse("2026-09-19T00:00:00Z").toEpochMilli(), command.range!!.end)
    }

    @Test fun `a lone start becomes a one minute exact search interval`() {
        val params = parser.parseResult("""{"intent":"calendar_search","reply":"Проверяю","params":{"query":"","range_start":"2026-10-03T21:10"}}""").getOrThrow().params as CalendarSearchParams
        assertEquals("2026-10-03T21:10", params.rangeStart)
        assertEquals("2026-10-03T21:11", params.rangeEnd)
        assertEquals("", params.query)
    }

    @Test fun `a regular search period remains unchanged`() {
        val params = parser.parseResult("""{"intent":"calendar_search","reply":"Проверяю","params":{"query":"книга","range_start":"2026-10-03T00:00","range_end":"2026-10-04T00:00"}}""").getOrThrow().params as CalendarSearchParams
        assertEquals("2026-10-03T00:00", params.rangeStart)
        assertEquals("2026-10-04T00:00", params.rangeEnd)
    }

    @Test fun `sum and end only search still require complete range`() {
        assertTrue(parser.parseResult("""{"intent":"calendar_sum","reply":"Считаю","params":{"range_start":"2026-10-03T21:10"}}""").isFailure)
        assertTrue(parser.parseResult("""{"intent":"calendar_search","reply":"Проверяю","params":{"range_end":"2026-10-03T21:10"}}""").isFailure)
    }

    @Test fun `zero and negative search periods remain invalid`() {
        assertTrue(parser.parseResult("""{"intent":"calendar_search","reply":"x","params":{"range_start":"2026-10-03T21:10","range_end":"2026-10-03T21:10"}}""").isFailure)
        assertTrue(parser.parseResult("""{"intent":"calendar_search","reply":"x","params":{"range_start":"2026-10-03T21:10","range_end":"2026-10-03T21:09"}}""").isFailure)
    }
}
