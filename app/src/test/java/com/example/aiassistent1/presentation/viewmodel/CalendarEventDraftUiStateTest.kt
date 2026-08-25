package com.example.aiassistent1.presentation.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
