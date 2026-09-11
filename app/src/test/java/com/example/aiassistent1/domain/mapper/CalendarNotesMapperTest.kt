package com.example.aiassistent1.domain.mapper

import com.example.aiassistent1.calendar.core.domain.*
import com.example.aiassistent1.domain.model.*
import com.example.aiassistent1.presentation.viewmodel.CalendarUpdateDraftUiState
import org.junit.Assert.*
import org.junit.Test

class CalendarNotesMapperTest {
    @Test fun `add maps notes without changing value`() {
        val command = CalendarCommandMapper().map(CalendarAddParams("Встреча", "2026-09-11T14:30", 20, value = 0, notes = "  Текст  ")).getOrThrow() as CalendarCommand.Add
        assertEquals("  Текст  ", command.notes)
        assertEquals(0L, command.value)
    }

    @Test fun `update facade passes notes and preview preserves existing when omitted`() {
        val event = CalendarEvent("id", "Встреча", 0, 60000, 0, 0, notes = "Старое")
        val target = CalendarUpdateTarget(CalendarTargetMode.LAST_CREATED)
        for (notes in listOf(null, "", "  Новое\n  ")) {
            val changes = CalendarUpdateCommandMapper().map(CalendarUpdateParams(
                target = CalendarUpdateTargetParams(useLastCreated = true),
                changes = CalendarUpdateChangesParams(time = "15:00", notes = notes),
            )).getOrThrow().changes
            assertEquals(notes, changes.notes)
            val draft = CalendarUpdateDraftUiState(event, changes, event.title, "2026-09-11T15:00", 1, target, "request")
            assertEquals(notes?.takeIf { it.isNotBlank() } ?: "Старое", draft.previewNotes)
            assertTrue(draft.isReadyForConfirmation)
        }
    }
}
