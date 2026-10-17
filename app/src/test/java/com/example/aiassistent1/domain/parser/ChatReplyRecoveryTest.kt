package com.example.aiassistent1.domain.parser

import org.junit.Assert.*
import org.junit.Test

class ChatReplyRecoveryTest {
    private val parser = AssistantResponseParser()

    @Test fun `unknown keys nested objects and text arrays preserve readable content`() {
        val cases = mapOf(
            """{"answer":"Привет"}""" to "Привет",
            """{"любой_ключ":{"ещё":["Первый",{"абзац":"Второй"}]}}""" to "Первый\nВторой",
            """{"response":{"text":"Вложенный ответ"}}""" to "Вложенный ответ",
            """{"params":{"response":"Данные"}}""" to "Данные",
            """["Первый",{"text":"Второй"}]""" to "Первый\nВторой",
            """{"reply":null,"answer":"Привет"}""" to "Привет",
            """{"role":"assistant","intent":"chat","emoji":"✨","other":"Ответ"}""" to "Ответ",
            """{"role":{"text":"assistant"},"other":"Ответ"}""" to "Ответ",
            """{"role":{"text":"Настоящий текст"}}""" to "Настоящий текст",
            """{"emoji":"Текст в неожиданном поле"}""" to "Текст в неожиданном поле",
            """"Обычная строка"""" to "Обычная строка",
            "Обычный текст без формата" to "Обычный текст без формата",
            "\"Цитата\" и продолжение" to "\"Цитата\" и продолжение",
            "{answer: Привет}" to "Привет",
            "{\"unfamiliar\":\"Часть ответа" to "Часть ответа",
            "{\"unfamiliar\":\"Часть \\uD83D\\uDE00" to "Часть 😀",
        )
        for ((raw, text) in cases) assertEquals(raw, text, parser.parseChatReply(raw).getOrThrow())
    }

    @Test fun `preferred reply does not concatenate protocol or alternative content`() {
        for (raw in listOf(
            """{"unknown":"Другое","reply":"Ответ"}""",
            """{"response":"Ответ","unknown":"Другое"}""",
            """{"nested":{"text":"Другое"},"reply":"Ответ"}""",
        )) assertEquals("Ответ", parser.parseChatReply(raw).getOrThrow())
    }

    @Test fun `empty output is not replaced by JSON field names or scalar metadata`() {
        for (raw in listOf("", " ", "{}", "[]", "{\"reply\":null}",
            "{\"role\":\"assistant\",\"intent\":\"chat\"}", "{\"count\":3,\"ok\":true}")) {
            assertTrue(raw, parser.parseChatReply(raw).isFailure)
        }
    }

    @Test fun `display recovery never permits execution of a malformed calendar command`() {
        val raw = """{"intent":"calendar_add","answer":"Встреча","params":{"title":"Встреча"}}"""
        assertTrue(parser.parseChatReply(raw).isSuccess)
        assertTrue(parser.parseResult(raw).isFailure)
    }
}
