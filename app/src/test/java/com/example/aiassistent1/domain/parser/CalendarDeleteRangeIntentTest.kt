package com.example.aiassistent1.domain.parser

import com.example.aiassistent1.domain.mapper.CalendarCommandMapper
import com.example.aiassistent1.domain.model.CalendarDeleteParams
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset

class CalendarDeleteRangeIntentTest {
    private val parser = AssistantResponseParser(ZoneOffset.UTC)
    private val valid = """{"start":"2026-09-16T00:00","end":"2026-09-19T00:00"}"""
    private fun parse(params: String, intent: String = "calendar_delete_range") = parser.parseResult(
        """{"intent":"$intent","reply":"События удалены: все события с шестнадцатого по восемнадцатое число.","params":$params}""",
    )

    @Test fun `range intent maps to the existing period deletion parameters`() {
        val response = parse(valid).getOrThrow()
        val params = response.params as CalendarDeleteParams
        assertEquals("calendar_delete_range", response.intent)
        assertEquals("2026-09-16T00:00", params.target.rangeStart)
        assertEquals("2026-09-19T00:00", params.target.rangeEnd)
        assertNull(params.target.query)
        assertFalse(params.target.useLastCreated)
        assertFalse(params.target.useLastInRange)
        val mapper = CalendarCommandMapper(ZoneOffset.UTC)
        for (oldParams in listOf(
            """{"range_start":"2026-09-16T00:00","range_end":"2026-09-19T00:00"}""",
            """{"target":{"range_start":"2026-09-16T00:00","range_end":"2026-09-19T00:00"}}""",
        )) {
            val previous = parse(oldParams, "calendar_delete").getOrThrow().params!!
            assertEquals(previous, params)
            assertEquals(mapper.map(previous).getOrThrow(), mapper.map(params).getOrThrow())
        }
    }

    @Test fun `date-only boundaries normalize to midnight without extending the end date`() {
        val expected = parse(valid).getOrThrow()
        val mapper = CalendarCommandMapper(ZoneOffset.UTC)
        for (params in listOf(
            """{"start":"2026-09-16","end":"2026-09-19"}""",
            """{"start":"2026-09-16","end":"2026-09-19T00:00"}""",
            """{"start":"2026-09-16T00:00","end":"2026-09-19"}""",
        )) {
            val actual = parse(params).getOrThrow()
            assertEquals(expected, actual)
            assertEquals(mapper.map(expected.params!!).getOrThrow(), mapper.map(actual.params!!).getOrThrow())
        }
    }

    @Test fun `mixed boundaries preserve any explicitly supplied time`() {
        for ((params, start, end) in listOf(
            Triple("""{"start":"2026-09-16T14:30","end":"2026-09-19"}""",
                "2026-09-16T14:30", "2026-09-19T00:00"),
            Triple("""{"start":"2026-09-16","end":"2026-09-16T14:30"}""",
                "2026-09-16T00:00", "2026-09-16T14:30"),
        )) {
            val target = (parse(params).getOrThrow().params as CalendarDeleteParams).target
            assertEquals(start, target.rangeStart)
            assertEquals(end, target.rangeEnd)
        }
    }

    @Test fun `both boundaries must be valid ordered local dates or timestamps`() {
        for (params in listOf(
            "{}",
            """{"start":"2026-09-16T00:00"}""",
            """{"end":"2026-09-19T00:00"}""",
            """{"start":null,"end":"2026-09-19T00:00"}""",
            """{"start":"2026-09-16T00:00","end":null}""",
            """{"start":16,"end":"2026-09-19T00:00"}""",
            """{"start":"2026-09-16T00:00","end":19}""",
            """{"start":"","end":"2026-09-19T00:00"}""",
            """{"start":"2026-02-30","end":"2026-03-01"}""",
            """{"start":"2026-09-16","end":"2026-09-31"}""",
            """{"start":"2026-9-16","end":"2026-09-19"}""",
            """{"start":"16.09.2026","end":"2026-09-19"}""",
            """{"start":"2026-09-19","end":"2026-09-16"}""",
            """{"start":"2026-09-16","end":"2026-09-16"}""",
            """{"start":"2026-09-16T14:30","end":"2026-09-16"}""",
            """{"start":"2026-09-16","end":"2026-09-16T00:00"}""",
            """{"start":"2026-02-30T00:00","end":"2026-03-01T00:00"}""",
            """{"start":"2026-09-16T00:00","end":"2026-09-16T24:00"}""",
            """{"start":"2026-09-19T00:00","end":"2026-09-16T00:00"}""",
            """{"start":"2026-09-16T00:00","end":"2026-09-16T00:00"}""",
        )) {
            JSONObject(params)
            assertTrue(params, parse(params).isFailure)
        }
    }

    @Test fun `range intent rejects other selectors and mixed field names`() {
        for (extra in listOf(
            """"query":"Встреча" """, """"target":{}""", """"changes":{}""",
            """"range_start":"2026-09-16T00:00" """, """"range_end":"2026-09-19T00:00" """,
            """"use_last_created":true""", """"use_last_in_range":true""", """"unknown":0""",
        )) {
            val params = valid.dropLast(1) + ",$extra}"
            JSONObject(params)
            assertTrue(params, parse(params).isFailure)
        }
    }

    @Test fun `new boundary spellings are limited to the new intent`() {
        for (intent in listOf("calendar_delete", "calendar_add", "calendar_update", "calendar_search", "calendar_sum", "chat")) {
            assertTrue(intent, parse(valid, intent).isFailure)
        }
    }

    @Test fun `existing range fields in other commands still require a time`() {
        val dates = """{"range_start":"2026-09-16","range_end":"2026-09-19"}"""
        for (intent in listOf("calendar_delete", "calendar_search", "calendar_sum")) {
            assertTrue(intent, parse(dates, intent).isFailure)
        }
        for (intent in listOf("calendar_delete", "calendar_update")) {
            assertTrue(intent, parse("""{"target":{"query":"Встреча","range_start":"2026-09-16","range_end":"2026-09-19"},"changes":{}}""",
                intent).isFailure)
        }
    }
}
