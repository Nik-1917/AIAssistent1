package com.example.aiassistent1.domain.interfaces

import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.ModelState
import com.example.aiassistent1.domain.context.ContextBudget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface LLMEngine : AutoCloseable {
    val state: StateFlow<ModelState>
    val contextBudget: Flow<ContextBudget?> get() = kotlinx.coroutines.flow.flowOf(null)

    suspend fun ensureLoaded(): Result<Unit>
    /** Проверка завершается до очистки черновика и сохранения нового сообщения в историю. */
    suspend fun prepareGeneration(
        messages: List<ChatMessage>,
        userMessageForSizing: String? = null,
    ): Result<Flow<String>> =
        ensureLoaded().map { generate(messages) }
    fun generate(messages: List<ChatMessage>): Flow<String>
    fun cancelGeneration()
    fun updateParams(params: com.example.aiassistent1.domain.model.GenerationParams)

    /** Releases the model while keeping this engine available for another load. */
    fun unload()

    /** Permanently releases the model and execution resources. Repeated calls are safe. */
    override fun close()
}
