package com.example.aiassistent1.presentation.formatting

import com.example.aiassistent1.calendar.core.domain.*
import com.example.aiassistent1.domain.model.CalendarScheduleInput
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun CalendarScheduleInput.description(): String = buildList {
    if (mode == CalendarTimeMode.FLOATING) add("Всегда по местному времени")
    add(when (repeat) {
        CalendarRepeat.NONE -> "Один раз"
        CalendarRepeat.DATES -> "Выбранные даты: $datesText"
        CalendarRepeat.DAILY -> "Каждый день"
        CalendarRepeat.WEEKLY -> weekdays.sortedBy { it.value }.joinToString(", ") {
            listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")[it.value - 1]
        }.ifEmpty { "Выберите дни недели" }
        CalendarRepeat.DAYS -> "Интервал: $intervalText дн."
        CalendarRepeat.WEEKS -> "Интервал: $intervalText нед."
        CalendarRepeat.MONTHS -> "Интервал: $intervalText мес."
    })
    if (hasUntil && repeat != CalendarRepeat.NONE) add("до $untilText")
}.joinToString(" · ")

/** An unbounded search returns a series definition, so its anchor must not look like a found occurrence. */
fun formatCalendarSearchEvent(event: CalendarEvent, zone: ZoneId): String {
    val isDefinition = event.schedule?.isRepeating == true && event.occurrenceDate == null && event.parentSeriesId == null
    val start = event.localStart(zone).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    val minutes = if (event.schedule?.mode == CalendarTimeMode.FLOATING) Duration.between(event.localStart(zone), event.localEnd(zone)).toMinutes()
        else (event.endsAtEpochMillis - event.startsAtEpochMillis) / 60_000L
    val duration = if (event.hasEndTime) ", $minutes мин" else ""
    val result = if (isDefinition) "- Серия: ${event.title} (начало расписания: $start$duration)"
        else "- ${event.title} ($start$duration)"
    return result + event.schedule?.let { " · ${CalendarScheduleInput.from(it).description()}" }.orEmpty()
}
