package com.example.aiassistent1.calendar.core.domain

/** UTC timestamps. Equal start/end represents an event whose end was not specified. */
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
) {
    val hasEndTime: Boolean get() = endsAtEpochMillis > startsAtEpochMillis
}

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
    /** An explicit editor action; omitted notes alone must never erase stored text. */
    val clearNotes: Boolean = false,
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
