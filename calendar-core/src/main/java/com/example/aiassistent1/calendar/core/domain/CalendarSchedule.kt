package com.example.aiassistent1.calendar.core.domain

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class CalendarTimeMode { FIXED, FLOATING }
enum class CalendarRepeat { NONE, DATES, DAILY, WEEKLY, DAYS, WEEKS, MONTHS }

data class CalendarRecurrence(
    val repeat: CalendarRepeat = CalendarRepeat.NONE,
    val interval: Int = 1,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val dates: Set<LocalDate> = emptySet(),
    val until: LocalDate? = null,
) {
    fun validate(start: LocalDate) {
        require(interval in 1..999) { "Интервал повторения должен быть от 1 до 999." }
        require(until == null || until >= start) { "Дата окончания повторений раньше начала." }
        require(repeat != CalendarRepeat.WEEKLY || weekdays.isNotEmpty()) { "Выберите дни недели." }
        if (repeat == CalendarRepeat.WEEKLY && until != null) {
            val firstDay = (0L..6L).first { start.dayOfWeek.plus(it) in weekdays }
            require(ChronoUnit.DAYS.between(start, until) >= firstDay) { "В выбранном периоде нет дней повторения." }
        }
        require(repeat != CalendarRepeat.DATES || dates.isNotEmpty()) { "Выберите даты." }
        require(dates.all { it >= start && (until == null || it <= until) }) { "Выбранные даты должны входить в период повторений." }
    }

    fun includes(start: LocalDate, date: LocalDate): Boolean {
        if (date < start || (until != null && date > until)) return false
        val days = ChronoUnit.DAYS.between(start, date)
        return when (repeat) {
            CalendarRepeat.NONE -> date == start
            CalendarRepeat.DATES -> date in dates
            CalendarRepeat.DAILY -> true
            CalendarRepeat.WEEKLY -> date.dayOfWeek in weekdays
            CalendarRepeat.DAYS -> days % interval == 0L
            CalendarRepeat.WEEKS -> days % (interval * 7L) == 0L
            // Missing month days are skipped, never clamped (31 January -> 31 March).
            CalendarRepeat.MONTHS -> date.dayOfMonth == start.dayOfMonth &&
                ChronoUnit.MONTHS.between(start.withDayOfMonth(1), date.withDayOfMonth(1)) % interval == 0L
        }
    }
}

/** Authoritative wall time. UTC values on a projected CalendarEvent are only a range-query projection. */
data class CalendarSchedule(
    val mode: CalendarTimeMode,
    val start: LocalDateTime,
    val end: LocalDateTime? = null,
    val zoneId: String? = null,
    val recurrence: CalendarRecurrence = CalendarRecurrence(),
    val excludedDates: Set<LocalDate> = emptySet(),
) {
    val isRepeating: Boolean get() = recurrence.repeat != CalendarRepeat.NONE

    init {
        require(start.year in 1..9999 && start.second == 0 && start.nano == 0)
        require(end == null || (end > start && end.year in 1..9999 && end.second == 0 && end.nano == 0)) { "Окончание события должно быть позже начала." }
        require(mode != CalendarTimeMode.FIXED || zoneId != null) { "Не указан пояс повторяющегося события." }
        require(mode != CalendarTimeMode.FLOATING || zoneId == null) { "Местное время хранится без часового пояса." }
        zoneId?.let(ZoneId::of)
        recurrence.validate(start.toLocalDate())
    }

    fun localStart(date: LocalDate): LocalDateTime = LocalDateTime.of(date, start.toLocalTime())
    fun localEnd(date: LocalDate): LocalDateTime? = end?.let { localStart(date).plus(Duration.between(start, it)) }

    fun firstOccurrence(event: CalendarEvent, viewingZone: ZoneId): CalendarEvent? {
        val candidates = if (recurrence.repeat == CalendarRepeat.DATES) recurrence.dates.sorted().asSequence()
            else generateSequence(start.toLocalDate()) { date ->
                if ((recurrence.until != null && date >= recurrence.until) || date >= LocalDate.of(9999, 12, 31)) null else date.plusDays(1)
            }.take(366_001)
        return candidates.mapNotNull { project(event, it, viewingZone) }.firstOrNull()
    }

    fun project(event: CalendarEvent, date: LocalDate, viewingZone: ZoneId): CalendarEvent? {
        if (!recurrence.includes(start.toLocalDate(), date) || date in excludedDates) return null
        val zone = if (mode == CalendarTimeMode.FLOATING) viewingZone else ZoneId.of(zoneId!!)
        val localStart = localStart(date)
        // An invalid generated local time is omitted, as required for recurrence instances by RFC 5545.
        if (isRepeating && zone.rules.getValidOffsets(localStart).isEmpty()) return null
        val startMillis = localStart.atZone(zone).toInstant().toEpochMilli()
        val localEnd = localEnd(date)
        val endMillis = if (mode == CalendarTimeMode.FIXED && end != null) Math.addExact(startMillis,
            Duration.between(start.atZone(zone), end.atZone(zone)).toMillis())
        else localEnd?.atZone(zone)?.toInstant()?.toEpochMilli()?.let { resolved ->
            if (resolved < startMillis) Math.addExact(startMillis, Duration.between(localStart, localEnd).toMillis()) else resolved
        } ?: startMillis
        return event.copy(id = if (isRepeating) occurrenceId(event.id, date) else event.id,
            startsAtEpochMillis = startMillis, endsAtEpochMillis = endMillis,
            occurrenceDate = date.takeIf { isRepeating })
    }

    fun expand(event: CalendarEvent, rangeStart: Long, rangeEnd: Long, viewingZone: ZoneId): List<CalendarEvent> {
        val zone = if (mode == CalendarTimeMode.FLOATING) viewingZone else ZoneId.of(zoneId!!)
        val overlapDays = end?.let { ChronoUnit.DAYS.between(start.toLocalDate(), it.toLocalDate()) } ?: 0L
        val first = maxOf(start.toLocalDate(), Instant.ofEpochMilli(rangeStart).atZone(zone).toLocalDate().minusDays(overlapDays + 1))
        val last = minOf(recurrence.until ?: LocalDate.of(9999, 12, 31), Instant.ofEpochMilli(rangeEnd - 1).atZone(zone).toLocalDate().plusDays(1))
        if (last < first) return emptyList()
        // Bounded allocation; fail explicitly rather than return a silently truncated calendar/sum.
        require(ChronoUnit.DAYS.between(first, last) <= 366_000) { "Период повторений слишком большой. Укажите период не более 1000 лет." }
        val dates = when (recurrence.repeat) {
            CalendarRepeat.NONE -> sequenceOf(start.toLocalDate())
            CalendarRepeat.DATES -> recurrence.dates.asSequence().sorted()
            else -> generateSequence(first) { if (it < last) it.plusDays(1) else null }
        }
        return dates.filter { it >= first && it <= last }.mapNotNull { project(event, it, viewingZone) }
            .filter { it.startsAtEpochMillis < rangeEnd && (it.endsAtEpochMillis > rangeStart ||
                (it.endsAtEpochMillis == it.startsAtEpochMillis && it.startsAtEpochMillis >= rangeStart)) }.toList()
    }

    /** Versioned, delimiter-safe encoding: values are enums, ISO dates/times and validated ZoneIds. */
    fun encode(): String = listOf("1", mode.name, start.toString(), end?.toString().orEmpty(), zoneId.orEmpty(),
        recurrence.repeat.name, recurrence.interval.toString(), recurrence.weekdays.sortedBy { it.value }.joinToString(",") { it.value.toString() },
        recurrence.dates.sorted().joinToString(","), recurrence.until?.toString().orEmpty(), excludedDates.sorted().joinToString(",")).joinToString("|")

    companion object {
        fun decode(value: String): CalendarSchedule {
            val p = value.split('|')
            require(p.size == 11 && p[0] == "1") { "Неподдерживаемая версия расписания." }
            fun dates(text: String) = text.takeIf { it.isNotEmpty() }?.split(',')?.map(CalendarTime::date)?.toSet() ?: emptySet()
            return CalendarSchedule(CalendarTimeMode.valueOf(p[1]), CalendarTime.dateTime(p[2]), p[3].takeIf(String::isNotEmpty)?.let(CalendarTime::dateTime),
                p[4].takeIf(String::isNotEmpty), CalendarRecurrence(CalendarRepeat.valueOf(p[5]), p[6].toInt(),
                    p[7].takeIf(String::isNotEmpty)?.split(',')?.map { DayOfWeek.of(it.toInt()) }?.toSet() ?: emptySet(),
                    dates(p[8]), p[9].takeIf(String::isNotEmpty)?.let(CalendarTime::date)), dates(p[10]))
        }

        fun occurrenceId(seriesId: String, date: LocalDate): String = "$seriesId@$date"
        fun parseOccurrenceId(id: String): Pair<String, LocalDate>? {
            val separator = id.lastIndexOf('@')
            if (separator <= 0) return null
            return runCatching { id.substring(0, separator) to CalendarTime.date(id.substring(separator + 1)) }.getOrNull()
        }
    }
}
