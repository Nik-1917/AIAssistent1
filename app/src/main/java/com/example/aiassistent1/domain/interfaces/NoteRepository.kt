package com.example.aiassistent1.domain.interfaces

import com.example.aiassistent1.domain.model.Note

interface NoteRepository {
    suspend fun saveNote(note: Note)
}
