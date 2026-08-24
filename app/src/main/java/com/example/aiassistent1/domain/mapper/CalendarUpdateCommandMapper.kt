package com.example.aiassistent1.domain.mapper

import com.example.aiassistent1.calendar.core.domain.CalendarEventChanges
import com.example.aiassistent1.calendar.core.domain.CalendarTargetMode
import com.example.aiassistent1.calendar.core.domain.CalendarUpdateCommand
import com.example.aiassistent1.calendar.core.domain.CalendarUpdateTarget
import com.example.aiassistent1.domain.model.CalendarUpdateParams
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Converts the model's JSON transport object into the calendar-core command.
 * Unknown JSON fields remain absent and are never replaced with defaults here.
 */
class CalendarUpdateCommandMapper(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun map(params: CalendarUpdateParams): Result<CalendarUpdateCommand> = runCatching {
        val targetParams = params.target
        val query = targetParams.query?.trim()?.takeIf(String::isNotEmpty)
        val hasRangeStart = targetParams.rangeStart != null
        val hasRangeEnd = targetParams.rangeEnd != null
        require(hasRangeStart == hasRangeEnd) {
            "Укажите обе границы исходного периода события."
        }
        require(!hasRangeStart || query != null) {
            "Исходный период можно указать только вместе с названием события."
        }

        val rangeStart = targetParams.rangeStart?.let {
            parseDateTime(it, "Начало исходного периода")
        }
        val rangeEnd = targetParams.rangeEnd?.let {
            parseDateTime(it, "Конец исходного периода")
        }
        if (rangeStart != null && rangeEnd != null) {
            require(rangeStart < rangeEnd) {
                "Начало исходного периода должно быть раньше его конца."
            }
        }

        val target = CalendarUpdateTarget(
            mode = when {
                query != null -> CalendarTargetMode.BY_QUERY
                targetParams.useLastReferenced -> CalendarTargetMode.LAST_REFERENCED
                else -> CalendarTargetMode.LAST_CREATED
            },
            query = query,
            rangeStartEpochMillis = rangeStart,
            rangeEndEpochMillis = rangeEnd,
        )
        val changes = CalendarEventChanges(
            title = params.changes.title?.trim()?.takeIf(String::isNotEmpty),
            date = params.changes.date?.let { parseDate(it) },
            time = params.changes.time?.let { parseTime(it) },
            durationMinutes = params.changes.durationMin,
        )
        CalendarUpdateCommand(target, changes)
    }

    private fun parseDateTime(value: String, label: String): Long {
        require(DATE_TIME_PATTERN.matches(value)) {
            "$label должен быть в формате ГГГГ-ММ-ДДTЧЧ:ММ."
        }
        return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
    }

    private fun parseDate(value: String): LocalDate {
        require(DATE_PATTERN.matches(value)) {
            "Новая дата должна быть в формате ГГГГ-ММ-ДД."
        }
        return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
    }

    private fun parseTime(value: String): LocalTime {
        require(TIME_PATTERN.matches(value)) {
            "Новое время должно быть в формате ЧЧ:ММ."
        }
        return LocalTime.parse(value, TIME_FORMATTER)
    }

    private companion object {
        val DATE_TIME_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}")
        val DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")
        val TIME_PATTERN = Regex("\\d{2}:\\d{2}")
        val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
