package com.example.aiassistent1.data.local

import androidx.room.Dao
import androidx.room.Insert

@Dao
interface NoteDao {
    @Insert
    suspend fun insert(note: NoteEntity)
}
