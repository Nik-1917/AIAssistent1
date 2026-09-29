package com.example.aiassistent1

import com.example.aiassistent1.domain.interfaces.LLMEngine
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.MessageRole
import com.example.aiassistent1.domain.model.ModelState
import com.example.aiassistent1.domain.provider.SystemPromptProvider
import com.example.aiassistent1.domain.usecase.SendMessageUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendMessageUseCaseTest {
    @Test
    fun `keeps raw new message separate from system history and style instructions`() = runTest {
        val engine = FakeLlmEngine(loadResult = Result.success(Unit))
        val useCase = SendMessageUseCase(engine, SystemPromptProvider())
        val raw = "Привет"
        val history = listOf(ChatMessage(role = MessageRole.USER, content = "Предыдущий вопрос"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Предыдущий ответ"),
            ChatMessage(role = MessageRole.USER, content = raw))
        val messages = com.example.aiassistent1.domain.context.ModelContextBuilder()
            .build(history, appendChatStyleInstruction = true)
        useCase(messages, useSystemPrompt = true, isCalendarMode = false, userMessageForSizing = raw)
            .getOrThrow().toList()
        assertEquals(raw, engine.lastUserMessageForSizing)
        assertEquals(MessageRole.SYSTEM, engine.lastMessages.first().role)
        assertTrue(engine.lastMessages.last().content.contains("отвечай очень вежливо используй эмодзи"))
        assertEquals(4, engine.lastMessages.size)
    }

    @Test
    fun `returns streamed deltas after successful model loading`() = runTest {
        val engine = FakeLlmEngine(loadResult = Result.success(Unit))
        val useCase = SendMessageUseCase(engine, SystemPromptProvider())

        val result = useCase(listOf(ChatMessage(role = MessageRole.USER, content = "Привет")))

        assertEquals(listOf("Первый", " ответ"), result.getOrThrow().toList())
        assertEquals(1, engine.generateCalls)
        assertEquals(listOf(MessageRole.USER), engine.lastMessages.map(ChatMessage::role))
    }

    @Test
    fun `adds the system prompt only when enabled`() = runTest {
        val engine = FakeLlmEngine(loadResult = Result.success(Unit))
        val useCase = SendMessageUseCase(engine, SystemPromptProvider())

        useCase(
            messages = listOf(ChatMessage(role = MessageRole.USER, content = "Привет")),
            useSystemPrompt = true,
        ).getOrThrow().toList()

        assertEquals(listOf(MessageRole.SYSTEM, MessageRole.USER), engine.lastMessages.map(ChatMessage::role))
    }

    @Test
    fun `does not generate when model loading fails`() = runTest {
        val engine = FakeLlmEngine(loadResult = Result.failure(IllegalStateException("Нет модели")))
        val useCase = SendMessageUseCase(engine, SystemPromptProvider())

        val result = useCase(emptyList())

        assertFalse(result.isSuccess)
        assertEquals(0, engine.generateCalls)
    }

    private class FakeLlmEngine(
        private val loadResult: Result<Unit>,
    ) : LLMEngine {
        private val mutableState = MutableStateFlow<ModelState>(ModelState.Unloaded)
        var generateCalls = 0
        var lastMessages: List<ChatMessage> = emptyList()
        var lastUserMessageForSizing: String? = null

        override val state: StateFlow<ModelState> = mutableState

        override suspend fun ensureLoaded(): Result<Unit> = loadResult

        override suspend fun prepareGeneration(messages: List<ChatMessage>, userMessageForSizing: String?): Result<Flow<String>> {
            lastUserMessageForSizing = userMessageForSizing
            return super<LLMEngine>.prepareGeneration(messages, userMessageForSizing)
        }

        override fun generate(messages: List<ChatMessage>): Flow<String> {
            generateCalls += 1
            lastMessages = messages
            return flowOf("Первый", " ответ")
        }

        override fun cancelGeneration() = Unit

        override fun updateParams(params: com.example.aiassistent1.domain.model.GenerationParams) = Unit

        override fun unload() = Unit

        override fun close() = Unit
    }
}
