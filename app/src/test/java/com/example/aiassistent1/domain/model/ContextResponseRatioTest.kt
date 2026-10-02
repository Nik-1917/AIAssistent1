package com.example.aiassistent1.domain.model

import org.junit.Assert.*
import org.junit.Test

class ContextResponseRatioTest {
    @Test fun `switching ratio keeps context and changes reservation in both directions`() {
        val half = ModelProfile.CHAT.defaults.withContextSize(4096)
        val quarter = half.withContextResponseRatio(ContextResponseRatio.FOUR_TO_ONE)
        assertEquals(4096, quarter.contextSize)
        assertEquals(1024, quarter.maxTokens)
        assertEquals(half, quarter.withContextResponseRatio(ContextResponseRatio.TWO_TO_ONE))
        assertEquals(half.contextLimits.maximum, quarter.contextLimits.maximum)
    }

    @Test fun `both sliders preserve chosen ratio at every position and cannot overflow`() {
        for (ratio in ContextResponseRatio.entries) {
            val initial = GenerationParams(trainedContextLength = 32768, contextResponseRatio = ratio)
            for (context in 512..32768 step 512) {
                val fromContext = initial.withContextSize(context)
                val fromResponse = initial.withMaxTokens(context / ratio.divisor)
                assertEquals(context, fromContext.contextSize)
                assertEquals(context, fromContext.maxTokens * ratio.divisor)
                assertEquals(fromContext, fromResponse)
            }
            assertEquals(32768, initial.withMaxTokens(Int.MAX_VALUE).contextSize)
            assertEquals(512, initial.withMaxTokens(Int.MIN_VALUE).contextSize)
        }
    }

    @Test fun `native clamp respects ratio and preserves shorter conference answer`() {
        val params = GenerationParams(contextSize = 16384, maxTokens = 4096,
            contextResponseRatio = ContextResponseRatio.FOUR_TO_ONE)
        val memory = DeviceContextLimit(4096, MemoryLimitStatus.ESTIMATED)
        val bounded = params.boundedForRuntime(32768, memory)
        assertEquals(4096, bounded.contextSize)
        assertEquals(1024, bounded.maxTokens)
        assertEquals(128, params.copy(maxTokens = 128).boundedForRuntime(32768, memory).maxTokens)
    }

    @Test fun `tiny and blocked windows never manufacture response room beyond model limit`() {
        val tiny = GenerationParams(trainedContextLength = 6, contextResponseRatio = ContextResponseRatio.FOUR_TO_ONE)
            .normalizedForSettings()
        assertEquals(4, tiny.contextSize)
        assertEquals(1, tiny.maxTokens)
        val impossible = tiny.copy(trainedContextLength = 2).normalizedForSettings()
        assertEquals(0, impossible.contextSize)
        assertThrows(IllegalStateException::class.java) { tiny.boundedForRuntime(2) }
        assertEquals(ContextResponseRatio.TWO_TO_ONE, ContextResponseRatio.fromStored(-1))
        assertEquals(ContextResponseRatio.TWO_TO_ONE, ContextResponseRatio.fromStored(3))
    }
}
