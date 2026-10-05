package com.example.aiassistent1.domain.usecase

import com.example.aiassistent1.domain.interfaces.LLMEngine
import com.example.aiassistent1.domain.model.*
import com.example.aiassistent1.domain.provider.SystemPromptProvider
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LongTextSummarizerTest {
    @Test fun `a lower memory budget repartitions the full source without skipping any characters`() = runBlocking {
        val engine = FakeEngine().apply { shrinkOnFirst = true }
        val source = "x".repeat(1200)
        assertEquals("итог", LongTextSummarizer(engine).summarize(flowOf(source)))
        assertEquals(1200, engine.inputs.sumOf { it.count { character -> character == 'x' } })
        assertTrue(engine.inputs.all { it.length + 10 <= 80 })
        assertEquals(1200, source.length)
    }
    @Test fun `every source character is processed and complete summaries feed tree reduction`() = runBlocking {
        val engine = FakeEngine()
        val result = LongTextSummarizer(engine).summarize(flowOf("x".repeat(1200)))
        assertEquals("итог", result)
        assertEquals(1200, engine.inputs.sumOf { it.count { char -> char == 'x' } })
        assertTrue(engine.inputs.size > 10)
        assertTrue(engine.inputs.any { it.contains("итог\n\nитог") })
        assertTrue(engine.inputs.all { it.length + 10 <= 128 })
    }
    @Test fun `noncompressing model fails explicitly instead of cutting its response`() = runBlocking {
        val engine = FakeEngine().apply { echo = true }
        val failure = runCatching { LongTextSummarizer(engine).summarize(flowOf("x".repeat(100))) }
        assertTrue(failure.isFailure)
        assertEquals(3, engine.inputs.size)
        assertTrue(engine.inputs.all { it.length == 100 })
    }
    @Test fun `chat overflow reaches history selection without silently replacing the answer by a summary`() = runBlocking {
        val engine = FakeEngine().apply { overflow = true }
        val source = listOf(ChatMessage(role = MessageRole.USER, content = "x".repeat(1200)))
        val failure = runCatching { SendMessageUseCase(engine, SystemPromptProvider())(source, isCalendarMode = false)
            .getOrThrow().toList() }.exceptionOrNull()
        assertTrue(failure is PromptCapacityException)
        assertTrue(engine.inputs.isEmpty())
        assertEquals(1200, source.single().content.length)
    }
    @Test fun `calendar overflow is never summarized or partially executed`() = runBlocking {
        val engine = FakeEngine().apply { overflow = true }
        val source = listOf(ChatMessage(role = MessageRole.USER, content = "x".repeat(1200)))
        val failure = runCatching { SendMessageUseCase(engine, SystemPromptProvider())(source, isCalendarMode = true)
            .getOrThrow().toList() }.exceptionOrNull()
        assertTrue(failure is PromptCapacityException)
        assertTrue(engine.inputs.isEmpty())
    }
    private class FakeEngine : LLMEngine {
        val inputs = mutableListOf<String>()
        var echo = false
        var overflow = false
        var shrinkOnFirst = false
        override val state = MutableStateFlow<ModelState>(ModelState.Ready)
        override suspend fun ensureLoaded() = Result.success(Unit)
        override fun generate(messages: List<ChatMessage>): Flow<String> = error("Legacy generation is forbidden")
        override fun generateForTask(messages: List<ChatMessage>, task: GenerationTask): Flow<String> = flow {
            if (overflow && task != GenerationTask.SUMMARY) throw PromptCapacityException(128)
            if (shrinkOnFirst) {
                shrinkOnFirst = false
                throw PromptCapacityException(80)
            }
            val text = messages.last().content
            inputs += text
            emit(if (echo) text else "итог")
        }
        override suspend fun countTokens(messages: List<ChatMessage>) = 10 + messages.last().content.length
        override suspend fun promptTokenBudget(task: GenerationTask) = 128
        override fun updateParams(params: GenerationParams) = Unit
        override fun cancelGeneration() = Unit
        override fun unload() = Unit
        override fun close() = Unit
    }
}
