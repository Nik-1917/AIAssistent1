package com.example.aiassistent1.presentation.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class CalendarEventDraftUiStateTest {
    @Test
    fun `retains a known date and requests only the time`() {
        val draft = CalendarEventDraftUiState(
            title = "Проверка отчёта",
            date = "2030-12-07",
            durationMinutes = 120,
        ).withNextField()

        assertEquals(CalendarEventField.Time, draft.activeField)
        assertFalse(draft.isComplete)
    }

    @Test
    fun `combines known date and time into an event start`() {
        val draft = CalendarEventDraftUiState(
            title = "Проверка отчёта",
            date = "2030-12-07",
            time = "15:00",
            durationMinutes = 120,
        ).withNextField()

        assertEquals("2030-12-07T15:00", draft.startsAt)
        assertTrue(draft.isComplete)
        assertEquals(null, draft.activeField)
    }

    @Test
    fun `uses today when an omitted date has no exact time`() {
        val date = resolveImplicitCalendarAddDate(
            now = LocalDateTime.of(2030, 10, 10, 14, 30),
            knownTime = null,
        )

        assertEquals(LocalDate.of(2030, 10, 10), date)
    }

    @Test
    fun `uses today when an omitted date has a later time`() {
        val date = resolveImplicitCalendarAddDate(
            now = LocalDateTime.of(2030, 10, 10, 14, 30),
            knownTime = LocalTime.of(18, 30),
        )

        assertEquals(LocalDate.of(2030, 10, 10), date)
    }

    @Test
    fun `uses tomorrow when an omitted date has an earlier time`() {
        val date = resolveImplicitCalendarAddDate(
            now = LocalDateTime.of(2030, 10, 10, 14, 30),
            knownTime = LocalTime.of(9, 0),
        )

        assertEquals(LocalDate.of(2030, 10, 11), date)
    }

    @Test
    fun `uses tomorrow when an omitted date equals the current minute`() {
        val date = resolveImplicitCalendarAddDate(
            now = LocalDateTime.of(2030, 10, 10, 14, 30),
            knownTime = LocalTime.of(14, 30),
        )

        assertEquals(LocalDate.of(2030, 10, 11), date)
    }
}
