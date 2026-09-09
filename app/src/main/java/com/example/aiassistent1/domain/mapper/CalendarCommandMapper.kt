package com.example.aiassistent1.domain.mapper

import com.example.aiassistent1.calendar.core.domain.*
import com.example.aiassistent1.domain.model.*
import java.time.LocalDateTime
import java.time.ZoneId

class CalendarCommandMapper(private val zoneId: ZoneId = ZoneId.systemDefault()) {
    fun map(params: AssistantParams): Result<CalendarCommand> = runCatching {
        when (params) {
            is CalendarAddParams -> {
                require(params.startsAt == null || (params.date == null && params.time == null))
                val start = params.startsAt?.let(CalendarTime::dateTime)
                CalendarCommand.Add(params.title, start?.toLocalDate() ?: CalendarTime.date(requireNotNull(params.date)),
                    start?.toLocalTime() ?: params.time?.let(CalendarTime::time), params.durationMin, params.value)
            }
            is CalendarSearchParams -> CalendarCommand.Search(params.query, range(params.rangeStart, params.rangeEnd))
            is CalendarSumParams -> CalendarCommand.Sum(params.query, range(params.rangeStart, params.rangeEnd))
            is CalendarUpdateParams -> {
                require(!params.target.useLastReferenced) { "use_last_referenced не входит в V12.4" }
                val t = params.target
                val c = params.changes
                require(c.value == null || !c.clearValue) { "value несовместим с clear_value" }
                CalendarCommand.Update(target(t.query, t.rangeStart, t.rangeEnd, t.useLastCreated, false),
                    CalendarEventChanges(c.title, c.date?.let(CalendarTime::date), c.time?.let(CalendarTime::time),
                        c.durationMin, when {
                            c.clearValue -> CalendarValueChange.Clear
                            c.value != null -> CalendarValueChange.Set(c.value)
                            else -> CalendarValueChange.Keep
                        }))
            }
            is CalendarDeleteParams -> params.target.let { t ->
                CalendarCommand.Delete(target(t.query, t.rangeStart, t.rangeEnd, t.useLastCreated, t.useLastInRange))
            }
        }
    }

    fun range(start: String?, end: String?): CalendarRange? {
        require((start == null) == (end == null)) { "Укажите обе границы периода" }
        return start?.let { CalendarRange(epoch(it), epoch(end!!)) }
    }
    private fun epoch(value: String) = CalendarTime.toEpochMillis(CalendarTime.dateTime(value), zoneId)

    private fun target(query: String?, start: String?, end: String?, last: Boolean, inRange: Boolean): CalendarUpdateTarget? {
        require(query == null || query.isNotBlank()) { "Название события не может быть пустым" }
        val selectorCount = listOf(query != null, last, inRange).count { it }
        require(selectorCount <= 1) { "Конфликт способов выбора события" }
        val period = range(start, end)
        require(period == null || query != null || inRange) { "Период требует название или выбор последнего события периода" }
        require(!inRange || period != null) { "Укажите период" }
        if (selectorCount == 0) return null
        return CalendarUpdateTarget(when {
            query != null -> CalendarTargetMode.BY_QUERY
            last -> CalendarTargetMode.LAST_CREATED
            else -> CalendarTargetMode.LAST_IN_RANGE
        }, query, period?.start, period?.end)
    }
}
