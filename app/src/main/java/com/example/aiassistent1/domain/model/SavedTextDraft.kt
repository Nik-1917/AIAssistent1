package com.example.aiassistent1.domain.model

data class SavedTextDraft(
    val id: String,
    val text: String,
    val createdAtEpochMillis: Long,
)
