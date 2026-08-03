package com.example.aiassistent1.domain.interfaces

interface VoiceDraftRepository {
    suspend fun loadDraft(): String

    suspend fun saveDraft(text: String)

    suspend fun clearDraft()
}