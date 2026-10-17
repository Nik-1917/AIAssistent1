package com.example.aiassistent1.domain.parser

import com.example.aiassistent1.domain.model.CalendarAddParams
import com.example.aiassistent1.domain.model.CalendarSearchParams
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
    fun `keeps blank calendar titles missing so the draft must request a title`() {
        for (title in listOf("", "   ")) {
            val response = parser.parseResult(
                """{"intent":"calendar_add","reply":"x","params":{"title":"$title","starts_at":"2026-09-13T17:00","ends_at":"2026-09-13T20:00"}}""",
            ).getOrThrow()

            val params = response.params as CalendarAddParams
            assertNull(params.title)
            assertEquals("2026-09-13T17:00", params.startsAt)
            assertEquals(180, params.durationMin)
        }
        val namedEvent = parser.parseResult(
            """{"intent":"calendar_add","reply":"x","params":{"title":"Событие"}}""",
        ).getOrThrow().params as CalendarAddParams
        assertEquals("Событие", namedEvent.title)
        assertTrue(parser.parseResult("""{"intent":"calendar_add","reply":"x","params":{"title":null}}""").isFailure)
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
    fun `both chat intents may omit params and preserve the literal reply`() {
        for (intent in listOf("chat", "chat_reply")) {
            val withoutParams = parser.parseResult(
                """{"intent":"$intent","reply":"  Уточните \"Завтра\".\nВремя 09:15.  "}""",
            ).getOrThrow()
            val withEmptyParams = parser.parseResult(
                """{"intent":"$intent","reply":"  Уточните \"Завтра\".\nВремя 09:15.  ","params":{}}""",
            ).getOrThrow()

            assertEquals(intent, withoutParams.intent)
            assertEquals("  Уточните \"Завтра\".\nВремя 09:15.  ", withoutParams.reply)
            assertNull(withoutParams.params)
            assertEquals(withEmptyParams, withoutParams)
        }
    }

    @Test
    fun `both chat intents reject invalid or nonempty supplied params`() {
        for (intent in listOf("chat", "chat_reply")) {
            for (params in listOf("null", "[]", "\"\"", "0", "true", "false", """{"title":"Встреча"}""")) {
                val raw = """{"intent":"$intent","reply":"Уточните запрос.","params":$params}"""
                assertTrue(raw, parser.parseResult(raw).isFailure)
            }
        }
    }

    @Test
    fun `omitting chat params does not relax reply or envelope validation`() {
        for (intent in listOf("chat", "chat_reply")) {
            for (raw in listOf(
                """{"intent":"$intent"}""",
                """{"intent":"$intent","reply":""}""",
                """{"intent":"$intent","reply":"   "}""",
                """{"intent":"$intent","reply":null}""",
                """{"intent":"$intent","reply":123}""",
                """{"intent":"$intent","reply":"Текст","extra":true}""",
                """{"intent":"$intent","reply":"Текст","reply":"Другой текст"}""",
            )) {
                assertTrue(raw, parser.parseResult(raw).isFailure)
            }
        }
    }

    @Test
    fun `all supported calendar commands still require a params object`() {
        for (intent in listOf("calendar_add", "calendar_search", "calendar_sum")) {
            val missing = parser.parseResult("""{"intent":"$intent","reply":"Текст"}""")
            assertTrue(intent, missing.isFailure)
            assertEquals("$.params: требуется объект", missing.exceptionOrNull()?.message)
            for (params in listOf("null", "[]", "\"\"", "0", "true", "false")) {
                val raw = """{"intent":"$intent","reply":"Текст","params":$params}"""
                assertTrue(raw, parser.parseResult(raw).isFailure)
            }
            // Empty objects remain valid incomplete commands, as before this change.
            assertTrue(parser.parseResult("""{"intent":"$intent","reply":"Текст","params":{}}""")
                .getOrThrow().params != null)
        }
    }

    @Test
    fun `extracts only the text of a chat response`() {
        assertEquals("Привет, \"друг\"!\nКак дела?", parser.parseChatReply(
            """{"intent":"chat","reply":"Привет, \"друг\"!\nКак дела?","params":{}}""",
        ).getOrThrow())
    }

    @Test
    fun `extracts response from the actual device reply without its emoji metadata`() {
        val raw = """{"response":"Привет! Сегодня четверг, 15 октября 2026 года.","emoji":"✨"}"""
        assertEquals("Привет! Сегодня четверг, 15 октября 2026 года.", parser.parseChatReply(raw).getOrThrow())
        // This chat-only format must not become an executable calendar response.
        assertTrue(parser.parseResult(raw).isFailure)
    }

    @Test
    fun `reply takes precedence over response regardless of order`() {
        for (raw in listOf(
            """{"response":"Другой текст","reply":"Основной текст"}""",
            """{"reply":"Основной текст","response":"Другой текст"}""",
            """{"response":{"text":"Метаданные"},"reply":"Основной текст"}""",
        )) assertEquals(raw, "Основной текст", parser.parseChatReply(raw).getOrThrow())
    }

    @Test
    fun `response text tolerates partial output and keeps escaped punctuation`() {
        assertEquals("Текст (1) {2}\n\"цитата\"", parser.parseChatReply(
            """{"response":"Текст (1) {2}\n\"цитата\"","emoji":"✨"}""",
        ).getOrThrow())
        assertEquals("Часть ответа", parser.parseChatReply("{\"response\":\"Часть ответа").getOrThrow())
        assertEquals("Текст", parser.parseChatReply("""{"reply":null,"response":"Текст"}""").getOrThrow())
        assertEquals("Текст", parser.parseChatReply("""{"response":"Текст","reply":""}""").getOrThrow())
    }

    @Test
    fun `chat without text still rejects empty response and emoji metadata`() {
        for (raw in listOf(
            """{"response":null,"emoji":"✨"}""",
            """{"response":"","emoji":"✨"}""",
        )) assertTrue(raw, parser.parseChatReply(raw).isFailure)
    }

    @Test
    fun `chat text extraction ignores intent params and additional metadata`() {
        for (raw in listOf(
            """{"intent":"calendar_add","reply":"Текст","params":{}}""",
            """{"intent":"chat","reply":"Текст","params":{"title":"Событие"}}""",
            """{"intent":null,"params":[1.5,{"reply":"Вложенное значение"}],"reply":"Текст"}""",
            """{"intent":"chat","reply":"Текст","params":{},"extra":1}""",
            """{"reply":"Текст"}""",
            """{'intent':'chat','reply':'Текст'}""",
        )) {
            assertEquals(raw, "Текст", parser.parseChatReply(raw).getOrThrow())
        }
        // Text presentation does not loosen validation of executable calendar commands.
        assertTrue(parser.parseResult("""{"intent":"chat","reply":"Текст","params":null}""").isFailure)
    }

    @Test
    fun `chat extraction does not require a complete object after the reply`() {
        for (raw in listOf(
            "{\"reply\":\"Текст\",\"params\":",
            "{\"reply\":\"Текст\"",
            """{"reply":"Текст"} trailing text""",
        )) {
            assertEquals("Текст", parser.parseChatReply(raw).getOrThrow())
        }
    }

    @Test
    fun `unfinished chat string preserves generated text without incomplete escapes`() {
        assertEquals("Текст", parser.parseChatReply("{\"reply\":\"Текст").getOrThrow())
        assertEquals("Текст", parser.parseChatReply("{\"reply\":\"Текст\\").getOrThrow())
        assertEquals("Текст", parser.parseChatReply("{\"reply\":\"Текст\\u04").getOrThrow())
        assertEquals("Текст", parser.parseChatReply("{\"reply\":\"Текст\\uD83D").getOrThrow())
        assertEquals("Текст 😀", parser.parseChatReply("{\"reply\":\"Текст \\uD83D\\uDE00").getOrThrow())
    }

    @Test
    fun `chat extractor never substitutes protocol fields for missing text`() {
        for (raw in listOf(
            """{"intent":"chat","role":"assistant"}""",
            """{"intent":"chat","reply":"","params":{}}""",
            """{"reply":null,"params":{}}""",
        )) assertTrue(parser.parseChatReply(raw).isFailure)
    }

    @Test
    fun `treats chat_reply as an alias of chat`() {
        val response = parser.parse("""{"intent":"chat_reply","reply":"На какое время?","params":{}}""")

        assertEquals("chat_reply", response?.intent)
        assertNull(response?.params)
        assertTrue(response?.reply?.isNotBlank() == true)
    }

    @Test
    fun `rejects an unsupported intent`() {
        val response = parser.parse(
            """{"intent":"calendar_future_action","reply":"x","params":{}}""",
        )

        assertNull(response)
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
