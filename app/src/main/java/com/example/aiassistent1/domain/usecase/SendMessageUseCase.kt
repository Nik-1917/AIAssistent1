package com.example.aiassistent1.domain.usecase

import com.example.aiassistent1.domain.interfaces.LLMEngine
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.MessageRole
import com.example.aiassistent1.domain.model.GenerationTask
import com.example.aiassistent1.domain.model.PromptCapacityException
import com.example.aiassistent1.domain.provider.SystemPromptProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

class SendMessageUseCase(
    private val llmEngine: LLMEngine,
    private val systemPromptProvider: SystemPromptProvider,
) {
    suspend operator fun invoke(
        messages: List<ChatMessage>,
        useSystemPrompt: Boolean = false,
        isCalendarMode: Boolean = true,
    ): Result<Flow<String>> {
        val systemMessage = ChatMessage(
            role = MessageRole.SYSTEM,
            content = systemPromptProvider.getSystemPrompt(isCalendarMode)
        )
        val modelMessages = if (useSystemPrompt) listOf(systemMessage) + messages else messages
        return Result.success(flow {
            var emitted = false
            try {
                llmEngine.generateForTask(modelMessages,
                    if (isCalendarMode) GenerationTask.CALENDAR else GenerationTask.CHAT).collect {
                    emitted = true
                    emit(it)
                }
            } catch (capacity: PromptCapacityException) {
                if (isCalendarMode || emitted) throw capacity
                // Preserve requests before old replies. Database/UI history remains intact.
                val requests = modelMessages.filter { it.role != MessageRole.ASSISTANT }
                try {
                    llmEngine.generateForTask(requests, GenerationTask.CHAT).collect { emitted = true; emit(it) }
                } catch (requestCapacity: PromptCapacityException) {
                    if (emitted) throw requestCapacity
                    val source = requests.filter { it.role == MessageRole.USER }.joinToString("\n\n") { it.content }
                    val summary = LongTextSummarizer(llmEngine).summarize(flowOf(source), requestCapacity.promptBudget)
                    emit("Выжимка длинного текста:\n\n")
                    emit(summary)
                }
            }
        })
    }
}
