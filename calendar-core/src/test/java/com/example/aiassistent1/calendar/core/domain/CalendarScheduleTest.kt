package com.example.aiassistent1.calendar.core.domain

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class CalendarScheduleTest {
    private val start = LocalDateTime.of(2026, 10, 20, 9, 0)
    private fun event(schedule: CalendarSchedule) = CalendarEvent("habit", "Зарядка", 0, 0, 1, 1, value = 5, notes = "Текст", schedule = schedule)
    private fun occurrences(schedule: CalendarSchedule, from: LocalDate, to: LocalDate, zone: ZoneId = ZoneOffset.UTC) =
        schedule.expand(event(schedule), from.atStartOfDay(zone).toInstant().toEpochMilli(), to.atStartOfDay(zone).toInstant().toEpochMilli(), zone)

    @Test fun floatingTimeStaysAtNineAcrossZonesAndNeverMutatesSource() {
        val schedule = CalendarSchedule(CalendarTimeMode.FLOATING, start, start.plusHours(1), recurrence = CalendarRecurrence(CalendarRepeat.DAILY))
        val timestamps = listOf("Europe/Samara", "Europe/Moscow", "Asia/Tokyo").map { id ->
            val zone = ZoneId.of(id)
            val occurrence = occurrences(schedule, start.toLocalDate(), start.toLocalDate().plusDays(1), zone).single()
            assertEquals(LocalTime.of(9, 0), Instant.ofEpochMilli(occurrence.startsAtEpochMillis).atZone(zone).toLocalTime())
            assertEquals(start, occurrence.localStart(zone))
            assertEquals("habit@2026-10-20", occurrence.id)
            assertEquals("Текст", occurrence.notes)
            occurrence.startsAtEpochMillis
        }
        assertEquals(3, timestamps.distinct().size)
        assertNull(schedule.zoneId)
        assertEquals(start, schedule.start)
    }

    @Test fun fixedSeriesKeepsOriginalZoneWhenViewedElsewhere() {
        val schedule = CalendarSchedule(CalendarTimeMode.FIXED, start, zoneId = "Europe/Samara", recurrence = CalendarRecurrence(CalendarRepeat.DAILY))
        val result = occurrences(schedule, start.toLocalDate(), start.toLocalDate().plusDays(1), ZoneId.of("Europe/Moscow")).single()
        assertEquals(LocalTime.of(8, 0), result.localStart(ZoneId.of("Europe/Moscow")).toLocalTime())
    }

    @Test fun selectedDatesAreDeduplicatedAndDoNotIncludeUnselectedAnchor() {
        val dates = setOf(start.toLocalDate().plusDays(1), start.toLocalDate().plusDays(4))
        val schedule = CalendarSchedule(CalendarTimeMode.FLOATING, start, recurrence = CalendarRecurrence(CalendarRepeat.DATES, dates = dates))
        assertEquals(dates.sorted(), occurrences(schedule, start.toLocalDate(), start.toLocalDate().plusDays(8)).map { it.occurrenceDate })
    }

    @Test fun weekdaysAndInclusiveUntilAreRespected() {
        val schedule = CalendarSchedule(CalendarTimeMode.FLOATING, start, recurrence = CalendarRecurrence(CalendarRepeat.WEEKLY,
            weekdays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), until = LocalDate.of(2026, 10, 23)))
        assertEquals(listOf("2026-10-21", "2026-10-23"), occurrences(schedule, start.toLocalDate(), start.toLocalDate().plusDays(30)).map { it.occurrenceDate.toString() })
    }

    @Test fun intervalsUseCalendarDatesAndSkipMissingMonthDays() {
        for ((repeat, interval, expected) in listOf(Triple(CalendarRepeat.DAYS, 3, listOf(0L, 3L, 6L)), Triple(CalendarRepeat.WEEKS, 2, listOf(0L, 14L, 28L)))) {
            val schedule = CalendarSchedule(CalendarTimeMode.FLOATING, start, recurrence = CalendarRecurrence(repeat, interval))
            assertEquals(expected.map { start.toLocalDate().plusDays(it) }, occurrences(schedule, start.toLocalDate(), start.toLocalDate().plusDays(expected.last() + 1)).map { it.occurrenceDate })
        }
        val january = LocalDateTime.of(2026, 1, 31, 9, 0)
        val monthly = CalendarSchedule(CalendarTimeMode.FLOATING, january, recurrence = CalendarRecurrence(CalendarRepeat.MONTHS))
        assertEquals(listOf("2026-01-31", "2026-03-31", "2026-05-31"), occurrences(monthly, january.toLocalDate(), LocalDate.of(2026, 6, 1)).map { it.occurrenceDate.toString() })
    }

    @Test fun overnightOverlapAndEndExclusivePointQueries() {
        val local = LocalDateTime.of(2026, 10, 20, 23, 30)
        val schedule = CalendarSchedule(CalendarTimeMode.FLOATING, local, local.plusHours(2), recurrence = CalendarRecurrence(CalendarRepeat.DAILY))
        val result = occurrences(schedule, LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 22))
        assertEquals(2, result.size)
        val point = CalendarSchedule(CalendarTimeMode.FLOATING, start)
        val millis = start.toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(1, point.expand(event(point), millis, millis + 1, ZoneOffset.UTC).size)
        assertTrue(point.expand(event(point), millis - 1, millis, ZoneOffset.UTC).isEmpty())
    }

    @Test fun dstGapIsSkippedAndOverlapOccursOnce() {
        val berlin = ZoneId.of("Europe/Berlin")
        val gap = CalendarSchedule(CalendarTimeMode.FLOATING, LocalDateTime.of(2026, 3, 28, 2, 30), recurrence = CalendarRecurrence(CalendarRepeat.DAILY))
        assertEquals(listOf("2026-03-28", "2026-03-30"), occurrences(gap, LocalDate.of(2026, 3, 28), LocalDate.of(2026, 3, 31), berlin).map { it.occurrenceDate.toString() })
        val overlap = gap.copy(start = LocalDateTime.of(2026, 10, 24, 2, 30))
        val result = occurrences(overlap, LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 27), berlin)
        assertEquals(3, result.size)
        assertEquals(ZoneOffset.ofHours(2), Instant.ofEpochMilli(result[1].startsAtEpochMillis).atZone(berlin).offset)
    }

    @Test fun morningHabitStaysAtNineAcrossDaylightSavingChange() {
        val berlin = ZoneId.of("Europe/Berlin")
        val schedule = CalendarSchedule(CalendarTimeMode.FLOATING, LocalDateTime.of(2026, 3, 28, 9, 0), recurrence = CalendarRecurrence(CalendarRepeat.DAILY))
        val result = occurrences(schedule, LocalDate.of(2026, 3, 28), LocalDate.of(2026, 3, 31), berlin)
        assertTrue(result.all { it.localStart(berlin).toLocalTime() == LocalTime.of(9, 0) })
        assertEquals(23 * 3_600_000L, result[1].startsAtEpochMillis - result[0].startsAtEpochMillis)
    }

    @Test fun fixedSeriesKeepsElapsedDurationAcrossOffsetChange() {
        val berlin = ZoneId.of("Europe/Berlin")
        val start = LocalDateTime.of(2026, 3, 28, 1, 30)
        val schedule = CalendarSchedule(CalendarTimeMode.FIXED, start, start.plusHours(2), berlin.id, CalendarRecurrence(CalendarRepeat.DAILY))
        val result = occurrences(schedule, LocalDate.of(2026, 3, 28), LocalDate.of(2026, 3, 31), berlin)
        assertTrue(result.all { it.endsAtEpochMillis - it.startsAtEpochMillis == 2 * 3_600_000L })
    }

    @Test fun exclusionsAndCodecSurviveRoundTrip() {
        val schedule = CalendarSchedule(CalendarTimeMode.FLOATING, start, start.plusMinutes(30), recurrence = CalendarRecurrence(CalendarRepeat.WEEKLY,
            weekdays = setOf(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY), until = LocalDate.of(2026, 11, 30)), excludedDates = setOf(start.toLocalDate()))
        val restored = CalendarSchedule.decode(schedule.encode())
        assertEquals(schedule, restored)
        assertEquals(listOf(LocalDate.of(2026, 10, 21)), occurrences(restored, start.toLocalDate(), start.toLocalDate().plusDays(3)).map { it.occurrenceDate })
        assertEquals("habit" to start.toLocalDate(), CalendarSchedule.parseOccurrenceId("habit@2026-10-20"))
    }

    @Test fun invalidSchedulesFailInsteadOfChangingDates() {
        assertThrows(IllegalArgumentException::class.java) { CalendarSchedule(CalendarTimeMode.FLOATING, start, zoneId = "Europe/Samara") }
        assertThrows(IllegalArgumentException::class.java) { CalendarSchedule(CalendarTimeMode.FLOATING, start, recurrence = CalendarRecurrence(CalendarRepeat.WEEKLY)) }
        assertThrows(IllegalArgumentException::class.java) { CalendarSchedule(CalendarTimeMode.FLOATING, start,
            recurrence = CalendarRecurrence(CalendarRepeat.WEEKLY, weekdays = setOf(DayOfWeek.MONDAY), until = start.toLocalDate())) }
        assertThrows(IllegalArgumentException::class.java) { CalendarSchedule(CalendarTimeMode.FLOATING, start, recurrence = CalendarRecurrence(CalendarRepeat.DAYS, 0)) }
        assertThrows(IllegalArgumentException::class.java) { CalendarSchedule(CalendarTimeMode.FLOATING, start, recurrence = CalendarRecurrence(CalendarRepeat.DATES, dates = setOf(start.toLocalDate().minusDays(1)))) }
    }
}
