package com.example.aiassistent1.domain.model

import com.example.aiassistent1.calendar.core.domain.*
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

/** Raw editor values survive restart, including incomplete/invalid interval and date text. */
data class CalendarScheduleInput(
    val mode: CalendarTimeMode = CalendarTimeMode.FIXED,
    val repeat: CalendarRepeat = CalendarRepeat.NONE,
    val intervalText: String = "1",
    val weekdays: Set<DayOfWeek> = emptySet(),
    val datesText: String = "",
    val hasUntil: Boolean = false,
    val untilText: String = "",
) {
    fun singleOccurrence() = copy(repeat = CalendarRepeat.NONE, intervalText = "1", weekdays = emptySet(),
        datesText = "", hasUntil = false, untilText = "")

    fun build(start: LocalDateTime, end: LocalDateTime?, zone: ZoneId): CalendarSchedule? {
        if (mode == CalendarTimeMode.FIXED && repeat == CalendarRepeat.NONE) return null
        val dates = if (repeat == CalendarRepeat.DATES) datesText.trim().split(Regex("[,;\\s]+"))
            .filter(String::isNotEmpty).map { parseDate(it, "Проверьте выбранные даты: нужен формат ГГГГ-ММ-ДД и существующая дата.") }.toSet() else emptySet()
        val interval = if (repeat in setOf(CalendarRepeat.DAYS, CalendarRepeat.WEEKS, CalendarRepeat.MONTHS))
            requireNotNull(intervalText.trim().toIntOrNull()) { "Введите целый интервал от 1 до 999." } else 1
        val until = if (hasUntil && repeat != CalendarRepeat.NONE) parseDate(untilText.trim(), "Укажите существующую дату окончания в формате ГГГГ-ММ-ДД.") else null
        return CalendarSchedule(mode, start, end, zone.id.takeIf { mode == CalendarTimeMode.FIXED },
            CalendarRecurrence(repeat, interval, weekdays.takeIf { repeat == CalendarRepeat.WEEKLY } ?: emptySet(), dates, until))
    }

    companion object {
        fun forEvent(event: CalendarEvent?): CalendarScheduleInput = from(event?.schedule).let {
            if (event?.seriesId != null) it.singleOccurrence() else it
        }

        private fun parseDate(value: String, message: String) = try { CalendarTime.date(value) }
            catch (error: java.time.DateTimeException) { throw IllegalArgumentException(message, error) }
            catch (error: IllegalArgumentException) { throw IllegalArgumentException(message, error) }

        fun from(schedule: CalendarSchedule?): CalendarScheduleInput = schedule?.let {
            CalendarScheduleInput(it.mode, it.recurrence.repeat, it.recurrence.interval.toString(), it.recurrence.weekdays,
                it.recurrence.dates.sorted().joinToString(", "), it.recurrence.until != null, it.recurrence.until?.toString().orEmpty())
        } ?: CalendarScheduleInput()
    }
}
