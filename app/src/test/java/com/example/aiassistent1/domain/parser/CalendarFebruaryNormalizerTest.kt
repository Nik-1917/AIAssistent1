package com.example.aiassistent1.domain.parser

import com.example.aiassistent1.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class CalendarFebruaryNormalizerTest {
    private val parser = AssistantResponseParser(ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 9, 21)
    private fun parse(fields: String, reply: String = "Событие", history: List<String> = emptyList(), intent: String = "calendar_add") =
        parser.parseResult("""{"intent":"$intent","reply":"$reply","params":{$fields}}""", CalendarDateContext(today, history))
    private fun date(raw: String, history: List<String> = emptyList()): String? =
        (parse(""""date":"$raw"""", history = history).getOrThrow().params as CalendarAddParams).date

    @Test fun `overflow table covers ordinary leap and century years`() {
        for (year in listOf(2026, 2024, 2000, 2100)) {
            for (day in 29..31) {
                val expected = LocalDate.of(year, 2, 1).plusDays(day - 1L).toString()
                assertEquals(expected, date("$year-02-$day"))
            }
        }
    }

    @Test fun `explicit year wins over command reply context and current year`() {
        val response = parse(""""date":"2024-02-29","ends_at":"2025-03-01T10:00"""",
            reply = "29 февраля 2024", history = listOf("на 2027 год")).getOrThrow()
        assertEquals("2024-02-29", (response.params as CalendarAddParams).date)
        assertEquals("29 февраля 2024", response.reply)
    }

    @Test fun `missing year uses command endpoint before conversation`() {
        val response = parse(""""date":"30 февраля","time":"09:00","ends_at":"2024-03-01T10:00"""",
            reply = "30 февраля в 09:00", history = listOf("в 2027 году")).getOrThrow()
        val params = response.params as CalendarAddParams
        assertEquals("2024-03-01", params.date)
        assertEquals(60, params.durationMin)
        assertEquals("1 марта в 09:00", response.reply)
    }

    @Test fun `explicit reply year supplies a missing parameter year`() {
        val response = parse(""""date":"29 февраля"""", reply = "29 февраля 2024", history = listOf("в 2027 году")).getOrThrow()
        assertEquals("2024-02-29", (response.params as CalendarAddParams).date)
    }

    @Test fun `current request then recent history supply year before fallback`() {
        assertEquals("2024-02-29", date("29 февраля", listOf("29 февраля 2024", "в 2027 году")))
        assertEquals("2024-02-29", date("02-29", listOf("Добавь 29 февраля", "Работаем с 2024 годом")))
        assertEquals("2024-02-29", date("29.02", listOf("2024")))
        assertEquals("2024-02-29", date("29 февраля", listOf("год: 2024")))
        assertEquals("2024-03-01", date("30 февраля", listOf("перенеси на 2024-03-02")))
        assertEquals("2024-03-01", date("30 февраля", listOf("перенеси на 02.03.2024")))
    }

    @Test fun `relative year in current request wins over earlier year`() {
        assertEquals("2027-03-01", date("29 февраля", listOf("в следующем году", "в 2024 году")))
        assertEquals("2025-03-01", date("29 февраля", listOf("в прошлом году")))
        assertEquals("2027-03-01", date("29 февраля", listOf("29 февраля следующего года")))
        assertEquals("2024-02-29", date("29 февраля", listOf("в позапрошлом году")))
        assertEquals("2026-03-01", date("29 февраля", listOf("в этом году", "в 2024 году")))
    }

    @Test fun `current year is last resort and unrelated numbers are not years`() {
        assertEquals("2026-03-01", date("29 февраля"))
        assertEquals("2026-03-02", date("30 февраля", listOf("ценность 2024")))
    }

    @Test fun `previous assistant context is consulted before current year but after user context`() {
        val raw = """{"intent":"calendar_add","reply":"29 февраля","params":{"date":"29 февраля"}}"""
        val context = CalendarDateContext(today, listOf("Добавь на тот же год"), listOf("Обсуждаем 2024 год"))
        val response = parser.parseResult(raw, context).getOrThrow()
        assertEquals("2024-02-29", (response.params as CalendarAddParams).date)
        val overridden = parser.parseResult(raw, context.copy(userMessagesNewestFirst = listOf("В 2027 году"))).getOrThrow()
        assertEquals("2027-03-01", (overridden.params as CalendarAddParams).date)
    }

    @Test fun `ambiguous context does not silently select current year`() {
        assertTrue(parse(""""date":"29 февраля"""", history = listOf("29 февраля 2024 или 29 февраля 2028")).isFailure)
        assertTrue(parse(""""date":"29 февраля"""", history = listOf("в прошлом году или в следующем году")).isFailure)
        assertTrue(parse(""""date":"29 февраля","starts_at":"2024-03-01T09:00","ends_at":"2028-03-01T10:00"""" ).isFailure)
    }

    @Test fun `reply correction is limited to overflowing february dates`() {
        val response = parse(""""date":"2026-02-30","title":"30 февраля","notes":"2026-02-30"""",
            reply = "30 февраля 2026, 2026-02-30T09:00, 30.02.2026. «30 февраля» и 31 апреля.").getOrThrow()
        assertEquals("2 марта 2026, 2026-03-02T09:00, 02.03.2026. «30 февраля» и 31 апреля.", response.reply)
        val params = response.params as CalendarAddParams
        assertEquals("30 февраля", params.title)
        assertEquals("2026-02-30", params.notes)
    }

    @Test fun `endpoint normalization precedes duration validation`() {
        val fields = """"starts_at":"2026-02-29T23:30","ends_at":"2026-02-30T00:20""""
        val params = parse(fields).getOrThrow().params as CalendarAddParams
        assertEquals("2026-03-01T23:30", params.startsAt)
        assertEquals("2026-03-02T00:20", params.endsAt)
        assertEquals(50, params.durationMin)
        assertTrue(parse("$fields,\"duration_min\":20").isFailure)
        assertTrue(parse(""""starts_at":"2026-02-30T09:00","ends_at":"2026-03-01T10:00"""" ).isFailure)
    }

    @Test fun `search sum delete update and date only delete boundaries normalize`() {
        val range = """"range_start":"2026-02-29T00:00","range_end":"2026-02-31T00:00""""
        val search = parse(range, intent = "calendar_search").getOrThrow().params as CalendarSearchParams
        assertEquals("2026-03-01T00:00", search.rangeStart)
        assertEquals("2026-03-03T00:00", search.rangeEnd)
        assertTrue(parse(range, intent = "calendar_sum").isSuccess)
        assertTrue(parse(range, intent = "calendar_delete").isSuccess)
        assertTrue(parse(""""target":{$range}""", intent = "calendar_delete").isSuccess)
        val update = parse(""""target":{"query":"Встреча",$range},"changes":{"date":"2024-02-30"}""", intent = "calendar_update").getOrThrow().params as CalendarUpdateParams
        assertEquals("2024-03-01", update.changes.date)
        val delete = parse(""""start":"2026-02-29","end":"2026-02-31"""", intent = "calendar_delete_range").getOrThrow().params as CalendarDeleteParams
        assertEquals("2026-03-01T00:00", delete.target.rangeStart)
        assertEquals("2026-03-03T00:00", delete.target.rangeEnd)
    }

    @Test fun `all other date and JSON errors remain errors`() {
        for (raw in listOf("2026-04-31", "2026-02-32", "2026-13-01", "0000-02-29", "29 апреля")) {
            assertTrue(raw, parse(""""date":"$raw"""" ).isFailure)
        }
        assertTrue(parse(""""date":"2026-02-30","date":"2026-03-02"""" ).isFailure)
        assertTrue(parse(""""date":"2026-02-30","unknown":true""" ).isFailure)
        assertTrue(parse(""""starts_at":"2026-02-30T24:00"""" ).isFailure)
        assertTrue(parse(""""date":20260230""" ).isFailure)
    }

    @Test fun `ordinary chat replies are never normalized by calendar parser`() {
        assertEquals("30 февраля", parse("", reply = "30 февраля", intent = "chat").getOrThrow().reply)
    }
}
