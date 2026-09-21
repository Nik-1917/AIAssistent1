package com.example.aiassistent1.domain.parser

import com.example.aiassistent1.presentation.viewmodel.CalendarEventDraftUiState
import com.example.aiassistent1.presentation.viewmodel.withNextField
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset

class CalendarInvalidDatesTest {
    private val parser = AssistantResponseParser(ZoneOffset.UTC)
    private fun parse(intent: String, fields: String) = parser.parseResult(
        """{"intent":"$intent","reply":"Начало 2026-02-28T10:00, окончание 11:00","params":{$fields}}""",
    )

    @Test fun `invalid supplied dates cannot become creation parameters`() {
        for (date in listOf("2026-02-30", "2026-02-29", "2026-04-31", "2026-13-01", "0000-01-01")) {
            for (fields in listOf(
                """"date":"$date","time":"10:00","duration_min":60""",
                """"starts_at":"${date}T10:00","duration_min":60""",
                """"starts_at":"2026-02-28T10:00","ends_at":"${date}T11:00""",
            )) assertTrue(fields, parse("calendar_add", fields).isFailure)
        }
    }

    @Test fun `nonexistent reversed and empty intervals are rejected`() {
        for ((start, end) in listOf(
            "2026-02-30T10:00" to "2026-03-01T11:00",
            "2026-02-28T10:00" to "2026-02-30T11:00",
            "2026-03-02T10:00" to "2026-03-01T10:00",
            "2026-03-01T10:00" to "2026-03-01T10:00",
        )) {
            assertTrue(parse("calendar_add", """"starts_at":"$start","ends_at":"$end"""").isFailure)
            for (intent in listOf("calendar_search", "calendar_sum", "calendar_delete")) {
                assertTrue(intent, parse(intent, """"range_start":"$start","range_end":"$end"""").isFailure)
            }
        }
    }

    @Test fun `valid leap date and cross midnight interval preserve model reply`() {
        val response = parse("calendar_add", """"starts_at":"2024-02-29T23:30","ends_at":"2024-03-01T00:20"""").getOrThrow()
        assertEquals("Начало 2026-02-28T10:00, окончание 11:00", response.reply)
    }

    @Test fun `draft validation rejects invalid dates even without an explicit end`() {
        for (draft in listOf(
            CalendarEventDraftUiState(date = "2026-02-30"),
            CalendarEventDraftUiState(date = "2026-04-31", time = "10:00", durationMinutes = 60),
            CalendarEventDraftUiState(date = "2026-02-28", time = "24:00"),
            CalendarEventDraftUiState(durationMinutes = 0),
            CalendarEventDraftUiState(date = "2026-02-28", time = "10:00", endsAt = "2026-02-28T09:00"),
        )) assertTrue(draft.toString(), runCatching { draft.withNextField() }.isFailure)
        assertEquals("2024-02-29", CalendarEventDraftUiState(date = "2024-02-29").withNextField().date)
    }
}
