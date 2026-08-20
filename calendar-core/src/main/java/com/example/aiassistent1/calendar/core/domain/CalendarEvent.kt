package com.example.aiassistent1.calendar.core.domain

/** A locally stored calendar event. All timestamps are UTC epoch milliseconds. */
data class CalendarEvent(
    val id: String,
    val title: String,
    val startsAtEpochMillis: Long,
    val endsAtEpochMillis: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

data class CalendarEventDraft(
    val title: String,
    val startsAtEpochMillis: Long,
    val endsAtEpochMillis: Long,
)

data class CalendarEventUpdate(
    val id: String,
    val title: String,
    val startsAtEpochMillis: Long,
    val endsAtEpochMillis: Long,
)
