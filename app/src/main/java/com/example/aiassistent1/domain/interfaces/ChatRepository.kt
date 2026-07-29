package com.example.aiassistent1.domain.interfaces

import com.example.aiassistent1.domain.model.ChatMessage
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun observeMessages(): Flow<List<ChatMessage>>
    suspend fun saveMessage(message: ChatMessage)
}