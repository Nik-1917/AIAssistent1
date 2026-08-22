package com.example.aiassistent1.domain.formatter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextChunkerTest {
    @Test
    fun `uses one chunk for every sentence even below the limit`() {
        assertEquals(
            listOf("Да.", "Нет.", "Возможно."),
            SpeechTextChunker.split("Да. Нет. Возможно."),
        )
    }

    @Test
    fun `splits an oversized sentence at its last comma before the limit`() {
        assertEquals(
            listOf("Один два,", "три четыре", "пять."),
            SpeechTextChunker.split("Один два, три четыре пять.", maxChunkLength = 12),
        )
    }

    @Test
    fun `splits an oversized sentence at a word when no comma fits`() {
        assertEquals(
            listOf("один два", "три четыре"),
            SpeechTextChunker.split("один два три четыре", maxChunkLength = 10),
        )
    }

    @Test
    fun `keeps every character of an oversized word`() {
        val word = "а".repeat(13)
        val chunks = SpeechTextChunker.split(word, maxChunkLength = 5)

        assertEquals(listOf("а".repeat(5), "а".repeat(5), "а".repeat(3)), chunks)
        assertEquals(word, chunks.joinToString(""))
    }

    @Test
    fun `does not exceed configured size for long normalized text`() {
        val chunks = SpeechTextChunker.split("  Первый   абзац. Второй абзац без сокращений.  ", maxChunkLength = 20)

        assertTrue(chunks.all { it.length <= 20 })
        assertEquals("Первый абзац. Второй абзац без сокращений.", chunks.joinToString(" "))
    }

    @Test
    fun `recognizes question exclamation ellipsis and closing quote endings`() {
        assertEquals(
            listOf("Да?", "Нет!", "«Возможно…»", "Продолжим"),
            SpeechTextChunker.split("Да? Нет! «Возможно…» Продолжим"),
        )
    }
}
