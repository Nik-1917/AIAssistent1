package com.example.aiassistent1.domain.model

import com.example.aiassistent1.calendar.core.domain.CalendarTime
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** One conversion of an entered wall time; offset is resolved for the event date, never for today. */
object CalendarInputTime {
    fun convert(value: LocalDateTime, source: ZoneId, target: ZoneId): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(CalendarTime.toEpochMillis(value, source)), target)
}
