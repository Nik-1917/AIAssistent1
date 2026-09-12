package com.example.aiassistent1.presentation.viewmodel

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarEventEndDateTest {
    private val eventDate = LocalDate.of(2030, 9, 12)

    @Test
    fun `same day ending keeps event creation date`() {
        assertEquals(
            eventDate,
            resolveCalendarEndDate(eventDate, LocalTime.of(14, 0), LocalTime.of(15, 0)),
        )
    }

    @Test
    fun `ending before start is next day`() {
        assertEquals(
            eventDate.plusDays(1),
            resolveCalendarEndDate(eventDate, LocalTime.of(23, 30), LocalTime.of(0, 20)),
        )
    }

    @Test
    fun `equal start and end is treated as next day`() {
        assertEquals(
            eventDate.plusDays(1),
            resolveCalendarEndDate(eventDate, LocalTime.of(14, 0), LocalTime.of(14, 0)),
        )
    }
}
