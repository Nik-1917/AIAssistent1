package com.example.aiassistent1.domain.parser

import com.example.aiassistent1.calendar.core.domain.CalendarCommand
import com.example.aiassistent1.calendar.core.domain.CalendarTargetMode
import com.example.aiassistent1.calendar.core.domain.CalendarValueChange
import com.example.aiassistent1.domain.mapper.CalendarCommandMapper
import com.example.aiassistent1.domain.model.CalendarDeleteParams
import com.example.aiassistent1.domain.model.CalendarUpdateParams
import org.junit.Assert.*
import org.junit.Test

class CalendarUpdateTargetAliasTest {
    private val parser = AssistantResponseParser()
    private val keys = listOf("use_last_created", "use_last", "use_last_in_range")
    private fun update(target: String) = parser.parseResult(
        """{"intent":"calendar_update","reply":"Событие изменено: ценность последнего события двенадцать тысяч пятьсот.","params":{"target":{$target},"changes":{"value":12500}}}""",
    )

    @Test fun `all true flag combinations map to the same last created update`() {
        for (mask in 1..7) {
            val fields = keys.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
                .joinToString(",") { """"$it":true""" }
            val params = update(fields).getOrThrow().params as CalendarUpdateParams
            assertTrue(params.target.useLastCreated)
            assertNull(params.target.query)
            assertNull(params.target.rangeStart)
            assertNull(params.target.rangeEnd)
            val command = CalendarCommandMapper().map(params).getOrThrow() as CalendarCommand.Update
            assertEquals(CalendarTargetMode.LAST_CREATED, command.target!!.mode)
            assertEquals(CalendarValueChange.Set(12500L), command.changes.valueChange)
        }
        assertFalse((update("").getOrThrow().params as CalendarUpdateParams).target.useLastCreated)
    }

    @Test fun `valid flags never mask invalid companion flags in either field order`() {
        for (key in keys) {
            for (bad in listOf("false", "1", "\"true\"", "null", "[]", "{}")) {
                val invalid = """"$key":$bad"""
                val companions = keys.filter { it != key }.joinToString(",") { """"$it":true""" }
                for (fields in listOf(invalid, "$invalid,$companions", "$companions,$invalid")) {
                    assertTrue(fields, update(fields).isFailure)
                }
            }
        }
    }

    @Test fun `aliases cannot select by query or by period`() {
        val selectors = listOf(
            """"query":"Встреча" """,
            """"range_start":"2026-09-15T00:00" """,
            """"range_end":"2026-09-16T00:00" """,
            """"range_start":"2026-09-15T00:00","range_end":"2026-09-16T00:00" """,
            """"query":"Встреча","range_start":"2026-09-15T00:00","range_end":"2026-09-16T00:00" """,
        )
        for (key in keys) {
            for (selector in selectors) {
                val fields = """"$key":true,$selector"""
                assertTrue(fields, update(fields).isFailure)
            }
        }
    }

    @Test fun `delete still requires a period and keeps its original selection mode`() {
        assertTrue(parser.parseResult(
            """{"intent":"calendar_delete","reply":"x","params":{"target":{"use_last_in_range":true}}}""",
        ).isFailure)
        val params = parser.parseResult(
            """{"intent":"calendar_delete","reply":"x","params":{"target":{"use_last_in_range":true,"range_start":"2026-09-15T00:00","range_end":"2026-09-16T00:00"}}}""",
        ).getOrThrow().params as CalendarDeleteParams
        assertTrue(params.target.useLastInRange)
        assertFalse(params.target.useLastCreated)
        val command = CalendarCommandMapper().map(params).getOrThrow() as CalendarCommand.Delete
        assertEquals(CalendarTargetMode.LAST_IN_RANGE, command.target!!.mode)
        for (intent in listOf("calendar_add", "calendar_search", "calendar_sum", "chat")) {
            assertTrue(parser.parseResult(
                """{"intent":"$intent","reply":"x","params":{"use_last_in_range":true}}""",
            ).isFailure)
        }
    }
}
