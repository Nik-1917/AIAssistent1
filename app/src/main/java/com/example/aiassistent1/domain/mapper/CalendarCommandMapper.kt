package com.example.aiassistent1.domain.mapper

import com.example.aiassistent1.calendar.core.domain.*
import com.example.aiassistent1.domain.model.*
import java.time.ZoneId

class CalendarCommandMapper(private val zoneId: ZoneId? = null) {
    fun map(params: AssistantParams, timeZone: ZoneId = zoneId ?: ZoneId.systemDefault()): Result<CalendarCommand> = runCatching {
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
            is CalendarSearchParams -> CalendarCommand.Search(params.query, range(params.rangeStart, params.rangeEnd, timeZone))
            is CalendarSumParams -> CalendarCommand.Sum(params.query, range(params.rangeStart, params.rangeEnd, timeZone))
        }
    }

    fun range(start: String?, end: String?, timeZone: ZoneId = zoneId ?: ZoneId.systemDefault()): CalendarRange? {
        require((start == null) == (end == null)) { "Укажите обе границы периода" }
        return start?.let { CalendarRange(epoch(it, timeZone), epoch(end!!, timeZone)) }
    }
    private fun epoch(value: String, timeZone: ZoneId) = CalendarTime.toEpochMillis(CalendarTime.dateTime(value), timeZone)


}
