package com.example.aiassistent1.calendar.core.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object CalendarTime {
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
