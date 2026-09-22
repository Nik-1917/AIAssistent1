package com.example.aiassistent1.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transcript_lines",
    foreignKeys = [
        ForeignKey(
            entity = ConferenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["conferenceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["conferenceId", "timestamp", "id"])]
)
data class TranscriptLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conferenceId: Long,
    val text: String,
    val timestamp: Long, // milliseconds from start
    val speaker: String? = null
)
