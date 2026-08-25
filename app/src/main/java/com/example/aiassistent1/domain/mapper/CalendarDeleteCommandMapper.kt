package com.example.aiassistent1.domain.mapper

import com.example.aiassistent1.calendar.core.domain.CalendarDeleteCommand
import com.example.aiassistent1.calendar.core.domain.CalendarTargetMode
import com.example.aiassistent1.calendar.core.domain.CalendarUpdateTarget
import com.example.aiassistent1.domain.model.CalendarDeleteParams
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Converts a model delete command into one explicitly identified local target. */
class CalendarDeleteCommandMapper(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun map(params: CalendarDeleteParams): Result<CalendarDeleteCommand> = runCatching {
        val targetParams = params.target
        val query = targetParams.query?.trim()?.takeIf(String::isNotEmpty)
        val hasRangeStart = targetParams.rangeStart != null
        val hasRangeEnd = targetParams.rangeEnd != null
        val selectorCount = listOf(
            query != null,
            targetParams.useLastCreated,
            targetParams.useLastInRange,
        ).count { it }
        require(hasRangeStart == hasRangeEnd) {
            "Укажите обе границы периода удаляемого события."
        }
        require(!hasRangeStart || query != null || targetParams.useLastInRange) {
            "Период удаления можно указать только вместе с названием события или выбором последнего события периода."
        }
        require(selectorCount == 1) {
            "Укажите, какое событие удалить."
        }
        require(!targetParams.useLastInRange || hasRangeStart) {
            "Для удаления последнего события периода укажите этот период."
        }

        val rangeStart = targetParams.rangeStart?.let {
            parseDateTime(it, "Начало периода удаления")
        }
        val rangeEnd = targetParams.rangeEnd?.let {
            parseDateTime(it, "Конец периода удаления")
        }
        if (rangeStart != null && rangeEnd != null) {
            require(rangeStart < rangeEnd) {
                "Начало периода удаления должно быть раньше его конца."
            }
        }

        CalendarDeleteCommand(
            target = CalendarUpdateTarget(
                mode = when {
                    query != null -> CalendarTargetMode.BY_QUERY
                    targetParams.useLastCreated -> CalendarTargetMode.LAST_CREATED
                    else -> CalendarTargetMode.LAST_IN_RANGE
                },
                query = query,
                rangeStartEpochMillis = rangeStart,
                rangeEndEpochMillis = rangeEnd,
            ),
        )
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

    private companion object {
        val DATE_TIME_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}")
    }
}
