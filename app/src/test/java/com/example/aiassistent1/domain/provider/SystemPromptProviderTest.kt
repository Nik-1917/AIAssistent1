package com.example.aiassistent1.domain.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptProviderTest {
    @Test
    fun `calendar prompt appends contract after current temporal context`() {
        val prompt = SystemPromptProvider().getSystemPrompt(isCalendarMode = true)

        assertTrue(prompt.startsWith("cегодня "))
        assertTrue(prompt.contains(" день недели "))
        assertEquals(SystemPromptProvider.CALENDAR_CONTRACT, prompt.substringAfter('\n'))
        assertTrue(
            Regex(
                """cегодня \d{4}-\d{2}-\d{2} \d{2}:\d{2} день недели \p{IsCyrillic}+ ответ JSON""",
            ).matches(prompt.lineSequence().first()),
        )
    }

    @Test
    fun `returns chat prompt when calendar mode is disabled`() {
        val prompt = SystemPromptProvider().getSystemPrompt(isCalendarMode = false)

        assertTrue(prompt.startsWith("cегодня "))
        assertTrue(prompt.contains(" день недели "))
        assertFalse(prompt.endsWith(" ответ JSON"))
        assertTrue(Regex("""cегодня \d{4}-\d{2}-\d{2} \d{2}:\d{2} день недели \p{IsCyrillic}+""").matches(prompt))
        assertFalse(prompt.contains("calendar_"))
    }

    @Test
    fun `creation reply contract preserves the title and separates schedule params`() {
        val prompt = SystemPromptProvider().getSystemPrompt(isCalendarMode = true)
        assertTrue(prompt.contains("Новое событие: НАЗВАНИЕ."))
        assertTrue(prompt.contains("Создаём событие без названия."))
        assertTrue(prompt.contains("Не добавляй дату, время, длительность"))
        assertTrue(prompt.contains("Слова внутри самого title сохраняй буквально"))
        assertTrue(prompt.contains("обработай только первое"))
        assertTrue(prompt.contains("Только для calendar_add: если запрошено несколько независимых событий"))
        assertTrue(prompt.contains("не обработаны"))
    }
}
