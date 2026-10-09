package com.example.aiassistent1.domain.mapper

import com.example.aiassistent1.calendar.core.domain.*
import com.example.aiassistent1.domain.model.*
import java.time.ZoneId

class CalendarCommandMapper(private val zoneId: ZoneId = ZoneId.systemDefault()) {
    fun map(params: AssistantParams): Result<CalendarCommand> = runCatching {
        when (params) {
            is CalendarAddParams -> {
                val start = params.startsAt?.let(CalendarTime::dateTime)
                val date = params.date?.let(CalendarTime::date)
                val time = params.time?.let(CalendarTime::time)
                require(start == null || date == null || date == start.toLocalDate()) {
                    "date не совпадает с датой starts_at"
                }
                require(start == null || time == null || time == start.toLocalTime()) {
                    "time не совпадает со временем starts_at"
                }
                CalendarCommand.Add(params.title, start?.toLocalDate() ?: requireNotNull(date),
                    start?.toLocalTime() ?: time, params.durationMin, params.value, params.notes,
                    endsAt = params.endsAt?.let(CalendarTime::dateTime))
            }
            is CalendarSearchParams -> CalendarCommand.Search(params.query, range(params.rangeStart, params.rangeEnd))
            is CalendarSumParams -> CalendarCommand.Sum(params.query, range(params.rangeStart, params.rangeEnd))
        }
    }

    fun range(start: String?, end: String?): CalendarRange? {
        require((start == null) == (end == null)) { "Укажите обе границы периода" }
        return start?.let { CalendarRange(epoch(it), epoch(end!!)) }
    }
    private fun epoch(value: String) = CalendarTime.toEpochMillis(CalendarTime.dateTime(value), zoneId)


}
