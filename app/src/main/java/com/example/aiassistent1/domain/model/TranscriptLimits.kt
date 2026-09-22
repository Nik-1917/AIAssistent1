package com.example.aiassistent1.domain.model

object TranscriptLimits {
    const val PER_CONFERENCE = 5L * 1024 * 1024
    const val GLOBAL = 100L * 1024 * 1024
    const val MAX_LINE_BYTES = 16 * 1024
    const val MAX_DURATION_MS = 3L * 60 * 60 * 1000
    fun bytes(text: String): Long = text.toByteArray(Charsets.UTF_8).size.toLong()
}
