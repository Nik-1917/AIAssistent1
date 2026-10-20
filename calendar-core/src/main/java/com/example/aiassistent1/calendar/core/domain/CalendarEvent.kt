package com.example.aiassistent1.calendar.core.domain

/** Concrete event or projected occurrence. Floating wall time is authoritative in schedule. */
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
    val schedule: CalendarSchedule? = null,
    val occurrenceDate: java.time.LocalDate? = null,
    val parentSeriesId: String? = null,
) {
    val hasEndTime: Boolean get() = schedule?.let { it.end != null } ?: (endsAtEpochMillis > startsAtEpochMillis)
    val seriesId: String? get() = parentSeriesId ?: occurrenceDate?.let { CalendarSchedule.parseOccurrenceId(id)?.first }
    fun localStart(zone: java.time.ZoneId): java.time.LocalDateTime =
        if (schedule?.mode == CalendarTimeMode.FLOATING) schedule.localStart(occurrenceDate ?: schedule.start.toLocalDate())
        else java.time.Instant.ofEpochMilli(startsAtEpochMillis).atZone(zone).toLocalDateTime()
    fun localEnd(zone: java.time.ZoneId): java.time.LocalDateTime =
        if (schedule?.mode == CalendarTimeMode.FLOATING) schedule.localEnd(occurrenceDate ?: schedule.start.toLocalDate()) ?: localStart(zone)
        else java.time.Instant.ofEpochMilli(endsAtEpochMillis).atZone(zone).toLocalDateTime()
}

data class CalendarEventDraft(
    val title: String,
    val startsAtEpochMillis: Long,
    val endsAtEpochMillis: Long,
    val value: Long? = null,
    val notes: String? = null,
    val schedule: CalendarSchedule? = null,
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
    /** Preserve by default; editors explicitly replace/clear the schedule. */
    val schedule: CalendarSchedule? = null,
    val replaceSchedule: Boolean = false,
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
