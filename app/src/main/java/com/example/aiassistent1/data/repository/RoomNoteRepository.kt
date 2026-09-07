package com.example.aiassistent1.data.repository

import com.example.aiassistent1.data.local.NoteDao
import com.example.aiassistent1.data.local.NoteEntity
import com.example.aiassistent1.domain.interfaces.NoteRepository
import com.example.aiassistent1.domain.model.Note

class RoomNoteRepository(
    private val noteDao: NoteDao,
) : NoteRepository {
    override suspend fun saveNote(note: Note) {
        noteDao.insert(note.toEntity())
    }
}

private fun Note.toEntity() = NoteEntity(
    id = id,
    text = text,
    createdAtEpochMillis = createdAtEpochMillis,
    localDateIso = localDateIso,
    localTime = localTime,
    dayOfWeek = dayOfWeek,
    timeZoneId = timeZoneId,
)
