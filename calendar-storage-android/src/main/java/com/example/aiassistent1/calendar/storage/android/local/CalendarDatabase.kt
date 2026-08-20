package com.example.aiassistent1.calendar.storage.android.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [CalendarEventEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class CalendarDatabase : RoomDatabase() {
    abstract fun calendarEventDao(): CalendarEventDao
}
