package com.example.aiassistent1.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [NoteEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class NoteDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
}
