package com.example.aiassistent1.domain.model

import org.junit.Assert.*
import org.junit.Test

class ModelContextLimitsTest {
    @Test fun `model ceiling replaces fallback and both sliders keep the exact ratio`() {
        for (limit in listOf(256, 512, 1024, 4096, 32768, 131072, 3000, Int.MAX_VALUE)) {
            val params = GenerationParams(trainedContextLength = limit)
            for (value in listOf(Int.MIN_VALUE, 512, 2048, 8192, 32768, Int.MAX_VALUE)) {
                for (bounded in listOf(params.withContextSize(value), params.withMaxTokens(value))) {
                    assertTrue(bounded.contextSize <= limit)
                    assertEquals(bounded.contextSize, bounded.maxTokens * 2)
                    assertTrue(bounded.contextSize >= params.contextLimits.minimum)
                }
            }
        }
        assertEquals(32768, GenerationParams(trainedContextLength = 32768).withContextSize(32768).contextSize)
        assertEquals(8192, GenerationParams().withContextSize(32768).contextSize)
    }

    @Test fun `rounding never crosses an off-grid ceiling and valid existing settings stay unchanged`() {
        val params = ModelProfile.CHAT.defaults.withContextResponseRatio(com.example.aiassistent1.domain.model.ContextResponseRatio.TWO_TO_ONE).copy(trainedContextLength = 3000)
        assertEquals(2560, params.withContextSize(3000).contextSize)
        assertEquals(params, params.normalizedForSettings())
        assertEquals(512, ModelProfile.CALENDAR.defaults.copy(trainedContextLength = 32768).normalizedForSettings().contextSize)
        assertEquals(1024, params.copy(trainedContextLength = 1024).normalizedForSettings().contextSize)
    }

    @Test fun `very large ceilings avoid allocating millions of Compose tick marks`() {
        assertEquals(14, ModelContextLimits().sliderSteps)
        assertEquals(0, ModelContextLimits(Int.MAX_VALUE).sliderSteps)
        assertFalse(ModelContextLimits(512).adjustable)
        assertEquals(256, ModelContextLimits(256).minimum)
    }

    @Test fun `runtime bounds actual file independently and preserves smaller summary budget`() {
        val summary = ModelProfile.CHAT.defaults.withContextResponseRatio(com.example.aiassistent1.domain.model.ContextResponseRatio.TWO_TO_ONE).copy(contextSize = 4096, maxTokens = 512, trainedContextLength = 131072)
        val bounded = summary.boundedForRuntime(1024)
        assertEquals(1024, bounded.contextSize)
        assertEquals(512, bounded.maxTokens)
        assertEquals(1024, bounded.trainedContextLength)
        assertEquals(512, summary.boundedForRuntime(32768).maxTokens)
        assertEquals(4096, summary.boundedForRuntime(32768).contextSize)
        assertEquals(8192, summary.copy(contextSize = 32768).boundedForRuntime(null).contextSize)
    }
}
