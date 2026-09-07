package com.example.aiassistent1.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val text: String,
    val createdAtEpochMillis: Long,
    val localDateIso: String,
    val localTime: String,
    val dayOfWeek: String,
    val timeZoneId: String,
)
