package com.example.aiassistent1.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationParamsTest {
    @Test
    fun `defaults reserve half the context for the response`() {
        assertEquals(512, GenerationParams().contextSize)
        assertEquals(256, GenerationParams().maxTokens)
    }

    @Test
    fun `calendar and chat have exact separate defaults`() {
        assertEquals(GenerationParams(contextSize = 512, maxTokens = 256, temperature = 0.36f,
            topP = 0.90f, topK = 20, repeatPenalty = 1.15f), ModelProfile.CALENDAR.defaults)
        assertEquals(GenerationParams(contextSize = 2048, maxTokens = 512, temperature = 0.70f,
            topP = 0.80f, topK = 20, repeatPenalty = 1.15f, batchSize = 64,
            contextResponseRatio = ContextResponseRatio.FOUR_TO_ONE), ModelProfile.CHAT.defaults)
    }

    @Test
    fun `both sliders reach every shared position including the defaults`() {
        val initial = GenerationParams(temperature = 0.7f, topP = 0.8f, topK = 12, gpuLayers = 3)
        for (context in 512..8192 step 512) {
            val expected = initial.copy(contextSize = context, maxTokens = context / 2)
            assertEquals(expected, initial.withContextSize(context))
            assertEquals(expected, initial.withMaxTokens(context / 2))
        }
    }

    @Test
    fun `old mismatched pairs and off-grid values normalize using context`() {
        assertEquals(GenerationParams(contextSize = 512, maxTokens = 256),
            GenerationParams(contextSize = 512, maxTokens = 512).normalizedForSettings())
        assertEquals(GenerationParams(contextSize = 2048, maxTokens = 1024),
            GenerationParams(contextSize = 1952, maxTokens = 2048).normalizedForSettings())
        assertEquals(GenerationParams(contextSize = 1024, maxTokens = 512),
            GenerationParams(contextSize = 992, maxTokens = 64).normalizedForSettings())
    }

    @Test
    fun `out of range input clamps before multiplication without overflow`() {
        val defaults = GenerationParams()
        assertEquals(defaults.copy(contextSize = 512, maxTokens = 256), defaults.withContextSize(Int.MIN_VALUE))
        assertEquals(defaults.copy(contextSize = 8192, maxTokens = 4096), defaults.withContextSize(Int.MAX_VALUE))
        assertEquals(defaults.copy(contextSize = 512, maxTokens = 256), defaults.withMaxTokens(Int.MIN_VALUE))
        assertEquals(defaults.copy(contextSize = 8192, maxTokens = 4096), defaults.withMaxTokens(Int.MAX_VALUE))
    }
}
