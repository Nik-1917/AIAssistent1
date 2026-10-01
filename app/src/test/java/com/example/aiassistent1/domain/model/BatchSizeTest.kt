package com.example.aiassistent1.domain.model

import org.junit.Assert.*
import org.junit.Test

class BatchSizeTest {
    @Test
    fun `auto uses the input budget with a 512 ceiling and preserves temporary response limits`() {
        assertTrue(ModelProfile.CALENDAR.defaults.batchSizeAuto)
        assertTrue(ModelProfile.CHAT.defaults.batchSizeAuto)
        assertEquals(256, ModelProfile.CALENDAR.defaults.effectiveBatchSize)
        assertEquals(512, ModelProfile.CHAT.defaults.effectiveBatchSize)
        assertEquals(512, GenerationParams().withContextSize(1024).effectiveBatchSize)
        assertEquals(512, GenerationParams().withContextSize(8192).effectiveBatchSize)
        val summary = GenerationParams(contextSize = 4096, maxTokens = 512)
        assertEquals(512, summary.effectiveBatchSize)
        assertEquals(512, summary.maxTokens)
    }

    @Test
    fun `manual positions are restricted by context and survive an auto round trip`() {
        val calendar = ModelProfile.CALENDAR.defaults
        assertEquals(listOf(64, 128, 256, 512), calendar.batchSizeOptions)
        assertEquals(listOf(64, 128, 256, 512, 1024), ModelProfile.CHAT.defaults.batchSizeOptions)
        for (size in listOf(64, 128, 256, 512, 1024)) {
            assertEquals(size, ModelProfile.CHAT.defaults.copy(batchSizeAuto = false, batchSize = size).effectiveBatchSize)
        }
        val manual = calendar.copy(batchSizeAuto = false, batchSize = 128)
        assertEquals(128, manual.effectiveBatchSize)
        val auto = manual.copy(batchSizeAuto = true)
        assertEquals(256, auto.effectiveBatchSize)
        assertEquals(128, auto.copy(batchSizeAuto = false).effectiveBatchSize)
        val reduced = ModelProfile.CHAT.defaults.copy(batchSizeAuto = false, batchSize = 1024)
            .withContextSize(512).normalizedForSettings()
        assertEquals(512, reduced.batchSize)
        assertEquals(512, reduced.effectiveBatchSize)
    }

    @Test
    fun `invalid saved batch and top k values normalize without overflow`() {
        val low = GenerationParams(batchSizeAuto = false, batchSize = Int.MIN_VALUE, topK = Int.MIN_VALUE).normalizedForSettings()
        assertEquals(64, low.batchSize)
        assertEquals(1, low.topK)
        val high = GenerationParams(batchSizeAuto = false, batchSize = Int.MAX_VALUE, topK = Int.MAX_VALUE).normalizedForSettings()
        assertEquals(512, high.batchSize)
        assertEquals(100, high.topK)
        assertEquals(256, GenerationParams(batchSize = 200).normalizedForSettings().batchSize)
        assertEquals(1, GenerationParams(contextSize = 0, maxTokens = Int.MAX_VALUE).effectiveBatchSize)
        assertEquals(512, GenerationParams(contextSize = Int.MAX_VALUE, maxTokens = Int.MIN_VALUE).effectiveBatchSize)
    }
}
