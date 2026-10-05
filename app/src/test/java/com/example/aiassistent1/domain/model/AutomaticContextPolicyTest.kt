package com.example.aiassistent1.domain.model

import org.junit.Assert.*
import org.junit.Test

class AutomaticContextPolicyTest {
    @Test fun `chat and summary require 1024 while calendar keeps its existing thresholds`() {
        for (task in listOf(GenerationTask.CHAT, GenerationTask.SUMMARY)) {
            assertEquals(1024, AutomaticContextPolicy.minimumAnswer(task))
            assertEquals(1008, AutomaticContextPolicy.promptBudget(2048, task))
            assertEquals(1536, AutomaticContextPolicy.minimumContext(100, task))
        }
        assertEquals(64, AutomaticContextPolicy.minimumAnswer(GenerationTask.CALENDAR))
        assertEquals(512, AutomaticContextPolicy.minimumContext(100, GenerationTask.CALENDAR))
        assertEquals(512, AutomaticContextPolicy.desiredContext(100, GenerationTask.CALENDAR))
        assertEquals(368, AutomaticContextPolicy.promptBudget(512, GenerationTask.CALENDAR))
        assertEquals(2048, AutomaticContextPolicy.desiredContext(1008, GenerationTask.SUMMARY))
        assertEquals(2560, AutomaticContextPolicy.desiredContext(1009, GenerationTask.SUMMARY))
        assertEquals(0, AutomaticContextPolicy.promptBudget(512, GenerationTask.SUMMARY))
    }
    @Test fun `512 is an initial reserve and a 1200 token answer fits after 100 input tokens`() {
        assertEquals(2048, AutomaticContextPolicy.desiredContext(100, GenerationTask.CHAT))
        assertEquals(512, AutomaticContextPolicy.answerReserve(100))
        assertEquals(1932, AutomaticContextPolicy.availableAnswer(2048, 100))
        assertTrue(AutomaticContextPolicy.availableAnswer(2048, 100) > 1200)
    }
    @Test fun `full input drives 512 step allocation and exact overflow boundaries`() {
        for ((input, context) in listOf(0 to 2048, 512 to 2048, 513 to 4096,
            1024 to 4096, 1025 to 6144, 1537 to 8192)) {
            assertEquals(context, AutomaticContextPolicy.desiredContext(input, GenerationTask.CHAT))
        }
        assertEquals(512, AutomaticContextPolicy.desiredContext(240, GenerationTask.CALENDAR))
        assertEquals(1024, AutomaticContextPolicy.desiredContext(241, GenerationTask.CALENDAR))
        assertEquals(368, AutomaticContextPolicy.promptBudget(512))
        assertEquals(0, AutomaticContextPolicy.availableAnswer(512, 600))
    }
    @Test(expected = ArithmeticException::class) fun `overflow cannot produce a small context`() {
        AutomaticContextPolicy.desiredContext(Int.MAX_VALUE, GenerationTask.CHAT)
    }
    @Test fun `transient unavailable RAM does not zero saved settings`() {
        val params = ModelProfile.CHAT.defaults.copy(deviceContextLimit = DeviceContextLimit(0, MemoryLimitStatus.LOW_MEMORY))
            .normalizedForAutomaticSettings()
        assertEquals(2048, params.contextSize)
        assertEquals(512, params.maxTokens)
    }
}
