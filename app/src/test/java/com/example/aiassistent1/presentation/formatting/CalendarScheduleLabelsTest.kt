package com.example.aiassistent1.presentation.formatting

import com.example.aiassistent1.calendar.core.domain.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class CalendarScheduleLabelsTest {
    private val start = LocalDateTime.of(2026, 10, 20, 9, 0)

    @Test fun ordinarySearchTextRetainsItsExistingFormat() {
        val millis = start.toInstant(ZoneOffset.UTC).toEpochMilli()
        val event = CalendarEvent("one", "Встреча", millis, millis + 30 * 60_000, 0, 0)
        assertEquals("- Встреча (2026-10-20T09:00:00, 30 мин)", formatCalendarSearchEvent(event, ZoneOffset.UTC))
    }

    @Test fun unboundedSeriesSearchLabelsItsAnchorAndShowsTheRule() {
        val schedule = CalendarSchedule(CalendarTimeMode.FLOATING, start, recurrence = CalendarRecurrence(CalendarRepeat.WEEKLY,
            weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)))
        val root = CalendarEvent("series", "Зарядка", 0, 0, 0, 0, schedule = schedule)
        val result = formatCalendarSearchEvent(root, ZoneId.of("Asia/Tokyo"))
        assertTrue(result.contains("Серия: Зарядка (начало расписания: 2026-10-20T09:00:00)"))
        assertTrue(result.contains("Всегда по местному времени · Пн, Пт"))
    }

    @Test fun floatingSearchUsesNominalTimeAndDurationAcrossDst() {
        val schedule = CalendarSchedule(CalendarTimeMode.FLOATING, LocalDateTime.of(2026, 3, 29, 1, 30), LocalDateTime.of(2026, 3, 29, 3, 30))
        val root = CalendarEvent("one", "Привычка", 0, 0, 0, 0, schedule = schedule)
        val event = schedule.project(root, schedule.start.toLocalDate(), ZoneId.of("Europe/Berlin"))!!
        assertEquals(60 * 60_000L, event.endsAtEpochMillis - event.startsAtEpochMillis)
        assertTrue(formatCalendarSearchEvent(event, ZoneId.of("Europe/Berlin")).contains("2026-03-29T01:30:00, 120 мин"))
    }
}
