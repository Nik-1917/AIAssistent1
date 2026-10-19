package com.example.aiassistent1.domain.interfaces

import com.example.aiassistent1.domain.model.ChatMessage
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun observeMessages(chatId: String): Flow<List<ChatMessage>>
    suspend fun saveMessage(message: ChatMessage)
    suspend fun deleteMessage(id: String)
    /** Deletes only the assistant's calendar reply; user messages and other chats are protected. */
    suspend fun deleteCalendarDraftReply(id: String)
    suspend fun deleteAllMessages(chatId: String)
}
