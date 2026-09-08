package com.example.aiassistent1.domain.model

import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val isInterrupted: Boolean = false,
    val chatId: String = "general",
)

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
}