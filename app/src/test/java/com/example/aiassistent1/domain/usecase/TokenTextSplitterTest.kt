package com.example.aiassistent1.domain.usecase

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TokenTextSplitterTest {
    @Test fun `all Cyrillic digits and emoji survive chunking with prompt overhead`() = runBlocking {
        val original = "Первый день 🙂 1234567890\n".repeat(100)
        val overhead = 17
        val budget = 64
        val chunks = TokenTextSplitter(budget) { overhead + it.codePointCount(0, it.length) }
            .chunks(flowOf(original.substring(0, 50), original.substring(50))).toList()
        assertEquals(original, chunks.joinToString(""))
        assertTrue(chunks.size > 10)
        chunks.forEach {
            assertTrue(it.codePointCount(0, it.length) + overhead <= budget)
            assertFalse(it.last().isHighSurrogate())
            assertFalse(it.first().isLowSurrogate())
        }
    }
    @Test fun `prefix at surrogate boundary makes progress without losing a code point`() = runBlocking {
        val original = "🙂".repeat(100)
        val chunks = TokenTextSplitter(3) { it.codePointCount(0, it.length) }
            .chunks(flowOf(original)).toList()
        assertEquals(original, chunks.joinToString(""))
        assertTrue(chunks.all { it.codePointCount(0, it.length) <= 3 })
    }
    @Test fun `prompt alone exceeding budget fails before reading source`() = runBlocking {
        assertTrue(runCatching { TokenTextSplitter(10) { 15 }.chunks(flowOf("text")).toList() }.isFailure)
    }
}
