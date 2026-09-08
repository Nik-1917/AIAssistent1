package com.example.aiassistent1.domain.usecase

import com.example.aiassistent1.domain.interfaces.LLMEngine
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.ModelState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatCalendarFieldUseCaseTest {

    private class FakeLLMEngine(val response: String) : LLMEngine {
        var lastMessages: List<ChatMessage>? = null
        override val state: StateFlow<ModelState> = MutableStateFlow(ModelState.Ready)
        override suspend fun ensureLoaded(): Result<Unit> = Result.success(Unit)
        override fun generate(messages: List<ChatMessage>): Flow<String> {
            lastMessages = messages
            return flowOf(response)
        }
        override fun cancelGeneration() {}
        override fun updateParams(params: GenerationParams) {}
        override fun close() {}
    }

    @Test
    fun `invoke sends exactly two messages and returns formatted value`() = runTest {
        val fakeEngine = FakeLLMEngine("""{"field":"title","value":"Team Meeting"}""")
        val useCase = FormatCalendarFieldUseCase(fakeEngine)

        val result = useCase("title", "non_empty_text", "team meeting")

        assertTrue(result.isSuccess)
        assertEquals("Team Meeting", result.getOrNull())
        
        val messages = fakeEngine.lastMessages
        assertEquals(2, messages?.size)
        assertEquals("SYSTEM", messages?.get(0)?.role?.name)
        assertEquals("USER", messages?.get(1)?.role?.name)
        assertTrue(messages?.get(1)?.content?.contains("\"intent\":\"calendar_field_format\"") == true)
    }

    @Test
    fun `parseResponse handles duration_min specifically`() = runTest {
        val fakeEngine = FakeLLMEngine("""{"field":"duration_min","value":90}""")
        val useCase = FormatCalendarFieldUseCase(fakeEngine)

        val result = useCase("duration_min", "positive_integer_minutes", "1.5 hours")

        assertEquals("90", result.getOrNull())
    }
    
    @Test
    fun `fails if JSON is missing`() = runTest {
        val fakeEngine = FakeLLMEngine("Just some text")
        val useCase = FormatCalendarFieldUseCase(fakeEngine)

        val result = useCase("title", "non_empty_text", "test")

        assertTrue(result.isFailure)
        assertEquals("Модель не вернула JSON для форматирования поля.", result.exceptionOrNull()?.message)
    }
}
