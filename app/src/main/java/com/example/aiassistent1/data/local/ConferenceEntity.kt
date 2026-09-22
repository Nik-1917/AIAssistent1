package com.example.aiassistent1.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conferences")
data class ConferenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val filePath: String,
    val startTime: Long,
    val duration: Long = 0,
    val summary: String? = null,
    val status: String = "recording",
    val error: String? = null,
    val transcriptEvicted: Boolean = false,
    val transcriptBytes: Long = 0,
)
