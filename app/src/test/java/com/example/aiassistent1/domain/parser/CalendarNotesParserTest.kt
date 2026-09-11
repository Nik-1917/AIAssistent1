package com.example.aiassistent1.domain.parser

import com.example.aiassistent1.domain.model.CalendarAddParams
import com.example.aiassistent1.domain.model.CalendarUpdateParams
import org.junit.Assert.*
import org.junit.Test

class CalendarNotesParserTest {
    private val parser = AssistantResponseParser()

    @Test fun `accepts the model response that previously failed without inventing value`() {
        val response = parser.parseResult("""{"intent":"calendar_add","reply":"Оплата аренды.","params":{"title":"Оплата аренды","starts_at":"2026-09-11T14:30","duration_min":20,"notes":"Я заработаю двенадцать пятьсот"}}""").getOrThrow()
        val params = response.params as CalendarAddParams
        assertEquals("Я заработаю двенадцать пятьсот", params.notes)
        assertNull(params.value)
    }

    @Test fun `preserves exact text including whitespace unicode quotes and newlines`() {
        val params = parser.parseResult("""{"intent":"calendar_add","reply":"x","params":{"notes":"  ТЕКСТ: \"цитата\"\nhttps://example.org/?x=1\n12500 ₽ 😀  "}}""").getOrThrow().params as CalendarAddParams
        assertEquals("  ТЕКСТ: \"цитата\"\nhttps://example.org/?x=1\n12500 ₽ 😀  ", params.notes)
    }

    @Test fun `absent and blank notes are optional in add and update`() {
        for (field in listOf("", "\"notes\":\"\"", "\"notes\":\"  \\n  \"")) {
            val add = parser.parseResult("""{"intent":"calendar_add","reply":"x","params":{$field}}""").getOrThrow().params as CalendarAddParams
            val update = parser.parseResult("""{"intent":"calendar_update","reply":"x","params":{"target":{"use_last_created":true},"changes":{$field}}}""").getOrThrow().params as CalendarUpdateParams
            assertNull(add.notes)
            assertNull(update.changes.notes)
        }
    }

    @Test fun `notes-only update is accepted`() {
        val params = parser.parseResult("""{"intent":"calendar_update","reply":"x","params":{"target":{"use_last_created":true},"changes":{"notes":"Новый текст"}}}""").getOrThrow().params as CalendarUpdateParams
        assertEquals("Новый текст", params.changes.notes)
        assertNull(params.changes.value)
    }

    @Test fun `does not loosen types unknown fields or other intents`() {
        for (value in listOf("42", "true", "null", "[]", "{}")) {
            assertTrue(parser.parseResult("""{"intent":"calendar_add","reply":"x","params":{"notes":$value}}""").isFailure)
            assertTrue(parser.parseResult("""{"intent":"calendar_update","reply":"x","params":{"target":{"use_last_created":true},"changes":{"notes":$value}}}""").isFailure)
        }
        assertTrue(parser.parseResult("""{"intent":"calendar_add","reply":"x","params":{"notes":"x","unknown":"x"}}""").isFailure)
        assertTrue(parser.parseResult("""{"intent":"calendar_search","reply":"x","params":{"notes":"x"}}""").isFailure)
    }
}
