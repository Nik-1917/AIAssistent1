package com.example.aiassistent1.domain.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptProviderTest {
    @Test
    fun `contains only current temporal context and JSON instruction`() {
        val prompt = SystemPromptProvider().getSystemPrompt()

        assertTrue(prompt.startsWith("Сегодня дата и время:"))
        assertTrue(prompt.contains("День недели сегодня:"))
        assertTrue(prompt.endsWith(" ответ JSON"))
        assertFalse(prompt.contains("послепослезавтра"))
        assertTrue(
            Regex(
                """Сегодня дата и время: \d{4}-\d{2}-\d{2} \d{2}:\d{2}\. День недели сегодня: \p{IsCyrillic}+\. Часовой пояс: \S+\. При вопросе о том, какой сегодня день недели, используй это значение\. ответ JSON""",
            ).matches(prompt),
        )
    }
}
