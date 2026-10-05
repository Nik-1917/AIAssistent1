package com.example.aiassistent1.domain.model

enum class ChatHistoryPolicy { ASK, AUTOMATIC }

data class ChatContextSettings(
    val excludedTurnIds: Set<String> = emptySet(),
    val policy: ChatHistoryPolicy = ChatHistoryPolicy.ASK,
)

/** A user request and every following assistant message, up to the next request. */
data class ChatContextTurn(val messages: List<ChatMessage>) {
    val id: String get() = messages.first().id
}

data class ChatContextPressure(
    val requestId: String,
    val turns: List<ChatContextTurn>,
    val reason: ContextCapacityReason,
    val id: String = java.util.UUID.randomUUID().toString(),
)

sealed interface ChatContextChoice {
    data class Automatic(val remember: Boolean = false) : ChatContextChoice
    data class Manual(val turnIds: Set<String>, val remember: Boolean = false) : ChatContextChoice
    data object Cancel : ChatContextChoice
}

object ChatContextHistory {
    fun turns(messages: List<ChatMessage>): List<ChatContextTurn> {
        val groups = mutableListOf<MutableList<ChatMessage>>()
        for (message in messages) when (message.role) {
            MessageRole.USER -> groups.add(mutableListOf(message))
            MessageRole.ASSISTANT -> groups.lastOrNull()?.add(message)
            MessageRole.SYSTEM -> Unit // The current system instruction is supplied separately.
        }
        return groups.map { ChatContextTurn(it.toList()) }
    }
}
