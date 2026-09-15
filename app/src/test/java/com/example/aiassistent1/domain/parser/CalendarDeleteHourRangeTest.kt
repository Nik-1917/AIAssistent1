package com.example.aiassistent1.domain.parser

import com.example.aiassistent1.calendar.core.domain.CalendarCommand
import com.example.aiassistent1.calendar.core.domain.CalendarTargetMode
import com.example.aiassistent1.domain.mapper.CalendarCommandMapper
import com.example.aiassistent1.domain.model.CalendarDeleteParams
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class CalendarDeleteHourRangeTest {
    private val parser = AssistantResponseParser()
    private val mapper = CalendarCommandMapper(ZoneId.of("Europe/Samara"))
    private val day = """"range_start":"2026-09-16T00:00","range_end":"2026-09-17T00:00" """
    private fun parse(target: String) = parser.parseResult(
        """{"intent":"calendar_delete","reply":"Удаление","params":{"target":{$target}}}""",
    )
    private fun params(target: String) = parse(target).getOrThrow().params as CalendarDeleteParams

    @Test fun `logged hour bounds produce the same command as an explicit narrow range`() {
        val normalized = params(""""query":"Прогулка",$day,"time_min":11,"time_max":12""")
        val explicit = params(
            """"query":"Прогулка","range_start":"2026-09-16T11:00","range_end":"2026-09-16T12:00" """,
        )
        assertEquals(explicit, normalized)
        assertEquals(mapper.map(explicit).getOrThrow(), mapper.map(normalized).getOrThrow())
    }

    @Test fun `absent hour fields preserve prior targets including arbitrary ranges`() {
        val named = params(""""query":"Прогулка" """)
        assertEquals("Прогулка", named.target.query)
        assertNull(named.target.rangeStart)
        assertNull(named.target.rangeEnd)
        val last = params(""""use_last_created":true""")
        assertTrue(last.target.useLastCreated)
        assertEquals(CalendarTargetMode.LAST_CREATED,
            (mapper.map(last).getOrThrow() as CalendarCommand.Delete).target!!.mode)
        assertNull(mapper.map(params("")).getOrThrow().let { (it as CalendarCommand.Delete).target })
        for ((start, end) in listOf(
            "2026-09-16T00:00" to "2026-09-17T00:00",
            "2026-09-16T11:15" to "2026-09-16T12:45",
            "2026-09-01T00:00" to "2026-10-01T00:00",
        )) {
            for (selector in listOf(""""query":"Прогулка" """, """"use_last_in_range":true""")) {
                val parsed = params("""$selector,"range_start":"$start","range_end":"$end" """)
                assertEquals(start, parsed.target.rangeStart)
                assertEquals(end, parsed.target.rangeEnd)
                assertTrue(mapper.map(parsed).isSuccess)
            }
        }
    }

    @Test fun `midnight bounds and next month retain strict timestamp format`() {
        for ((min, max, start, end) in listOf(
            listOf("0", "1", "2026-09-16T00:00", "2026-09-16T01:00"),
            listOf("23", "24", "2026-09-16T23:00", "2026-09-17T00:00"),
            listOf("0", "24", "2026-09-16T00:00", "2026-09-17T00:00"),
        )) {
            val parsed = params(""""query":"Прогулка",$day,"time_min":$min,"time_max":$max""")
            assertEquals(start, parsed.target.rangeStart)
            assertEquals(end, parsed.target.rangeEnd)
            assertTrue(mapper.map(parsed).isSuccess)
        }
        val monthEnd = params(
            """"query":"Прогулка","range_start":"2026-12-31T00:00","range_end":"2027-01-01T00:00","time_min":23,"time_max":24""",
        )
        assertEquals("2027-01-01T00:00", monthEnd.target.rangeEnd)
    }

    @Test fun `last in range uses the narrowed period`() {
        val parsed = params(""""use_last_in_range":true,$day,"time_min":11,"time_max":12""")
        assertTrue(parsed.target.useLastInRange)
        assertEquals("2026-09-16T11:00", parsed.target.rangeStart)
        assertEquals("2026-09-16T12:00", parsed.target.rangeEnd)
        assertEquals(CalendarTargetMode.LAST_IN_RANGE,
            (mapper.map(parsed).getOrThrow() as CalendarCommand.Delete).target!!.mode)
    }

    @Test fun `partial invalid and conflicting hour bounds never fall back to a full day`() {
        for (hours in listOf(
            """"time_min":11""", """"time_max":12""",
            """"time_min":-1,"time_max":12""", """"time_min":11,"time_max":25""",
            """"time_min":12,"time_max":12""", """"time_min":23,"time_max":1""",
            """"time_min":24,"time_max":24""", """"time_min":0,"time_max":0""",
            """"time_min":9223372036854775807,"time_max":24""",
        )) assertTrue(hours, parse(""""query":"Прогулка",$day,$hours""").isFailure)
        for (bad in listOf("\"11\"", "11.5", "true", "null", "[]", "{}")) {
            assertTrue(bad, parse(""""query":"Прогулка",$day,"time_min":$bad,"time_max":12""").isFailure)
            assertTrue(bad, parse(""""query":"Прогулка",$day,"time_min":0,"time_max":$bad""").isFailure)
        }
    }

    @Test fun `hour bounds require exactly one full day`() {
        for (range in listOf(
            "",
            ""","range_start":"2026-09-16T00:00" """,
            ""","range_end":"2026-09-17T00:00" """,
            ""","range_start":"2026-09-16T11:00","range_end":"2026-09-16T12:00" """,
            ""","range_start":"2026-09-16T01:00","range_end":"2026-09-17T01:00" """,
            ""","range_start":"2026-09-16T00:00","range_end":"2026-09-18T00:00" """,
        )) {
            assertTrue(range, parse(""""query":"Прогулка","time_min":11,"time_max":12$range""").isFailure)
        }
    }

    @Test fun `existing selector checks and other intent schemas remain strict`() {
        for (selector in listOf(
            "", """"use_last_created":true,""",
            """"query":"Прогулка","use_last_in_range":true,""",
        )) {
            assertTrue(selector, parse("""$selector$day,"time_min":11,"time_max":12""").isFailure)
        }
        assertTrue(parser.parseResult(
            """{"intent":"calendar_update","reply":"x","params":{"target":{"query":"Прогулка",$day,"time_min":11,"time_max":12},"changes":{}}}""",
        ).isFailure)
        for (intent in listOf("calendar_search", "calendar_sum", "calendar_add", "chat")) {
            assertTrue(intent, parser.parseResult(
                """{"intent":"$intent","reply":"x","params":{"time_min":11,"time_max":12}}""",
            ).isFailure)
        }
        assertTrue(parser.parseResult(
            """{"intent":"calendar_delete","reply":"x","params":{"target":{"query":"Прогулка",$day},"changes":{"value":5}}}""",
        ).isFailure)
    }
}
