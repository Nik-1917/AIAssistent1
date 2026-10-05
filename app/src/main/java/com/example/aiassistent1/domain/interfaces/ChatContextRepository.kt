package com.example.aiassistent1.domain.interfaces

import com.example.aiassistent1.domain.model.ChatContextSettings
import com.example.aiassistent1.domain.model.ChatHistoryPolicy
import kotlinx.coroutines.flow.Flow

/** Stores context selections only; it cannot delete or rewrite chat messages. */
interface ChatContextRepository {
    fun observe(chatId: String): Flow<ChatContextSettings>
    suspend fun update(chatId: String, excludedTurnIds: Set<String>? = null, policy: ChatHistoryPolicy? = null)
}
