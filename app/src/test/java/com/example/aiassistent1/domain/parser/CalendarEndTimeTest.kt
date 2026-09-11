package com.example.aiassistent1.domain.parser

import com.example.aiassistent1.domain.model.CalendarAddParams
import com.example.aiassistent1.domain.mapper.CalendarCommandMapper
import com.example.aiassistent1.calendar.core.domain.CalendarCommand
import com.example.aiassistent1.presentation.viewmodel.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset

class CalendarEndTimeTest {
    private val parser = AssistantResponseParser(ZoneOffset.UTC)
    private fun parse(fields: String) = parser.parseResult("""{"intent":"calendar_add","reply":"Событие","params":{$fields}}""")

    @Test fun `endpoints derive duration while retaining optional notes and required value`() {
        val params = parse(""""title":"Встреча","starts_at":"2026-09-11T14:30","ends_at":"2026-09-11T15:00","notes":"Текст"""").getOrThrow().params as CalendarAddParams
        assertEquals(30, params.durationMin)
        assertEquals("2026-09-11T15:00", params.endsAt)
        assertEquals("Текст", params.notes)
        assertNull(params.value)
        val command = CalendarCommandMapper(ZoneOffset.UTC).map(params).getOrThrow() as CalendarCommand.Add
        assertEquals(params.endsAt, command.endsAt.toString())
        val draft = CalendarEventDraftUiState(title = params.title, date = "2026-09-11", time = "14:30",
            endsAt = params.endsAt, notes = params.notes).withNextField()
        assertEquals(30, draft.durationMinutes)
        assertEquals(CalendarEventField.Value, draft.activeField)
        assertTrue(draft.copy(value = 0).withNextField().isComplete)
    }

    @Test fun `crossing midnight and matching duration are accepted`() {
        val params = parse(""""starts_at":"2026-09-11T23:40","ends_at":"2026-09-12T00:20","duration_min":40""").getOrThrow().params as CalendarAddParams
        assertEquals(40, params.durationMin)
    }

    @Test fun `date and shorthand time can also pair with a full end`() {
        val params = parse(""""date":"2026-09-11","starts_at":"14:30","ends_at":"2026-09-11T15:00"""").getOrThrow().params as CalendarAddParams
        assertEquals(30, params.durationMin)
        assertEquals("14:30", params.time)
    }

    @Test fun `incomplete start retains end until time is filled without asking duration`() {
        val params = parse(""""date":"2026-09-11","ends_at":"2026-09-11T15:00"""").getOrThrow().params as CalendarAddParams
        assertNull(params.durationMin)
        val draft = CalendarEventDraftUiState(title = "Встреча", date = params.date, endsAt = params.endsAt).withNextField()
        assertEquals(CalendarEventField.Time, draft.activeField)
        val filled = draft.copy(time = "14:30").withNextField()
        assertEquals(30, filled.durationMinutes)
        assertEquals(CalendarEventField.Value, filled.activeField)
    }

    @Test fun `rejects reversed equal conflicting malformed and excessive intervals`() {
        for (fields in listOf(
            """"starts_at":"2026-09-11T14:30","ends_at":"2026-09-11T14:30""",
            """"starts_at":"2026-09-11T14:30","ends_at":"2026-09-11T14:00""",
            """"starts_at":"2026-09-11T14:30","ends_at":"2026-09-11T15:00","duration_min":20""",
            """"starts_at":"0001-01-01T00:00","ends_at":"9999-01-01T00:00""",
            """"ends_at":"15:00""", """"ends_at":"2026-02-30T15:00""",
            """"ends_at":123""", """"ends_at":null""",
        )) assertTrue(fields, parse(fields).isFailure)
    }

    @Test fun `without end existing duration behavior is unchanged`() {
        val params = parse(""""starts_at":"2026-09-11T14:30","duration_min":20""").getOrThrow().params as CalendarAddParams
        assertEquals(20, params.durationMin)
        assertNull(params.endsAt)
        assertNull((parse(""""title":"Встреча"""").getOrThrow().params as CalendarAddParams).durationMin)
    }
}
