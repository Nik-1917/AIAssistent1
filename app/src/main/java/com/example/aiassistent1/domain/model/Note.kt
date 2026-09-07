package com.example.aiassistent1.domain.model

import java.util.UUID

data class Note(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val createdAtEpochMillis: Long,
    val localDateIso: String,
    val localTime: String,
    val dayOfWeek: String,
    val timeZoneId: String,
)
