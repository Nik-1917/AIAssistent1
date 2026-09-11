package com.example.aiassistent1.calendar.core.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object CalendarTime {
    /** Explicit endpoints use elapsed minutes, including offset transitions. */
    fun durationMinutes(start: LocalDateTime?, end: LocalDateTime?, supplied: Int?, zoneId: ZoneId): Int? {
        if (end == null) return supplied
        val endMillis = toEpochMillis(end, zoneId)
        if (start == null) return supplied
        val elapsed = Math.subtractExact(endMillis, toEpochMillis(start, zoneId))
        require(elapsed > 0) { "ends_at: окончание должно быть позже начала" }
        require(elapsed % 60_000L == 0L) { "ends_at: интервал должен содержать целое число минут" }
        val minutes = elapsed / 60_000L
        require(minutes <= Int.MAX_VALUE) { "ends_at: длительность вне допустимого диапазона" }
        require(supplied == null || supplied.toLong() == minutes) { "ends_at: окончание не совпадает с duration_min" }
        return minutes.toInt()
    }

    fun date(value: String): LocalDate {
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) { "Ожидается ГГГГ-ММ-ДД" }
        return LocalDate.parse(value).also { require(it.year in 1..9999) { "Год вне диапазона 0001–9999" } }
    }
    fun time(value: String): LocalTime {
        require(Regex("[0-9]{2}:[0-9]{2}").matches(value)) { "Ожидается ЧЧ:ММ" }
        return LocalTime.parse(value)
    }
    fun dateTime(value: String): LocalDateTime {
        require(value.length == 16 && value[10] == 'T') { "Ожидается ГГГГ-ММ-ДДTЧЧ:ММ" }
        return LocalDateTime.of(date(value.substring(0, 10)), time(value.substring(11)))
    }
    fun toEpochMillis(value: LocalDateTime, zoneId: ZoneId): Long {
        val offsets = zoneId.rules.getValidOffsets(value)
        require(offsets.size == 1) { "Местное время отсутствует или неоднозначно в часовом поясе $zoneId" }
        return value.toInstant(offsets.single()).toEpochMilli()
    }
}
