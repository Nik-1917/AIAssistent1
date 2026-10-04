package com.example.aiassistent1.domain.interfaces

import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.ModelState
import com.example.aiassistent1.domain.model.AutomaticGenerationState
import com.example.aiassistent1.domain.model.GenerationTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

private val emptyAutomaticState = MutableStateFlow<AutomaticGenerationState?>(null).asStateFlow()

interface LLMEngine : AutoCloseable {
    val state: StateFlow<ModelState>
    val automaticState: StateFlow<AutomaticGenerationState?> get() = emptyAutomaticState

    suspend fun ensureLoaded(): Result<Unit>
    fun generate(messages: List<ChatMessage>): Flow<String>
    fun generateForTask(messages: List<ChatMessage>, task: GenerationTask): Flow<String> = flow {
        ensureLoaded().getOrThrow()
        emitAll(generate(messages))
    }
    suspend fun countTokens(messages: List<ChatMessage>): Int = error("Точный токенизатор недоступен")
    suspend fun promptTokenBudget(task: GenerationTask): Int = error("Автоматический бюджет недоступен")
    fun cancelGeneration()
    fun updateParams(params: com.example.aiassistent1.domain.model.GenerationParams)

    /** Releases the model while keeping this engine available for another load. */
    fun unload()

    /** Permanently releases the model and execution resources. Repeated calls are safe. */
    override fun close()
}
