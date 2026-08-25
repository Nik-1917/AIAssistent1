package com.example.aiassistent1.domain.parser

import com.example.aiassistent1.domain.model.CalendarAddParams
import com.example.aiassistent1.domain.model.CalendarDeleteParams
import com.example.aiassistent1.domain.model.CalendarSearchParams
import com.example.aiassistent1.domain.model.CalendarUpdateParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantResponseParserTest {
    private val parser = AssistantResponseParser()

    @Test
    fun `parses a complete local calendar event`() {
        val response = parser.parse(
            """{"intent":"calendar_add","reply":"Подтвердите","params":{"title":"Встреча","starts_at":"2026-08-25T15:00","duration_min":60}}""",
        )

        val params = response?.params as CalendarAddParams
        assertEquals("Встреча", params.title)
        assertEquals("2026-08-25T15:00", params.startsAt)
        assertEquals(60, params.durationMin)
    }

    @Test
    fun `keeps absent event fields empty for the dialog`() {
        val response = parser.parse(
            """{"intent":"calendar_add","reply":"Уточните время","params":{"title":"Встреча"}}""",
        )

        val params = response?.params as CalendarAddParams
        assertEquals("Встреча", params.title)
        assertNull(params.startsAt)
        assertNull(params.durationMin)
    }

    @Test
    fun `parses an explicit calendar search range`() {
        val response = parser.parse(
            """{"intent":"calendar_search","reply":"Проверяю","params":{"query":"","range_start":"2026-08-21T00:00","range_end":"2026-08-22T00:00"}}""",
        )

        val params = response?.params as CalendarSearchParams
        assertEquals("2026-08-21T00:00", params.rangeStart)
        assertEquals("2026-08-22T00:00", params.rangeEnd)
    }

    @Test
    fun `accepts chat response without a calendar operation`() {
        val response = parser.parse("""{"intent":"chat","reply":"На какое время?","params":{}}""")

        assertEquals("chat", response?.intent)
        assertNull(response?.params)
        assertTrue(response?.reply?.isNotBlank() == true)
    }

    @Test
    fun `parses an update command with target and partial changes`() {
        val response = parser.parse(
            """{"intent":"calendar_update","reply":"Событие изменено","params":{"target":{"query":"тренировка","range_start":"2026-08-25T00:00","range_end":"2026-08-26T00:00"},"changes":{"date":"2026-08-28"}}}""",
        )

        val params = response?.params as CalendarUpdateParams
        assertEquals("тренировка", params.target.query)
        assertEquals("2026-08-25T00:00", params.target.rangeStart)
        assertEquals("2026-08-28", params.changes.date)
        assertNull(params.changes.time)
    }

    @Test
    fun `parses last created target without invented fields`() {
        val response = parser.parse(
            """{"intent":"calendar_update","reply":"Время изменено","params":{"target":{"use_last_created":true},"changes":{"time":"10:00"}}}""",
        )

        val params = response?.params as CalendarUpdateParams
        assertTrue(params.target.useLastCreated)
        assertNull(params.target.query)
        assertEquals("10:00", params.changes.time)
    }

    @Test
    fun `parses immediate calendar delete target`() {
        val response = parser.parse(
            """{"intent":"calendar_delete","reply":"Событие удалено","params":{"target":{"query":"стоматолог","range_start":"2026-08-25T00:00","range_end":"2026-08-26T00:00"}}}""",
        )

        val params = response?.params as CalendarDeleteParams
        assertEquals("стоматолог", params.target.query)
        assertEquals("2026-08-25T00:00", params.target.rangeStart)
        assertEquals("2026-08-26T00:00", params.target.rangeEnd)
        assertTrue(!params.target.useLastCreated)
    }

    @Test
    fun `parses delete of the last created event`() {
        val response = parser.parse(
            """{"intent":"calendar_delete","reply":"Событие удалено","params":{"target":{"use_last_created":true}}}""",
        )

        val params = response?.params as CalendarDeleteParams
        assertTrue(params.target.useLastCreated)
        assertNull(params.target.query)
    }
}
