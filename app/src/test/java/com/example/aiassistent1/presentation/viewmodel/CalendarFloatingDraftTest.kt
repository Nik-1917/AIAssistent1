package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.calendar.core.domain.*
import com.example.aiassistent1.domain.model.CalendarScheduleInput
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class CalendarFloatingDraftTest {
    @Test fun floatingDraftDoesNotRequireZoneConfirmationAndUsesNominalDuration() {
        val draft = CalendarEventDraftUiState(title = "Привычка", date = "2026-03-29", time = "01:30", endsAt = "2026-03-29T03:30",
            timeZoneId = "Europe/Berlin", scheduleInput = CalendarScheduleInput(mode = CalendarTimeMode.FLOATING)).withNextField()
        assertEquals(120, draft.durationMinutes)
        assertFalse(draft.needsTimeZoneConfirmation(ZoneId.of("Asia/Tokyo")))
        assertEquals("03:30", draft.endDisplayText())
        assertEquals(60, draft.copy(scheduleInput = CalendarScheduleInput(), durationMinutes = null).withNextField().durationMinutes)
    }

    @Test fun chosenDatesAndWeekdaysAreValidatedAtSave() {
        val start = LocalDateTime.of(2026, 10, 20, 9, 0)
        val input = CalendarScheduleInput(CalendarTimeMode.FLOATING, CalendarRepeat.DATES, datesText = "2026-10-21, 2026-10-21; 2026-10-23")
        assertEquals(2, input.build(start, null, ZoneOffset.UTC)!!.recurrence.dates.size)
        assertThrows(IllegalArgumentException::class.java) { input.copy(datesText = "2026-02-30").build(start, null, ZoneOffset.UTC) }
        assertThrows(IllegalArgumentException::class.java) { input.copy(repeat = CalendarRepeat.WEEKLY).build(start, null, ZoneOffset.UTC) }
        assertThrows(IllegalArgumentException::class.java) { input.copy(repeat = CalendarRepeat.DAYS, intervalText = "").build(start, null, ZoneOffset.UTC) }
        assertNull(CalendarScheduleInput().build(start, null, ZoneOffset.UTC))
    }
}
