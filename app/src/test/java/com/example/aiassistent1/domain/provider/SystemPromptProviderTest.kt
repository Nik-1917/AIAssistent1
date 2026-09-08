package com.example.aiassistent1.domain.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptProviderTest {
    @Test
    fun `contains only current temporal context and JSON instruction`() {
        val prompt = SystemPromptProvider().getSystemPrompt()

        assertTrue(prompt.startsWith("cегодня "))
        assertTrue(prompt.contains(" день недели "))
        assertTrue(prompt.endsWith(" ответ JSON"))
        assertTrue(
            Regex(
                """cегодня \d{4}-\d{2}-\d{2} \d{2}:\d{2} день недели \p{IsCyrillic}+ ответ JSON""",
            ).matches(prompt),
        )
    }
}
