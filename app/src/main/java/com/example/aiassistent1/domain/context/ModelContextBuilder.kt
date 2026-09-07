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
    ): List<ChatMessage> =
        chatHistory
            .asSequence()
            .filter { it.role == MessageRole.USER }
            .toList()
            .takeLast(maximumUserMessages)
            .let { messages ->
                if (!appendChatStyleInstruction) {
                    messages
                } else {
                    messages.map { message ->
                        message.copy(content = message.content + CHAT_STYLE_INSTRUCTION_SUFFIX)
                    }
                }
            }

    private companion object {
        const val CHAT_STYLE_INSTRUCTION_SUFFIX = "\n\nотвечай очень вежливо используй эмодзи"
    }
}
