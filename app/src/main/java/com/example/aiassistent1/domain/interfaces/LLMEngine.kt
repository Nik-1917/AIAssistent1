package com.example.aiassistent1.domain.interfaces

import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.ModelState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface LLMEngine : AutoCloseable {
    val state: StateFlow<ModelState>

    suspend fun ensureLoaded(): Result<Unit>
    fun generate(messages: List<ChatMessage>): Flow<String>
    fun cancelGeneration()
    fun updateParams(params: com.example.aiassistent1.domain.model.GenerationParams)
}