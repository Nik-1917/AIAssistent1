package com.example.aiassistent1

import com.example.aiassistent1.domain.interfaces.LLMEngine
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.MessageRole
import com.example.aiassistent1.domain.model.ModelState
import com.example.aiassistent1.domain.usecase.SendMessageUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SendMessageUseCaseTest {
    @Test
    fun `returns streamed deltas after successful model loading`() = runTest {
        val engine = FakeLlmEngine(loadResult = Result.success(Unit))
        val useCase = SendMessageUseCase(engine)

        val result = useCase(listOf(ChatMessage(role = MessageRole.USER, content = "Привет")))

        assertEquals(listOf("Первый", " ответ"), result.getOrThrow().toList())
        assertEquals(1, engine.generateCalls)
    }

    @Test
    fun `does not generate when model loading fails`() = runTest {
        val engine = FakeLlmEngine(loadResult = Result.failure(IllegalStateException("Нет модели")))
        val useCase = SendMessageUseCase(engine)

        val result = useCase(emptyList())

        assertFalse(result.isSuccess)
        assertEquals(0, engine.generateCalls)
    }

    private class FakeLlmEngine(
        private val loadResult: Result<Unit>,
    ) : LLMEngine {
        private val mutableState = MutableStateFlow<ModelState>(ModelState.Unloaded)
        var generateCalls = 0

        override val state: StateFlow<ModelState> = mutableState

        override suspend fun ensureLoaded(): Result<Unit> = loadResult

        override fun generate(messages: List<ChatMessage>): Flow<String> {
            generateCalls += 1
            return flowOf("Первый", " ответ")
        }

        override fun cancelGeneration() = Unit

        override fun close() = Unit
    }
}