package com.example.aiassistent1.calendar.storage.android.local

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

/** Independent of event lifetime: deleting an event must not erase deduplication evidence. */
@Entity(tableName = "calendar_command_receipts")
data class CalendarReceiptEntity(
    @PrimaryKey val requestId: String,
    val kind: String,
    val eventId: String,
    val title: String,
    @ColumnInfo(defaultValue = "NULL") val notes: String? = null,
)
