package com.example.aiassistent1.domain.model

import org.junit.Assert.*
import org.junit.Test

class ModelMemoryCalculatorTest {
    private val mib = 1024L * 1024
    private val model = ModelMemoryMetadata("qwen3", 128 * mib, 2, 64, 128, 4, 2, 16, 16, 1000)
    private val settings = MemoryRuntimeSettings()
    private val memory = DeviceMemorySnapshot(8192 * mib, 6144 * mib, 256 * mib, false)

    @Test fun `full context cost accounts for weights KV prefill and logits exactly once`() {
        val cost = requireNotNull(ModelMemoryCalculator.cost(model, settings, 512))
        assertEquals(201326592L, cost.weightsBytes)
        assertEquals(143343616L, cost.workingBytes)
        assertEquals(344670208L, cost.totalBytes)
    }

    @Test fun `ceiling fits with reserve and the next slider step does not`() {
        val reserve = ModelMemoryCalculator.calculate(model, settings, memory).reserveBytes
        val exact = memory.copy(availableBytes = reserve + 344670208L)
        val limit = ModelMemoryCalculator.calculate(model, settings, exact)
        assertEquals(MemoryLimitStatus.ESTIMATED, limit.status)
        assertEquals(512, limit.maximumContext)
        assertEquals(0, ModelMemoryCalculator.calculate(model, settings,
            exact.copy(availableBytes = exact.availableBytes - 1)).maximumContext)
        val next = requireNotNull(ModelMemoryCalculator.cost(model, settings, 1024))
        assertEquals(1024, ModelMemoryCalculator.calculate(model, settings,
            exact.copy(availableBytes = reserve + next.totalBytes)).maximumContext)
    }

    @Test fun `more RAM never lowers ceiling while larger weights and KV heads never raise it`() {
        val base = ModelMemoryCalculator.calculate(model, settings, memory).maximumContext
        assertTrue(ModelMemoryCalculator.calculate(model, settings, memory.copy(availableBytes = 7168 * mib)).maximumContext >= base)
        assertTrue(ModelMemoryCalculator.calculate(model.copy(fileBytes = 1024 * mib), settings, memory).maximumContext < base)
        assertTrue(ModelMemoryCalculator.calculate(model.copy(kvHeadCount = 4), settings, memory).maximumContext < base)
        assertTrue(ModelMemoryCalculator.calculate(model, settings.copy(gpuLayers = 1), memory).maximumContext < base)
    }

    @Test fun `larger batch is included in maximum calculation`() {
        val small = ModelMemoryCalculator.calculate(model, MemoryRuntimeSettings(false, 64), memory)
        val large = ModelMemoryCalculator.calculate(model, MemoryRuntimeSettings(false, 1024), memory)
        assertTrue(small.maximumContext > large.maximumContext)
    }

    @Test fun `missing or malformed dimensions never claim a safe ceiling`() {
        for (invalid in listOf(null, model.copy(architecture = "qwen35"), model.copy(blockCount = null),
            model.copy(kvHeadCount = 0), model.copy(keyLength = -1), model.copy(vocabularySize = null),
            model.copy(embeddingLength = 65, keyLength = null), model.copy(fileBytes = Long.MAX_VALUE),
            model.copy(blockCount = Int.MAX_VALUE, kvHeadCount = Int.MAX_VALUE, headCount = Int.MAX_VALUE,
                keyLength = Int.MAX_VALUE, valueLength = Int.MAX_VALUE))) {
            assertEquals(MemoryLimitStatus.METADATA_UNAVAILABLE, ModelMemoryCalculator.calculate(invalid, settings, memory).status)
        }
    }

    @Test fun `optional dimensions use documented defaults without treating a missing KV head count as zero`() {
        val implicit = model.copy(kvHeadCount = null, keyLength = null, valueLength = null)
        assertEquals(ModelMemoryCalculator.cost(model.copy(kvHeadCount = 4), settings, 4096),
            ModelMemoryCalculator.cost(implicit, settings, 4096))
    }

    @Test fun `memory pressure blocks even if a loaded allocation could be released`() {
        val pressured = memory.copy(lowMemory = true, processPrivateDirtyBytes = 2048 * mib)
        val limit = ModelMemoryCalculator.calculate(model, settings, pressured, 2048 * mib)
        assertFalse(limit.canLoad)
        assertEquals(MemoryLimitStatus.LOW_MEMORY, limit.status)
        assertEquals(0, limit.maximumContext)
    }

    @Test fun `unavailable snapshots integer extremes and 32 bit process are handled conservatively`() {
        for (invalid in listOf(null, memory.copy(totalBytes = 0), memory.copy(availableBytes = -1),
            memory.copy(availableBytes = memory.totalBytes + 1), memory.copy(processPrivateDirtyBytes = -1))) {
            assertEquals(MemoryLimitStatus.MEMORY_UNAVAILABLE, ModelMemoryCalculator.calculate(model, settings, invalid).status)
        }
        assertEquals(MemoryLimitStatus.LOW_MEMORY, ModelMemoryCalculator.calculate(model, settings,
            memory.copy(totalBytes = Long.MAX_VALUE, availableBytes = Long.MAX_VALUE, lowMemoryThresholdBytes = Long.MAX_VALUE)).status)
        assertFalse(ModelMemoryCalculator.calculate(model.copy(fileBytes = 1024 * mib), settings,
            memory.copy(is64Bit = false)).canLoad)
    }

    @Test fun `only measured dirty allocation credit is added back and cannot exceed device RAM`() {
        val before = ModelMemoryCalculator.calculate(model, settings, memory)
        val loaded = memory.copy(availableBytes = memory.availableBytes - 512 * mib, processPrivateDirtyBytes = 512 * mib)
        assertEquals(before.maximumContext, ModelMemoryCalculator.calculate(model, settings, loaded, 512 * mib).maximumContext)
        assertEquals(ModelMemoryCalculator.calculate(model, settings, loaded, 512 * mib).maximumContext,
            ModelMemoryCalculator.calculate(model, settings, loaded, Long.MAX_VALUE).maximumContext)
    }

    @Test fun `settings combine independent GGUF and memory ceilings and block without a fake minimum`() {
        val device = DeviceContextLimit(4096, MemoryLimitStatus.ESTIMATED)
        val params = ModelProfile.CHAT.defaults.copy(contextSize = 32768, trainedContextLength = 32768, deviceContextLimit = device)
            .normalizedForSettings()
        assertEquals(4096, params.contextSize)
        assertEquals(2048, params.maxTokens)
        assertEquals(1024, params.copy(trainedContextLength = 1024).contextLimits.maximum)
        val blocked = params.copy(deviceContextLimit = DeviceContextLimit(0, MemoryLimitStatus.LOW_MEMORY)).normalizedForSettings()
        assertEquals(0, blocked.contextSize)
        assertEquals(0, blocked.maxTokens)
        assertFalse(blocked.contextLimits.adjustable)
        assertThrows(IllegalStateException::class.java) { params.boundedForRuntime(32768, blocked.deviceContextLimit) }
        assertEquals(512, params.copy(maxTokens = 512).boundedForRuntime(32768, device).maxTokens)
    }
}
