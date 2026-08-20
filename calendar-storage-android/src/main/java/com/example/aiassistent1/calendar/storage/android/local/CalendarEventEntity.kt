package com.example.aiassistent1.calendar.storage.android.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "calendar_events",
    indices = [
        Index(value = ["startsAtEpochMillis"]),
        Index(value = ["title"]),
    ],
)
data class CalendarEventEntity(
    @PrimaryKey val id: String,
    val title: String,
    val startsAtEpochMillis: Long,
    val endsAtEpochMillis: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
