package com.example.aiassistent1.domain.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptProviderTest {
    @Test fun `separate creation clock preserves calendar header and the existing contract`() {
        val provider = SystemPromptProvider()
        val target = java.time.LocalDateTime.parse("2028-01-01T01:30")
        val input = java.time.LocalDateTime.parse("2027-12-31T14:30")
        val prompt = provider.getSystemPrompt(true, target, input)
        assertTrue(prompt.startsWith(provider.getSystemPrompt(true, target).lineSequence().first() + "\n"))
        assertTrue(prompt.contains("Для calendar_add сейчас 2027-12-31 14:30"))
        assertTrue(prompt.contains("Для calendar_search и calendar_sum используй дату и время первой строки."))
        assertTrue(prompt.endsWith(SystemPromptProvider.CALENDAR_CONTRACT))
        assertFalse(prompt.contains("America/"))
        assertFalse(prompt.contains("Europe/"))
        assertFalse(prompt.contains("time_zone"))
        assertFalse(prompt.contains("UTC"))
        assertEquals(provider.getSystemPrompt(false, target), provider.getSystemPrompt(false, target, input))
        assertEquals(provider.getSystemPrompt(true, target), provider.getSystemPrompt(true, target, target))
    }

    @Test fun `supplied local time is exact and never includes timezone metadata`() {
        val local = java.time.LocalDateTime.of(2026, 10, 20, 15, 0)
        for (calendar in listOf(true, false)) {
            val prompt = SystemPromptProvider().getSystemPrompt(calendar, local)
            assertTrue(prompt.startsWith("cегодня 2026-10-20 15:00 день недели вторник"))
            assertFalse(prompt.contains("Europe/"))
            assertFalse(prompt.contains("UTC"))
            assertFalse(prompt.contains("+04:00"))
            assertFalse(prompt.contains("time_zone"))
        }
    }

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
        assertTrue(Regex("""cегодня \d{4}-\d{2}-\d{2} \d{2}:\d{2} день недели \p{IsCyrillic}+""").containsMatchIn(prompt))
        assertFalse(prompt.contains("Верни один JSON"))
        assertFalse(prompt.contains("calendar_"))
    }

    @Test
    fun `calendar contract advertises supported fields types and current reply rules`() {
        val prompt = SystemPromptProvider().getSystemPrompt(isCalendarMode = true)
        assertTrue(prompt.contains("Верни один JSON: {\"intent\":\"команда\",\"reply\":\"ответ пользователю\",\"params\":{}}. Без Markdown."))
        val actions = prompt.lineSequence().filter { it.startsWith("chat:") || it.startsWith("calendar_") }.toList()
        assertEquals(listOf(
            "chat: {}",
            "calendar_add: {title,starts_at,ends_at,date,time,duration_min,value,notes}",
            "calendar_search: {query,range_start,range_end}",
            "calendar_sum: {query,range_start,range_end}",
        ), actions)
        assertTrue(prompt.contains("неизвестные значения пропускай, не пиши null"))
        assertTrue(prompt.contains("title,notes,query,reply — строки"))
        assertTrue(prompt.contains("duration_min — целое положительное число минут, value — целое число"))
        assertTrue(prompt.contains("reply — непустой краткий ответ, согласованный с params"))
        assertTrue(prompt.contains("Не утверждай, что событие уже сохранено"))
    }
}
