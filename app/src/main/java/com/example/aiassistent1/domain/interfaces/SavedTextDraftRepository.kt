package com.example.aiassistent1.domain.interfaces

import com.example.aiassistent1.domain.model.SavedTextDraft

interface SavedTextDraftRepository {
    suspend fun loadTextDrafts(): List<SavedTextDraft>

    /** Saves a separate draft and clears the matching working text in one durable operation. */
    suspend fun archiveVoiceDraft(draft: SavedTextDraft): List<SavedTextDraft>

    suspend fun deleteTextDraft(id: String): List<SavedTextDraft>
}
