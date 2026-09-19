package com.example.aiassistent1.presentation.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class CalendarEventDraftUiStateTest {
    @Test
    fun `duration displays an end without changing the explicit endpoint or required fields`() {
        val draft = CalendarEventDraftUiState(
            title = "Встреча", date = "2026-09-18", time = "14:30", durationMinutes = 90,
        ).withNextField()

        assertEquals("16:00", draft.endDisplayText(ZoneOffset.UTC))
        assertNull(draft.endsAt)
        assertEquals(CalendarEventField.Value, draft.activeField)
        assertFalse(draft.isComplete)
        assertTrue(draft.copy(value = 0).withNextField().isComplete)
    }

    @Test
    fun `derived end includes the date when crossing midnight or several days`() {
        val draft = CalendarEventDraftUiState(date = "2026-09-18", time = "23:30", durationMinutes = 50)

        assertEquals("00:20 (19.09.2026)", draft.endDisplayText(ZoneOffset.UTC))
        assertEquals("00:20 (21.09.2026)", draft.copy(durationMinutes = 2930).endDisplayText(ZoneOffset.UTC))
    }

    @Test
    fun `derived end appears after filling the time or duration and follows changes`() {
        val draft = CalendarEventDraftUiState(title = "Встреча", date = "2026-09-18", durationMinutes = 90)
            .withNextField()
        assertNull(draft.endDisplayText(ZoneOffset.UTC))
        assertEquals(CalendarEventField.Time, draft.activeField)
        val filled = draft.copy(time = "14:30").withNextField()
        assertEquals("16:00", filled.endDisplayText(ZoneOffset.UTC))
        assertEquals("17:00", filled.copy(time = "15:30").withNextField().endDisplayText(ZoneOffset.UTC))

        val withoutDuration = filled.copy(durationMinutes = null).withNextField()
        assertNull(withoutDuration.endDisplayText(ZoneOffset.UTC))
        assertEquals(CalendarEventField.DurationMinutes, withoutDuration.activeField)
        assertEquals("15:00", withoutDuration.copy(durationMinutes = 30).withNextField().endDisplayText(ZoneOffset.UTC))
        assertNull(filled.copy(date = null).endDisplayText(ZoneOffset.UTC))
    }

    @Test
    fun `explicit end displays and derives duration after filling the start`() {
        val draft = CalendarEventDraftUiState(
            title = "Встреча", date = "2026-09-18", endsAt = "2026-09-18T16:00",
        ).withNextField()
        assertEquals("16:00", draft.endDisplayText(ZoneOffset.UTC))
        assertNull(draft.durationMinutes)
        assertEquals(CalendarEventField.Time, draft.activeField)

        val filled = draft.copy(time = "14:30").withNextField()
        assertEquals(90, filled.durationMinutes)
        assertEquals("16:00", filled.endDisplayText(ZoneOffset.UTC))
        assertEquals("2026-09-18T16:00", filled.endsAt)
        assertEquals(CalendarEventField.Value, filled.activeField)
    }

    @Test
    fun `explicit end on the next day derives duration and displays the end date`() {
        val draft = CalendarEventDraftUiState(
            title = "Встреча", date = "2026-09-18", time = "23:30", endsAt = "2026-09-19T00:20",
        ).withNextField()

        assertEquals(50, draft.durationMinutes)
        assertEquals("00:20 (19.09.2026)", draft.endDisplayText(ZoneOffset.UTC))
        assertTrue(runCatching { draft.copy(durationMinutes = 40).withNextField() }.isFailure)
    }

    @Test
    fun `derived end uses elapsed time across clock changes as persistence does`() {
        val zone = ZoneId.of("Europe/Berlin")
        val spring = CalendarEventDraftUiState(date = "2026-03-29", time = "01:30", durationMinutes = 60)
        assertEquals("03:30", spring.endDisplayText(zone))
        val autumn = CalendarEventDraftUiState(date = "2026-10-25", time = "01:30", durationMinutes = 120)
        assertEquals("02:30", autumn.endDisplayText(zone))
        assertNull(autumn.endsAt)
    }

    @Test
    fun `unresolvable derived end does not crash the dialog`() {
        val draft = CalendarEventDraftUiState(date = "2026-03-29", time = "02:30", durationMinutes = 60)
        assertNull(draft.endDisplayText(ZoneId.of("Europe/Berlin")))
        assertNull(draft.copy(time = "invalid").endDisplayText(ZoneOffset.UTC))
    }

    @Test
    fun `notes survive field completion but are never required`() {
        val incomplete = CalendarEventDraftUiState(
            title = "Встреча", date = "2026-09-11", time = "14:30",
            durationMinutes = 20, notes = "  Текст\n12500  ",
        ).withNextField()
        assertEquals(CalendarEventField.Value, incomplete.activeField)
        assertFalse(incomplete.isComplete)
        val complete = incomplete.copy(value = 0).withNextField()
        assertTrue(complete.isComplete)
        assertEquals(incomplete.notes, complete.notes)
        assertEquals(null, complete.activeField)
        assertTrue(complete.copy(notes = null).isComplete)
    }

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
            value = 0,
        ).withNextField()

        assertEquals("2030-12-07T15:00", draft.startsAt)
        assertTrue(draft.isComplete)
        assertEquals(null, draft.activeField)
    }

    @Test
    fun `requests value when the model omitted it`() {
        val draft = CalendarEventDraftUiState(
            title = "Проверка отчёта",
            date = "2030-12-07",
            time = "15:00",
            durationMinutes = 120,
        ).withNextField()

        assertEquals(CalendarEventField.Value, draft.activeField)
        assertFalse(draft.isComplete)
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
