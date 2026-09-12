package com.example.aiassistent1.domain.context

import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.MessageRole

class ModelContextBuilder(
    private val maximumUserMessages: Int = 2,
) {
    init {
        require(maximumUserMessages > 0) { "The user-message limit must be positive." }
    }

    fun build(
        chatHistory: List<ChatMessage>,
        appendChatStyleInstruction: Boolean = false,
        isCalendarMode: Boolean = false,
    ): List<ChatMessage> {
        val userIndices = chatHistory.indices
            .filter { chatHistory[it].role == MessageRole.USER }
            .takeLast(if (isCalendarMode) 1 else maximumUserMessages)
            .toSet()
        val assistantIndex = if (isCalendarMode) -1 else {
            chatHistory.indexOfLast { it.role == MessageRole.ASSISTANT }
        }

        return chatHistory.mapIndexedNotNull { index, message ->
            if (index !in userIndices && index != assistantIndex) return@mapIndexedNotNull null
            if (appendChatStyleInstruction && message.role == MessageRole.USER) {
                message.copy(content = message.content + CHAT_STYLE_INSTRUCTION_SUFFIX)
            } else {
                message
            }
        }
    }

    private companion object {
        const val CHAT_STYLE_INSTRUCTION_SUFFIX = "\n\nотвечай очень вежливо используй эмодзи"
    }
}
