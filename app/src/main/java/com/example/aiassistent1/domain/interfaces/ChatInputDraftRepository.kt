package com.example.aiassistent1.domain.interfaces

/** The unsent text in the main input field, independent of voice and calendar drafts. */
interface ChatInputDraftRepository {
    suspend fun load(): String
    suspend fun save(text: String)
}
