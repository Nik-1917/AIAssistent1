package com.example.aiassistent1.domain.model

import org.junit.Assert.*
import org.junit.Test

class HybridModelMemoryCalculatorTest {
    private val mib = 1024L * 1024
    private val hybrid = HybridMemoryMetadata(4, 2048, 128, 16, 16, 4)
    private val qwen35 = ModelMemoryMetadata("qwen35", 1_274_388_480, 24, 2048, 6144,
        8, 2, 256, 256, 248320, hybrid)
    private val settings = MemoryRuntimeSettings(false, 64)
    private val memory = DeviceMemorySnapshot(8192 * mib, 6144 * mib, 256 * mib, false)
    private fun cost(model: ModelMemoryMetadata = qwen35, context: Int = 512) =
        requireNotNull(ModelMemoryCalculator.cost(model, settings, context))

    @Test fun `qwen35 has six KV layers and eighteen FP32 recurrent states with native rollback copies`() {
        val small = cost()
        assertEquals(MemoryEstimateProfile.QWEN35, small.profile)
        // Independent cache-size delta: 6 layers * 2 KV heads * (256 + 256) * FP32 * 512 tokens,
        // plus one non-flash workspace of 64 tokens * 8 heads * FP32 * 512 tokens.
        assertEquals(13 * mib, cost(context = 1024).workingBytes - small.workingBytes)
        val allAttention = cost(qwen35.copy(hybrid = hybrid.copy(uniformRecurrentLayers = false)))
        val allRecurrent = cost(qwen35.copy(hybrid = hybrid.copy(uniformRecurrentLayers = true)))
        // One recurrent layer retains (18,432 conv + 262,144 SSM) floats, in 17 native state slots.
        // Moving six more layers from attention to recurrence removes 12 MiB of KV and adds 109.17 MiB.
        assertEquals(6L * 280576 * 4 * 17 - 12 * mib, allRecurrent.workingBytes - small.workingBytes)
        assertTrue(allAttention.totalBytes > qwen35.fileBytes)
        assertTrue(ModelMemoryCalculator.calculate(qwen35, settings, memory).canLoad)
    }

    @Test fun `explicit recurrent mask overrides interval and missing interval uses native default four`() {
        val flags = List(24) { (it + 1) % 4 != 0 }
        assertEquals(cost(), cost(qwen35.copy(hybrid = hybrid.copy(fullAttentionInterval = null))))
        assertEquals(cost(), cost(qwen35.copy(hybrid = hybrid.copy(fullAttentionInterval = 2, recurrentLayers = flags))))
        // A NextN block is charged as an extra attention layer, not as a recurrent layer.
        val nextN = qwen35.copy(blockCount = 25, hybrid = hybrid.copy(nextNPredictLayers = 1))
        assertEquals(2 * mib, cost(nextN).workingBytes - cost().workingBytes)
    }

    @Test fun `hybrid dimensions masks and arithmetic failures cannot become generic fallback`() {
        for (invalid in listOf(
            qwen35.copy(hybrid = null), qwen35.copy(valueLength = 128),
            qwen35.copy(hybrid = hybrid.copy(convolutionKernel = 0)),
            qwen35.copy(hybrid = hybrid.copy(innerSize = null)),
            qwen35.copy(hybrid = hybrid.copy(stateSize = -1)),
            qwen35.copy(hybrid = hybrid.copy(timeStepRank = 8)),
            qwen35.copy(hybrid = hybrid.copy(groupCount = 3)),
            qwen35.copy(hybrid = hybrid.copy(fullAttentionInterval = 0)),
            qwen35.copy(hybrid = hybrid.copy(recurrentLayers = listOf(true))),
            qwen35.copy(hybrid = hybrid.copy(nextNPredictLayers = 24)),
            qwen35.copy(hybrid = hybrid.copy(nextNPredictLayers = 1, uniformRecurrentLayers = true)),
            qwen35.copy(hybrid = hybrid.copy(convolutionKernel = Int.MAX_VALUE, stateSize = Int.MAX_VALUE,
                innerSize = Int.MAX_VALUE, timeStepRank = 1, groupCount = 1)),
        )) {
            assertNull(invalid.toString(), ModelMemoryCalculator.cost(invalid, settings, 512))
            assertEquals(MemoryLimitStatus.METADATA_UNAVAILABLE,
                ModelMemoryCalculator.calculate(invalid, settings, memory).status)
        }
    }

    @Test fun `future architecture uses a labelled conservative bounded estimate`() {
        val known = ModelMemoryMetadata("qwen3", 128 * mib, 2, 64, 128, 4, 2, 16, 16, 1000)
        val future = known.copy(architecture = "future-text-v2")
        val estimate = cost(future)
        assertEquals(MemoryEstimateProfile.CONSERVATIVE_FALLBACK, estimate.profile)
        assertTrue(estimate.weightsBytes > cost(known).weightsBytes)
        assertTrue(estimate.workingBytes > cost(known).workingBytes)
        val limit = ModelMemoryCalculator.calculate(future, settings, memory)
        assertTrue(limit.canLoad)
        assertEquals(2048, limit.maximumContext)
        assertTrue(limit.explanation.contains("повышенным запасом"))
        assertEquals(MemoryEstimateProfile.CONSERVATIVE_FALLBACK, limit.estimateProfile)
        assertEquals(2048, GenerationParams(contextSize = 32768).boundedForRuntime(32768, limit).contextSize)
        assertFalse(ModelMemoryCalculator.calculate(future, settings, memory.copy(lowMemory = true)).canLoad)
        assertEquals(MemoryLimitStatus.INSUFFICIENT_MEMORY, ModelMemoryCalculator.calculate(
            future, settings, memory.copy(availableBytes = 2048 * mib)).status)
        assertEquals(MemoryLimitStatus.METADATA_UNAVAILABLE, ModelMemoryCalculator.calculate(
            future.copy(blockCount = null), settings, memory).status)
        assertEquals(MemoryLimitStatus.METADATA_UNAVAILABLE, ModelMemoryCalculator.calculate(
            future.copy(fileBytes = Long.MAX_VALUE), settings, memory).status)
        assertEquals(MemoryLimitStatus.MEMORY_UNAVAILABLE, ModelMemoryCalculator.calculate(future, settings, null).status)
    }

    @Test fun `unknown hybrid retains recurrence and never discounts cache like qwen35`() {
        val future = qwen35.copy(architecture = "future-hybrid")
        assertTrue(cost(future).workingBytes > cost().workingBytes)
        assertTrue(cost(future).workingBytes > cost(future.copy(hybrid = null)).workingBytes)
        // A future SSM layout need not satisfy Qwen's inner = rank * state identity.
        assertNotNull(ModelMemoryCalculator.cost(future.copy(hybrid = hybrid.copy(innerSize = 4096)), settings, 512))
    }

    @Test fun `both profiles invert the same cost and preserve exact memory boundaries`() {
        for (model in listOf(qwen35, qwen35.copy(architecture = "future-hybrid"))) {
            for (runtime in listOf(settings, settings.copy(gpuLayers = 1))) {
                val bytes = requireNotNull(ModelMemoryCalculator.cost(model, runtime, 512)).totalBytes
                assertEquals(model.fileBytes, ModelMemoryCalculator.maximumModelFileBytes(model, runtime, 512, bytes))
                assertTrue(requireNotNull(ModelMemoryCalculator.maximumModelFileBytes(model, runtime, 512, bytes - 1)) < model.fileBytes)
                val reserve = ModelMemoryCalculator.calculate(model, runtime, memory).reserveBytes
                val exactMemory = memory.copy(totalBytes = 32 * 1024 * mib, availableBytes = 30 * 1024 * mib)
                val exactReserve = ModelMemoryCalculator.calculate(model, runtime, exactMemory).reserveBytes
                assertTrue(reserve > 0)
                val exact = exactMemory.copy(availableBytes = exactReserve + bytes)
                assertEquals(512, ModelMemoryCalculator.calculate(model, runtime, exact).maximumContext)
                assertEquals(0, ModelMemoryCalculator.calculate(model, runtime, exact.copy(availableBytes = exact.availableBytes - 1)).maximumContext)
            }
        }
    }

    @Test fun `prefill growth and GPU copy cannot lower memory costs`() {
        val base = cost().totalBytes
        assertTrue(requireNotNull(ModelMemoryCalculator.cost(qwen35, MemoryRuntimeSettings(), 512)).totalBytes > base)
        assertTrue(requireNotNull(ModelMemoryCalculator.cost(qwen35, settings.copy(gpuLayers = 1), 512)).totalBytes > base)
    }
}
