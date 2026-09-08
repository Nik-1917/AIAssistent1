package com.example.aiassistent1.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages WHERE chatId = :chatId ORDER BY createdAtEpochMillis ASC")
    fun observeAll(chatId: String): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE chatId = :chatId")
    suspend fun deleteAll(chatId: String)

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteById(id: String)
}