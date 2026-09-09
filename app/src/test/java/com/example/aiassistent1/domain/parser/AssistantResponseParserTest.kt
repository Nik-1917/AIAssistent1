package com.example.aiassistent1.domain.parser

import com.example.aiassistent1.domain.model.CalendarAddParams
import com.example.aiassistent1.domain.model.CalendarDeleteParams
import com.example.aiassistent1.domain.model.CalendarSearchParams
import com.example.aiassistent1.domain.model.CalendarUpdateParams
import com.example.aiassistent1.domain.model.CalendarSumParams
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
    fun `keeps a supplied event date when its time is absent`() {
        val response = parser.parse(
            """{"intent":"calendar_add","reply":"Уточните время","params":{"title":"Проверка отчёта","date":"2030-12-07","duration_min":120}}""",
        )

        val params = response?.params as CalendarAddParams
        assertEquals("Проверка отчёта", params.title)
        assertNull(params.startsAt)
        assertEquals("2030-12-07", params.date)
        assertNull(params.time)
        assertEquals(120, params.durationMin)
    }

    @Test
    fun `keeps a supplied event time for the missing-date fallback`() {
        val response = parser.parse(
            """{"intent":"calendar_add","reply":"Уточните подробности.","params":{"title":"Проверка отчёта","time":"15:00","duration_min":120}}""",
        )

        val params = response?.params as CalendarAddParams
        assertNull(params.startsAt)
        assertNull(params.date)
        assertEquals("15:00", params.time)
    }

    @Test
    fun `normalizes a time-only starts_at emitted with an explicit date`() {
        val response = parser.parse(
            """{"intent":"calendar_add","reply":"Подтвердите","params":{"title":"Встреча","date":"2026-09-10","starts_at":"09:15","duration_min":30}}""",
        )

        val params = response?.params as CalendarAddParams
        assertNull(params.startsAt)
        assertEquals("2026-09-10", params.date)
        assertEquals("09:15", params.time)
        assertEquals(30, params.durationMin)
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
    fun `rejects removed note intent`() {
        val response = parser.parse(
            """{"intent":"note_add","reply":"Сохраняю","params":{"text":"текст"}}""",
        )

        assertNull(response)
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

    @Test
    fun `parses delete of the last event in a period`() {
        val response = parser.parse(
            """{"intent":"calendar_delete","reply":"Событие удалено","params":{"target":{"use_last_in_range":true,"range_start":"2026-08-25T00:00","range_end":"2026-08-26T00:00"}}}""",
        )

        val params = response?.params as CalendarDeleteParams
        assertTrue(params.target.useLastInRange)
        assertEquals("2026-08-25T00:00", params.target.rangeStart)
        assertEquals("2026-08-26T00:00", params.target.rangeEnd)
    }

    @Test
    fun `parses sum and preserves an integer value`() {
        val response = parser.parse(
            """{"intent":"calendar_sum","reply":"Готовлю сумму","params":{"range_start":"2026-08-25T00:00","range_end":"2026-08-26T00:00"}}""",
        )

        assertTrue(response?.params is CalendarSumParams)
        assertNull((response?.params as CalendarSumParams).query)
        val add = parser.parse(
            """{"intent":"calendar_add","reply":"Событие","params":{"title":"Поезд","starts_at":"2026-08-25T15:00","duration_min":60,"value":0}}""",
        )?.params as CalendarAddParams
        assertEquals(0L, add.value)
    }

    @Test
    fun `rejects coercion, arrays and surrounding text`() {
        assertNull(parser.parse("""{"intent":"calendar_add","reply":"x","params":{"title":"x","date":"2026-08-25","duration_min":"60"}}"""))
        assertNull(parser.parse("""prefix {"intent":"chat","reply":"x","params":{}} suffix"""))
        assertNull(parser.parse("""{"intent":"chat","reply":"x","params":[]}"""))
    }
}
