package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.interfaces.LLMEngine
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.ModelParameterProfiles
import com.example.aiassistent1.domain.model.ModelProfile
import com.example.aiassistent1.domain.model.ModelState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelParamsControllerTest {
    @Test
    fun `each conversation uses its own profile and editing the other profile does not touch the engine`() {
        val engine = RecordingEngine()
        val controller = ModelParamsController(engine)
        val state = ChatUiState(activeChatId = "calendar", areModelParamsLoaded = true)
        controller.applyWhenIdle(state, summarizing = false)
        val edited = state.copy(modelProfiles = state.modelProfiles.copy(
            chat = ModelProfile.CHAT.defaults.copy(temperature = 0.85f),
        ))
        controller.applyWhenIdle(edited, summarizing = false)
        assertEquals(listOf(ModelProfile.CALENDAR.defaults), engine.updates)
        controller.applyWhenIdle(edited.copy(activeChatId = "general"), summarizing = false)
        assertEquals(edited.modelProfiles.chat, engine.updates.last())
        assertEquals(2, engine.updates.size)
    }

    @Test
    fun `changing mode and settings during an answer cannot replace its parameters`() {
        val engine = RecordingEngine()
        val controller = ModelParamsController(engine)
        val request = ModelProfile.CALENDAR.defaults.withContextSize(1024)
        controller.applyForRequest(request)
        val next = ChatUiState(activeChatId = "general", areModelParamsLoaded = true, isProcessing = true,
            modelProfiles = ModelParameterProfiles(chat = ModelProfile.CHAT.defaults.copy(topP = 0.75f)))
        controller.applyWhenIdle(next, summarizing = false)
        controller.applyWhenIdle(next.copy(isProcessing = false, isStopping = true), summarizing = false)
        assertEquals(listOf(request), engine.updates)
        controller.applyWhenIdle(next.copy(isProcessing = false), summarizing = false)
        assertEquals(listOf(request, next.modelProfiles.chat), engine.updates)
    }

    @Test
    fun `unread defaults do not reach the engine and summary overrides are restored to the active profile`() {
        val engine = RecordingEngine()
        val controller = ModelParamsController(engine)
        controller.applyWhenIdle(ChatUiState(), summarizing = false)
        assertTrue(engine.updates.isEmpty())
        val summary = ModelProfile.CHAT.defaults.copy(contextSize = 4096, maxTokens = 512)
        controller.applyForRequest(summary)
        val state = ChatUiState(activeChatId = "calendar", areModelParamsLoaded = true)
        controller.applyWhenIdle(state, summarizing = true)
        assertEquals(listOf(summary), engine.updates)
        controller.applyWhenIdle(state, summarizing = false)
        assertEquals(listOf(summary, ModelProfile.CALENDAR.defaults), engine.updates)
    }

    private class RecordingEngine : LLMEngine {
        val updates = mutableListOf<GenerationParams>()
        override val state = MutableStateFlow<ModelState>(ModelState.Unloaded)
        override fun updateParams(params: GenerationParams) { updates += params }
        override suspend fun ensureLoaded(): Result<Unit> = error("Unexpected model load")
        override fun generate(messages: List<ChatMessage>): Flow<String> = error("Unexpected generation")
        override fun cancelGeneration() = Unit
        override fun unload() = Unit
        override fun close() = Unit
    }
}
