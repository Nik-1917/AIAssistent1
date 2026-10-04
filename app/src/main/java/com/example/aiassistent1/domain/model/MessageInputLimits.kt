package com.example.aiassistent1.domain.model

/** A UI/storage bound, independent of the exact token budget selected by the engine. */
object MessageInputLimits {
    const val CHAT_CHARACTERS = 100_000
    const val CALENDAR_CHARACTERS = 3_000
    fun forMode(calendar: Boolean): Int = if (calendar) CALENDAR_CHARACTERS else CHAT_CHARACTERS
}
