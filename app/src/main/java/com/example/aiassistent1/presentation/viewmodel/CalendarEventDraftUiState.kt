package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.calendar.core.domain.CalendarTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    val notes: String? = null,
    val endsAt: String? = null,
    val chatId: String = "calendar",
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val fieldInputs: Map<CalendarEventField, String> = emptyMap(),
    val savedEventId: String? = null,
    val saveRequested: Boolean = false,
    val isSaving: Boolean = false,
    val formattingField: CalendarEventField? = null,
) {
    val isEditable: Boolean get() = savedEventId == null && !saveRequested && !isSaving

    fun fieldText(field: CalendarEventField): String = fieldInputs[field] ?: when (field) {
        CalendarEventField.Title -> title
        CalendarEventField.Date -> date
        CalendarEventField.Time -> time
        CalendarEventField.DurationMinutes -> durationMinutes?.toString() ?: inferredDuration()?.toString()
        CalendarEventField.Value -> value?.toString()
    }.orEmpty()

    private fun inferredDuration(): Int? = runCatching {
        val startDate = fieldInputs[CalendarEventField.Date] ?: date
        val startTime = fieldInputs[CalendarEventField.Time] ?: time
        val start = if (startDate.isNullOrBlank() || startTime.isNullOrBlank()) null
            else CalendarTime.dateTime("${startDate.trim()}T${startTime.trim()}")
        CalendarTime.durationMinutes(start, endsAt?.let(CalendarTime::dateTime), null, ZoneId.systemDefault())
    }.getOrNull()

    val missingFields: List<CalendarEventField>
        get() = CalendarEventField.entries.filter { fieldText(it).isBlank() }

    /** Validate every edited field together; never silently save an older valid value. */
    fun resolveInputs(): CalendarEventDraftUiState {
        fun text(field: CalendarEventField) = fieldText(field).trim().ifEmpty { null }
        val dateText = text(CalendarEventField.Date)
        val timeText = text(CalendarEventField.Time)
        dateText?.let { require(runCatching { CalendarTime.date(it) }.isSuccess) { "Введите существующую дату в формате ГГГГ-ММ-ДД." } }
        timeText?.let { require(runCatching { CalendarTime.time(it) }.isSuccess) { "Введите время от 00:00 до 23:59 в формате ЧЧ:ММ." } }
        val durationText = text(CalendarEventField.DurationMinutes)
        val valueText = text(CalendarEventField.Value)
        val scheduleChanged = fieldInputs.any { (field, input) ->
            (field == CalendarEventField.DurationMinutes ||
                (startsAt != null && field in setOf(CalendarEventField.Date, CalendarEventField.Time))) &&
                input.trim() != copy(fieldInputs = emptyMap()).fieldText(field)
        }
        return copy(
            title = text(CalendarEventField.Title), date = dateText, time = timeText,
            durationMinutes = durationText?.let {
                requireNotNull(it.toIntOrNull()) { "Длительность должна быть целым числом минут." }
            },
            value = valueText?.let { requireNotNull(it.toLongOrNull()) { "Ценность должна быть целым числом." } },
            endsAt = endsAt.takeUnless { scheduleChanged }, fieldInputs = emptyMap(),
        ).withNextField()
    }

    val startsAt: String?
        get() = if (date.isNullOrBlank() || time.isNullOrBlank()) null else "$date" + "T" + "$time"

    val isComplete: Boolean
        get() = !title.isNullOrBlank() && !date.isNullOrBlank() && !time.isNullOrBlank() &&
            durationMinutes != null && value != null

    // Display-only: keep the model's explicit endpoint separate from a derived end.
    // Use elapsed minutes, matching CalendarCommandExecutor when saving the event.
    internal fun endDisplayText(zoneId: ZoneId = ZoneId.systemDefault()): String? = runCatching {
        val end = endsAt?.let(CalendarTime::dateTime) ?: run {
            val start = startsAt?.let(CalendarTime::dateTime) ?: return@runCatching null
            val duration = durationMinutes?.takeIf { it > 0 } ?: return@runCatching null
            val endMillis = Math.addExact(
                CalendarTime.toEpochMillis(start, zoneId),
                Math.multiplyExact(duration.toLong(), 60_000L),
            )
            Instant.ofEpochMilli(endMillis).atZone(zoneId).toLocalDateTime()
        }
        val endTime = end.format(DateTimeFormatter.ofPattern("HH:mm"))
        if (end.toLocalDate().toString() == date) endTime
        else "$endTime (${end.format(DateTimeFormatter.ofPattern("dd.MM.uuuu"))})"
    }.getOrNull()
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
    // Supplied invalid values must fail, never turn into missing fields or corrected dates.
    date?.let(CalendarTime::date)
    time?.let(CalendarTime::time)
    durationMinutes?.let { require(it > 0) { "Длительность должна быть больше нуля." } }
    startsAt?.let { CalendarTime.toEpochMillis(CalendarTime.dateTime(it), ZoneId.systemDefault()) }

    val resolvedDuration = if (endsAt == null) durationMinutes else com.example.aiassistent1.calendar.core.domain.CalendarTime.durationMinutes(
        startsAt?.let(com.example.aiassistent1.calendar.core.domain.CalendarTime::dateTime),
        endsAt?.let(com.example.aiassistent1.calendar.core.domain.CalendarTime::dateTime),
        durationMinutes, java.time.ZoneId.systemDefault(),
    )
    val next = when {
        title.isNullOrBlank() -> CalendarEventField.Title
        date.isNullOrBlank() -> CalendarEventField.Date
        time.isNullOrBlank() -> CalendarEventField.Time
        resolvedDuration == null -> CalendarEventField.DurationMinutes
        value == null -> CalendarEventField.Value
        else -> null
    }
    return copy(durationMinutes = resolvedDuration, activeField = next, input = "", error = null, isFormatting = false, isVoiceInputActive = false)
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
