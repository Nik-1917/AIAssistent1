package com.example.aiassistent1.presentation.viewmodel

enum class CalendarEventField(val label: String) {
    Title("Название события"),
    StartsAt("Дата и время (ГГГГ-ММ-ДДTЧЧ:ММ)"),
    DurationMinutes("Длительность в минутах"),
}

data class CalendarEventDraftUiState(
    val title: String? = null,
    val startsAt: String? = null,
    val durationMinutes: Int? = null,
    val activeField: CalendarEventField? = null,
    val input: String = "",
    val error: String? = null,
    val isFormatting: Boolean = false,
    val isVoiceInputActive: Boolean = false,
) {
    val isComplete: Boolean
        get() = !title.isNullOrBlank() && !startsAt.isNullOrBlank() && durationMinutes != null
}

val CalendarEventField.modelName: String
    get() = when (this) {
        CalendarEventField.Title -> "title"
        CalendarEventField.StartsAt -> "starts_at"
        CalendarEventField.DurationMinutes -> "duration_min"
    }

val CalendarEventField.expectedFormat: String
    get() = when (this) {
        CalendarEventField.Title -> "non_empty_text"
        CalendarEventField.StartsAt -> "YYYY-MM-DDTHH:MM"
        CalendarEventField.DurationMinutes -> "positive_integer_minutes"
    }

fun CalendarEventDraftUiState.withNextField(): CalendarEventDraftUiState {
    val next = when {
        title.isNullOrBlank() -> CalendarEventField.Title
        startsAt.isNullOrBlank() -> CalendarEventField.StartsAt
        durationMinutes == null -> CalendarEventField.DurationMinutes
        else -> null
    }
    return copy(activeField = next, input = "", error = null, isFormatting = false, isVoiceInputActive = false)
}
