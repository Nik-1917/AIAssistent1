package com.example.aiassistent1.domain.context

import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.MessageRole
import com.example.aiassistent1.domain.model.ChatContextHistory

class ModelContextBuilder(
    private val maximumUserMessages: Int = Int.MAX_VALUE,
) {
    init {
        require(maximumUserMessages > 0) { "The user-message limit must be positive." }
    }

    fun build(
        chatHistory: List<ChatMessage>,
        appendChatStyleInstruction: Boolean = false,
        isCalendarMode: Boolean = false,
        excludedTurnIds: Set<String> = emptySet(),
    ): List<ChatMessage> {
        val selected = if (isCalendarMode) {
            listOfNotNull(chatHistory.lastOrNull { it.role == MessageRole.USER })
        } else {
            val turns = ChatContextHistory.turns(chatHistory)
            val currentRequest = turns.lastOrNull()?.id
            turns.filter { it.id == currentRequest || it.id !in excludedTurnIds }
                .takeLast(maximumUserMessages).flatMap { it.messages }
        }
        return selected.map { message ->
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
