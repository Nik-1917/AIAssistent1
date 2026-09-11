package com.example.aiassistent1.domain.formatter

import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarNotesFormatterTest {
    @Test fun `absent notes leave existing reply unchanged`() {
        for (notes in listOf(null, "", " \n ")) assertEquals("Ответ.", "Ответ.".withCalendarNotes(notes))
    }

    @Test fun `shows the exact text without interpreting numbers or markup`() {
        val notes = "  Текст\n12500 **рублей**  "
        assertEquals("Ответ.\nПримечание: $notes", "Ответ.".withCalendarNotes(notes))
    }
}
