package com.example.aiassistent1.presentation.viewmodel

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class CalendarEventField(val label: String) {
    Title("Название события"),
    Date("Дата (ГГГГ-ММ-ДД)"),
    Time("Время (ЧЧ:ММ)"),
    DurationMinutes("Длительность в минутах"),
    Value("Ценность события"),
}

data class CalendarEventDraftUiState(
    val title: String? = null,
    val date: String? = null,
    val time: String? = null,
    val durationMinutes: Int? = null,
    val value: Long? = null,
    val requestId: String = "",
    val activeField: CalendarEventField? = null,
    val input: String = "",
    val error: String? = null,
    val isFormatting: Boolean = false,
    val isVoiceInputActive: Boolean = false,
) {
    val startsAt: String?
        get() = if (date.isNullOrBlank() || time.isNullOrBlank()) null else "$date" + "T" + "$time"

    val isComplete: Boolean
        get() = !title.isNullOrBlank() && !date.isNullOrBlank() && !time.isNullOrBlank() &&
            durationMinutes != null && value != null
}

val CalendarEventField.modelName: String
    get() = when (this) {
        CalendarEventField.Title -> "title"
        CalendarEventField.Date -> "date"
        CalendarEventField.Time -> "time"
        CalendarEventField.DurationMinutes -> "duration_min"
        CalendarEventField.Value -> "value"
    }

val CalendarEventField.expectedFormat: String
    get() = when (this) {
        CalendarEventField.Title -> "non_empty_text"
        CalendarEventField.Date -> "YYYY-MM-DD"
        CalendarEventField.Time -> "HH:MM"
        CalendarEventField.DurationMinutes -> "positive_integer_minutes"
        CalendarEventField.Value -> "integer"
    }

fun CalendarEventDraftUiState.withNextField(): CalendarEventDraftUiState {
    val next = when {
        title.isNullOrBlank() -> CalendarEventField.Title
        date.isNullOrBlank() -> CalendarEventField.Date
        time.isNullOrBlank() -> CalendarEventField.Time
        durationMinutes == null -> CalendarEventField.DurationMinutes
        value == null -> CalendarEventField.Value
        else -> null
    }
    return copy(activeField = next, input = "", error = null, isFormatting = false, isVoiceInputActive = false)
}

/**
 * Resolves an omitted calendar-add date from the local time supplied to the
 * assistant. An exact time later today stays today; the current minute and
 * any earlier time belong to tomorrow. Without an exact time, retain today
 * and let the draft ask only for the time.
 */
internal fun resolveImplicitCalendarAddDate(
    now: LocalDateTime,
    knownTime: LocalTime?,
): LocalDate = when {
    knownTime == null -> now.toLocalDate()
    knownTime > now.toLocalTime() -> now.toLocalDate()
    else -> now.toLocalDate().plusDays(1)
}
