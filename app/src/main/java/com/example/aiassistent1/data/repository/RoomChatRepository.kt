package com.example.aiassistent1.data.repository

import com.example.aiassistent1.data.local.ChatMessageDao
import com.example.aiassistent1.data.local.ChatMessageEntity
import com.example.aiassistent1.domain.interfaces.ChatRepository
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.MessageRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomChatRepository(
    private val chatMessageDao: ChatMessageDao,
) : ChatRepository {
    override fun observeMessages(): Flow<List<ChatMessage>> =
        chatMessageDao.observeAll().map { messages -> messages.map(ChatMessageEntity::toDomain) }

    override suspend fun saveMessage(message: ChatMessage) {
        chatMessageDao.upsert(message.toEntity())
    }
}

private fun ChatMessageEntity.toDomain() = ChatMessage(
    id = id,
    role = MessageRole.valueOf(role),
    content = content,
    createdAtEpochMillis = createdAtEpochMillis,
    isInterrupted = isInterrupted,
)

private fun ChatMessage.toEntity() = ChatMessageEntity(
    id = id,
    role = role.name,
    content = content,
    createdAtEpochMillis = createdAtEpochMillis,
    isInterrupted = isInterrupted,
)