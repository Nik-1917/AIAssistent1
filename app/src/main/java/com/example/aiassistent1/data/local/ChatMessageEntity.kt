package com.example.aiassistent1.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val role: String,
    val content: String,
    val createdAtEpochMillis: Long,
    val isInterrupted: Boolean,
)