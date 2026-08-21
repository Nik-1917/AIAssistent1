package com.example.aiassistent1.domain.provider

import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptProviderTest {
    @Test
    fun `describes relative ranges through the fourth day`() {
        val prompt = SystemPromptProvider().getSystemPrompt()

        assertTrue(prompt.contains("«послезавтра» — с 00:00 второго дня после текущего до 00:00 третьего дня"))
        assertTrue(prompt.contains("«послепослезавтра» — с 00:00 третьего дня после текущего до 00:00 четвёртого дня"))
    }
}
