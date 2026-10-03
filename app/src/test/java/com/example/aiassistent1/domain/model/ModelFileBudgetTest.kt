package com.example.aiassistent1.domain.model

import org.junit.Assert.*
import org.junit.Test

class ModelFileBudgetTest {
    private val mib = 1024L * 1024
    private val model = ModelMemoryMetadata("qwen3", 128 * mib, 2, 64, 128, 4, 2, 16, 16, 1000)
    private val settings = MemoryRuntimeSettings()

    @Test fun `file ceiling fits exactly and accounts for all buffers without counting the old file twice`() {
        val budget = ModelFileBudget(344670208L, model)
        assertEquals(134217728L, budget.maximumFileBytes(512, settings))
        assertEquals(134217727L, budget.copy(capacityBytes = budget.capacityBytes - 1).maximumFileBytes(512, settings))
        assertEquals(134217729L, budget.copy(capacityBytes = budget.capacityBytes + 1).maximumFileBytes(512, settings))
        // Different quantization/file size with the same dimensions has the same calculated ceiling.
        assertEquals(budget.maximumFileBytes(512, settings),
            budget.copy(metadata = model.copy(fileBytes = 1024 * mib)).maximumFileBytes(512, settings))
    }

    @Test fun `both overhead branches and GPU copies use the same cost as context allocation`() {
        for (fileBytes in listOf(128 * mib, 640 * mib - 1, 640 * mib, 640 * mib + 1, 3072 * mib)) {
            for (gpuLayers in listOf(0, 1, -1)) {
                val runtime = settings.copy(gpuLayers = gpuLayers)
                val candidate = model.copy(fileBytes = fileBytes)
                val capacity = requireNotNull(ModelMemoryCalculator.cost(candidate, runtime, 4096)).totalBytes
                val budget = ModelFileBudget(capacity, model)
                assertEquals(fileBytes, budget.maximumFileBytes(4096, runtime))
                assertTrue(requireNotNull(budget.copy(capacityBytes = capacity - 1).maximumFileBytes(4096, runtime)) < fileBytes)
                assertTrue(requireNotNull(ModelMemoryCalculator.cost(candidate.copy(fileBytes = fileBytes + 1), runtime, 4096)).totalBytes > capacity)
            }
        }
    }

    @Test fun `larger context batch and GPU reservation reduce the file limit`() {
        val budget = ModelFileBudget(4096 * mib, model)
        val base = requireNotNull(budget.maximumFileBytes(4096, settings))
        assertTrue(requireNotNull(budget.maximumFileBytes(8192, settings)) < base)
        assertTrue(requireNotNull(budget.maximumFileBytes(4096, settings.copy(gpuLayers = 1))) < base)
        assertTrue(requireNotNull(budget.maximumFileBytes(4096, MemoryRuntimeSettings(false, 1024))) <
            requireNotNull(budget.maximumFileBytes(4096, MemoryRuntimeSettings(false, 64))))
    }

    @Test fun `reserve low memory metadata and loaded private credit flow into the file budget`() {
        val before = DeviceMemorySnapshot(8192 * mib, 6144 * mib, 256 * mib, false)
        val limit = ModelMemoryCalculator.calculate(model, settings, before)
        assertEquals(before.availableBytes - limit.reserveBytes, requireNotNull(limit.modelFileBudget).capacityBytes)
        val after = before.copy(availableBytes = before.availableBytes - 512 * mib, processPrivateDirtyBytes = 512 * mib)
        assertEquals(limit.modelFileBudget, ModelMemoryCalculator.calculate(model, settings, after, 512 * mib).modelFileBudget)
        assertNull(ModelMemoryCalculator.calculate(model, settings, before.copy(lowMemory = true)).modelFileBudget)
        assertNull(ModelMemoryCalculator.calculate(null, settings, before).modelFileBudget)
        assertNull(ModelMemoryCalculator.calculate(model, settings, null).modelFileBudget)
        val hugeModel = model.copy(fileBytes = 8192 * mib)
        val blocked = ModelMemoryCalculator.calculate(hugeModel, settings, before)
        assertEquals(MemoryLimitStatus.INSUFFICIENT_MEMORY, blocked.status)
        assertTrue(requireNotNull(blocked.modelFileBudget?.maximumFileBytes(512, settings)) > 0)
        assertTrue(requireNotNull(ModelMemoryCalculator.calculate(model, settings, before.copy(is64Bit = false))
            .modelFileBudget).capacityBytes <= 768 * mib)
    }

    @Test fun `missing or invalid inputs are distinguished from zero remaining capacity`() {
        val budget = ModelFileBudget(4096 * mib, model)
        assertEquals(0L, budget.copy(capacityBytes = 0).maximumFileBytes(512, settings))
        assertEquals(0L, budget.copy(capacityBytes = 128 * mib).maximumFileBytes(512, settings))
        assertNull(budget.copy(capacityBytes = -1).maximumFileBytes(512, settings))
        assertNull(budget.maximumFileBytes(0, settings))
        val fallback = requireNotNull(budget.copy(metadata = model.copy(architecture = "unknown")).maximumFileBytes(512, settings))
        assertTrue(fallback in 1 until requireNotNull(budget.maximumFileBytes(512, settings)))
        assertNull(budget.copy(metadata = model.copy(architecture = "")).maximumFileBytes(512, settings))
        assertNull(budget.copy(metadata = model.copy(vocabularySize = null)).maximumFileBytes(512, settings))
    }

    @Test fun `long limits never overflow binary search or claim a file that exceeds capacity`() {
        for (runtime in listOf(settings, settings.copy(gpuLayers = 1))) {
            val maximum = requireNotNull(ModelFileBudget(Long.MAX_VALUE, model).maximumFileBytes(512, runtime))
            assertTrue(maximum > 0 && maximum < Long.MAX_VALUE)
            assertNotNull(ModelMemoryCalculator.cost(model.copy(fileBytes = maximum), runtime, 512))
            assertNull(ModelMemoryCalculator.cost(model.copy(fileBytes = maximum + 1), runtime, 512))
        }
    }
}
