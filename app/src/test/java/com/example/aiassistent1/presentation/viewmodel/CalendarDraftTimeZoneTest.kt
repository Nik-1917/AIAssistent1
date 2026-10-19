package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.calendar.core.domain.CalendarTime
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class CalendarDraftTimeZoneTest {
    private val samara = ZoneId.of("Europe/Samara")
    private val moscow = ZoneId.of("Europe/Moscow")
    private fun draft() = CalendarEventDraftUiState(title = "Встреча", date = "2026-10-20", time = "15:00",
        timeZoneId = samara.id, timeZoneConfirmedFor = samara.id, value = 0, notes = "  Документы\n12500  ")

    @Test fun `timezone change requires a choice and preserving moment keeps the original UTC`() {
        val original = draft()
        assertFalse(original.needsTimeZoneConfirmation(samara))
        assertTrue(original.needsTimeZoneConfirmation(moscow))
        val confirmed = original.confirmTimeZone(moscow, keepMoment = true)
        assertFalse(confirmed.needsTimeZoneConfirmation(moscow))
        assertEquals(original.time, confirmed.time)
        assertEquals(samara.id, confirmed.timeZoneId)
        assertEquals(epoch(original), epoch(confirmed))
        assertEquals(original.notes, confirmed.notes)
        assertEquals(0L, confirmed.value)
    }

    @Test fun `explicit local-time choice changes only the interpretation and calculates new UTC`() {
        val original = draft()
        val local = original.confirmTimeZone(moscow, keepMoment = false)
        assertEquals("15:00", local.time)
        assertEquals(moscow.id, local.timeZoneId)
        assertEquals(3_600_000L, epoch(local) - epoch(original))
        assertFalse(local.needsTimeZoneConfirmation(moscow))
    }

    @Test fun `legacy draft has unknown zone and cannot claim to preserve an unknown instant`() {
        val legacy = draft().copy(timeZoneId = null, timeZoneConfirmedFor = null)
        assertTrue(legacy.needsTimeZoneConfirmation(moscow))
        assertTrue(runCatching { legacy.confirmTimeZone(moscow, keepMoment = true) }.isFailure)
        assertEquals(moscow.id, legacy.confirmTimeZone(moscow, keepMoment = false).timeZoneId)
    }

    @Test fun `an interrupted save keeps its immutable original zone for an idempotent retry`() {
        val pending = draft().copy(saveRequested = true)
        assertFalse(pending.needsTimeZoneConfirmation(moscow))
        assertTrue(runCatching { pending.confirmTimeZone(moscow, keepMoment = false) }.isFailure)
    }

    @Test fun `choosing a new zone recomputes elapsed duration across DST with explicit endpoints`() {
        val original = draft().copy(date = "2026-03-29", time = "01:30", endsAt = "2026-03-29T03:30", durationMinutes = 120)
        val changed = original.confirmTimeZone(ZoneId.of("Europe/Berlin"), keepMoment = false).resolveInputs()
        assertEquals(60, changed.durationMinutes)
        assertEquals("2026-03-29T03:30", changed.endsAt)
        assertEquals(original.notes, changed.notes)
    }

    @Test fun `another later selection asks again without modifying fields`() {
        val kept = draft().confirmTimeZone(moscow, keepMoment = true)
        assertTrue(kept.needsTimeZoneConfirmation(ZoneId.of("Asia/Yekaterinburg")))
        assertEquals("15:00", kept.time)
    }

    private fun epoch(draft: CalendarEventDraftUiState) = CalendarTime.toEpochMillis(CalendarTime.dateTime(draft.startsAt!!), draft.zoneId)
}
