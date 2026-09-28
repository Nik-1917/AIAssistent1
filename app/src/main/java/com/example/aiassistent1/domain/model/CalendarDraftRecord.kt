package com.example.aiassistent1.domain.model

/** A local, unconfirmed event. It is never included in the model's chat context. */
data class CalendarDraftRecord(
    val requestId: String,
    val chatId: String,
    val createdAtEpochMillis: Long,
    val title: String? = null,
    val date: String? = null,
    val time: String? = null,
    val durationMinutes: Int? = null,
    val value: Long? = null,
    val notes: String? = null,
    val endsAt: String? = null,
    val fieldInputs: Map<String, String> = emptyMap(),
    val savedEventId: String? = null,
    // Written before committing. A retry uses the same immutable data and receipt ID.
    val saveRequested: Boolean = false,
)
