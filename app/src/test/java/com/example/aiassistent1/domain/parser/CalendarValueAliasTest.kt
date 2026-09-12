package com.example.aiassistent1.domain.parser

import com.example.aiassistent1.calendar.core.domain.CalendarCommand
import com.example.aiassistent1.calendar.core.domain.CalendarValueChange
import com.example.aiassistent1.domain.mapper.CalendarCommandMapper
import com.example.aiassistent1.domain.model.CalendarAddParams
import com.example.aiassistent1.domain.model.CalendarUpdateParams
import org.junit.Assert.*
import org.junit.Test

class CalendarValueAliasTest {
    private val parser = AssistantResponseParser()
    private fun add(fields: String) = parser.parseResult(
        """{"intent":"calendar_add","reply":"x","params":{$fields}}""",
    )
    private fun update(fields: String) = parser.parseResult(
        """{"intent":"calendar_update","reply":"x","params":{"target":{"use_last_created":true},"changes":{$fields}}}""",
    )

    @Test fun `actual failing response now maps to value four`() {
        val response = parser.parseResult("""{"intent":"calendar_add","reply":"Чтение книги.","params":{"title":"Чтение книги","starts_at":"2026-10-03T21:10","duration_min":30,"date_value":4}}""").getOrThrow()
        val params = response.params as CalendarAddParams
        assertEquals(4L, params.value)
        val command = CalendarCommandMapper().map(params).getOrThrow() as CalendarCommand.Add
        assertEquals(4L, command.value)
        assertEquals("Чтение книги", command.title)
        assertEquals(30, command.durationMinutes)
    }

    @Test fun `either spelling and matching pairs preserve integer bounds and zero`() {
        for (value in listOf(0L, 4L, -7L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            for (fields in listOf("\"value\":$value", "\"date_value\":$value", "\"value\":$value,\"date_value\":$value")) {
                assertEquals(value, (add(fields).getOrThrow().params as CalendarAddParams).value)
                val params = update(fields).getOrThrow().params as CalendarUpdateParams
                assertEquals(value, params.changes.value)
                val command = CalendarCommandMapper().map(params).getOrThrow() as CalendarCommand.Update
                assertEquals(CalendarValueChange.Set(value), command.changes.valueChange)
            }
        }
    }

    @Test fun `absent fields remain absent`() {
        assertNull((add("").getOrThrow().params as CalendarAddParams).value)
        val params = update("").getOrThrow().params as CalendarUpdateParams
        assertNull(params.changes.value)
        val command = CalendarCommandMapper().map(params).getOrThrow() as CalendarCommand.Update
        assertEquals(CalendarValueChange.Keep, command.changes.valueChange)
    }

    @Test fun `conflicting fields are rejected in either order`() {
        for (fields in listOf("\"value\":0,\"date_value\":4", "\"date_value\":4,\"value\":0")) {
            assertTrue(add(fields).exceptionOrNull()!!.message!!.contains("должны совпадать"))
            assertTrue(update(fields).isFailure)
        }
    }

    @Test fun `bad alias type is rejected even alongside valid value`() {
        for (bad in listOf("\"4\"", "4.0", "true", "null", "[]", "{}", "9223372036854775808")) {
            for (fields in listOf("\"date_value\":$bad", "\"value\":4,\"date_value\":$bad", "\"value\":$bad,\"date_value\":4")) {
                assertTrue(fields, add(fields).isFailure)
                assertTrue(fields, update(fields).isFailure)
            }
        }
    }

    @Test fun `alias cannot bypass clear value conflict or other intent schemas`() {
        assertTrue(update("\"date_value\":0,\"clear_value\":true").isFailure)
        assertTrue(update("\"value\":4,\"date_value\":4,\"clear_value\":true").isFailure)
        val clear = update("\"clear_value\":true").getOrThrow().params as CalendarUpdateParams
        assertTrue(clear.changes.clearValue)
        assertNull(clear.changes.value)
        assertTrue(parser.parseResult("""{"intent":"calendar_search","reply":"x","params":{"date_value":4}}""").isFailure)
        assertTrue(add("\"date_value\":4,\"unknown\":1").isFailure)
    }
}
