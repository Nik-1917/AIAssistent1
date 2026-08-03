package com.example.aiassistent1.domain.model

data class VoiceInputEvent(
    val sessionId: Long,
    val transcript: String,
)

data class VoiceInputError(
    val sessionId: Long,
    val cause: Throwable,
)