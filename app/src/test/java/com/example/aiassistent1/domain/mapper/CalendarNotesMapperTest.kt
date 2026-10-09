package com.example.aiassistent1.domain.mapper

import com.example.aiassistent1.calendar.core.domain.*
import com.example.aiassistent1.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class CalendarNotesMapperTest {
    @Test fun `add maps notes without changing value`() {
        val command = CalendarCommandMapper().map(CalendarAddParams("Встреча", "2026-09-11T14:30", 20, value = 0, notes = "  Текст  ")).getOrThrow() as CalendarCommand.Add
        assertEquals("  Текст  ", command.notes)
        assertEquals(0L, command.value)
    }
}
