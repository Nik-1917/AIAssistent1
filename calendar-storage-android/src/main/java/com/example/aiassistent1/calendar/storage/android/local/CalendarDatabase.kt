package com.example.aiassistent1.calendar.storage.android.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CalendarEventEntity::class, CalendarReceiptEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class CalendarDatabase : RoomDatabase() {
    abstract fun calendarEventDao(): CalendarEventDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE calendar_events ADD COLUMN notes TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE calendar_command_receipts ADD COLUMN notes TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE calendar_events ADD COLUMN value INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE calendar_events ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS calendar_command_receipts (requestId TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, eventId TEXT NOT NULL, title TEXT NOT NULL)")
            }
        }
    }
}
