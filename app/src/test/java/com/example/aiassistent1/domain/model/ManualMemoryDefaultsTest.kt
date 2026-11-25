package com.example.aiassistent1.domain.model

import org.junit.Assert.*
import org.junit.Test

class ManualMemoryDefaultsTest {
    @Test fun `first manual entry reserves 1024 answer tokens for the selected ratio`() {
        for ((ratio, context) in listOf(
            ContextResponseRatio.TWO_TO_ONE to 2048,
            ContextResponseRatio.FOUR_TO_ONE to 4096,
        )) {
            val initial = ModelProfile.CHAT.defaults.copy(contextResponseRatio = ratio)
            val manual = initial.withAutomaticMemory(false, ModelProfile.CHAT.defaults.batchSize)
            assertEquals(context, manual.contextSize)
            assertEquals(1024, manual.maxTokens)
            assertEquals(ratio, manual.contextResponseRatio)
            assertEquals(context, manual.defaultManualContextSize)
            assertEquals(1024, manual.defaultManualAnswerTokens)
            assertEquals(64, manual.batchSize)
            assertFalse(manual.automaticMemory)
            assertTrue(manual.manualMemoryConfigured)
        }
    }

    @Test fun `each manual ratio restores its own sliders and reset only affects the selected ratio`() {
        val quarter = ModelProfile.CHAT.defaults.withAutomaticMemory(false).withContextSize(6144)
        val half = quarter.withContextResponseRatio(ContextResponseRatio.TWO_TO_ONE)
        assertEquals(2048, half.contextSize)
        assertEquals(1024, half.maxTokens)
        val editedHalf = half.withMaxTokens(2048)
        assertEquals(editedHalf, editedHalf.withContextResponseRatio(ContextResponseRatio.TWO_TO_ONE))
        val restoredQuarter = editedHalf.withContextResponseRatio(ContextResponseRatio.FOUR_TO_ONE)
        assertEquals(6144, restoredQuarter.contextSize)
        assertEquals(1536, restoredQuarter.maxTokens)
        val resetQuarter = restoredQuarter.withContextSize(restoredQuarter.defaultManualContextSize)
        val restoredHalf = resetQuarter.withAutomaticMemory(true).withAutomaticMemory(false)
            .withContextResponseRatio(ContextResponseRatio.TWO_TO_ONE)
        assertEquals(4096, restoredHalf.contextSize)
        assertEquals(2048, restoredHalf.maxTokens)
        assertEquals(resetQuarter, restoredHalf.withContextResponseRatio(ContextResponseRatio.FOUR_TO_ONE))
    }

    @Test fun `manual presets respect file limits but not temporary memory pressure`() {
        for ((ratio, expectedContext, expectedAnswer) in listOf(
            Triple(ContextResponseRatio.TWO_TO_ONE, 2048, 1024),
            Triple(ContextResponseRatio.FOUR_TO_ONE, 2560, 640),
        )) {
            val manual = ModelProfile.CHAT.defaults.copy(
                contextResponseRatio = ratio, trainedContextLength = 3000,
                deviceContextLimit = DeviceContextLimit(0, MemoryLimitStatus.LOW_MEMORY),
            ).withAutomaticMemory(false).normalizedForAutomaticSettings()
            assertEquals(expectedContext, manual.contextSize)
            assertEquals(expectedAnswer, manual.maxTokens)
            assertEquals(expectedContext, manual.defaultManualContextSize)
            assertEquals(expectedAnswer, manual.defaultManualAnswerTokens)
        }
    }
}
