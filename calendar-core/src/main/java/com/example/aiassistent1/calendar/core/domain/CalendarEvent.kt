package com.example.aiassistent1.calendar.core.domain

/** A locally stored calendar event. All timestamps are UTC epoch milliseconds. */
data class CalendarEvent(
    val id: String,
    val title: String,
    val startsAtEpochMillis: Long,
    val endsAtEpochMillis: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val value: Long? = null,
    val revision: Long = 0,
    val notes: String? = null,
)

data class CalendarEventDraft(
    val title: String,
    val startsAtEpochMillis: Long,
    val endsAtEpochMillis: Long,
    val value: Long? = null,
    val notes: String? = null,
)

data class CalendarEventUpdate(
    val id: String,
    val title: String,
    val startsAtEpochMillis: Long,
    val endsAtEpochMillis: Long,
    val valueChange: CalendarValueChange = CalendarValueChange.Keep,
    val expectedRevision: Long? = null,
    /** Null or blank preserves the stored notes. */
    val notes: String? = null,
)

sealed interface CalendarValueChange {
    data object Keep : CalendarValueChange
    data class Set(val value: Long) : CalendarValueChange
    data object Clear : CalendarValueChange
}

fun CalendarValueChange.applyTo(current: Long?): Long? = when (this) {
    CalendarValueChange.Keep -> current
    is CalendarValueChange.Set -> value
    CalendarValueChange.Clear -> null
}
