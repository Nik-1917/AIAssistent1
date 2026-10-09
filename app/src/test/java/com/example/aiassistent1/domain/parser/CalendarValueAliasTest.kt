package com.example.aiassistent1.domain.parser

import com.example.aiassistent1.calendar.core.domain.CalendarCommand
import com.example.aiassistent1.domain.mapper.CalendarCommandMapper
import com.example.aiassistent1.domain.model.CalendarAddParams
import org.junit.Assert.*
import org.junit.Test

class CalendarValueAliasTest {
    private val parser = AssistantResponseParser()
    private fun add(fields: String) = parser.parseResult(
        """{"intent":"calendar_add","reply":"x","params":{$fields}}""",
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

    @Test fun `priority is accepted as a calendar add value alias`() {
        for (fields in listOf("\"priority\":15", "\"value\":15,\"priority\":15", "\"date_value\":15,\"priority\":15")) {
            assertEquals(15L, (add(fields).getOrThrow().params as CalendarAddParams).value)
        }
        assertTrue(add("\"value\":15,\"priority\":14").isFailure)
        assertTrue(add("\"date_value\":15,\"priority\":14").isFailure)
    }

    @Test fun `either spelling and matching pairs preserve integer bounds and zero`() {
        for (value in listOf(0L, 4L, -7L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            for (fields in listOf("\"value\":$value", "\"date_value\":$value", "\"value\":$value,\"date_value\":$value")) {
                assertEquals(value, (add(fields).getOrThrow().params as CalendarAddParams).value)
            }
        }
    }

    @Test fun `absent fields remain absent`() {
        assertNull((add("").getOrThrow().params as CalendarAddParams).value)
    }

    @Test fun `conflicting fields are rejected in either order`() {
        for (fields in listOf("\"value\":0,\"date_value\":4", "\"date_value\":4,\"value\":0")) {
            assertTrue(add(fields).exceptionOrNull()!!.message!!.contains("должны совпадать"))
        }
    }

    @Test fun `bad alias type is rejected even alongside valid value`() {
        for (bad in listOf("\"4\"", "4.0", "true", "null", "[]", "{}", "9223372036854775808")) {
            for (fields in listOf("\"date_value\":$bad", "\"value\":4,\"date_value\":$bad", "\"value\":$bad,\"date_value\":4")) {
                assertTrue(fields, add(fields).isFailure)
            }
        }
    }

    @Test fun `alias cannot bypass other intent schemas`() {
        assertTrue(parser.parseResult("""{"intent":"calendar_search","reply":"x","params":{"date_value":4}}""").isFailure)
        assertTrue(add("\"date_value\":4,\"unknown\":1").isFailure)
    }
}
