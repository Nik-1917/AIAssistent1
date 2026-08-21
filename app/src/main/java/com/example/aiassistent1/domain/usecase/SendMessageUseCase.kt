package com.example.aiassistent1.domain.usecase

import com.example.aiassistent1.domain.interfaces.LLMEngine
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.MessageRole
import com.example.aiassistent1.domain.provider.SystemPromptProvider
import kotlinx.coroutines.flow.Flow

class SendMessageUseCase(
    private val llmEngine: LLMEngine,
    private val systemPromptProvider: SystemPromptProvider,
) {
    suspend operator fun invoke(
        messages: List<ChatMessage>,
        useSystemPrompt: Boolean = false,
    ): Result<Flow<String>> {
        val systemMessage = ChatMessage(
            role = MessageRole.SYSTEM,
            content = systemPromptProvider.getSystemPrompt()
        )
        val modelMessages = if (useSystemPrompt) listOf(systemMessage) + messages else messages
        return llmEngine.ensureLoaded().map { llmEngine.generate(modelMessages) }
    }
}
