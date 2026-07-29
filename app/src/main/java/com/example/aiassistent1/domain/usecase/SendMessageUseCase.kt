package com.example.aiassistent1.domain.usecase

import com.example.aiassistent1.domain.interfaces.LLMEngine
import com.example.aiassistent1.domain.model.ChatMessage
import kotlinx.coroutines.flow.Flow

class SendMessageUseCase(
    private val llmEngine: LLMEngine,
) {
    suspend operator fun invoke(messages: List<ChatMessage>): Result<Flow<String>> =
        llmEngine.ensureLoaded().map { llmEngine.generate(messages) }
}