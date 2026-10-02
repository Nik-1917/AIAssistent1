package com.example.aiassistent1.data.model

import com.example.aiassistent1.domain.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceModelMemoryGuardTest {
    @get:Rule val directory = TemporaryFolder()
    private val mib = 1024L * 1024

    @Test fun `loaded model is not subtracted twice and unloading clears the credit`() {
        val file = GgufTestFile().architecture().context(32768).memory().write(directory.newFile())
        var memory = DeviceMemorySnapshot(8192 * mib, 6144 * mib, 256 * mib, false, 256 * mib)
        val guard = DeviceModelMemoryGuard { memory }
        val params = ModelProfile.CHAT.defaults.copy(contextSize = 4096, maxTokens = 2048)
        val before = guard.assess(file, params)
        val metadata = requireNotNull(GgufMetadataReader.readMetadata(file)?.memory)
        val cost = requireNotNull(ModelMemoryCalculator.cost(metadata, MemoryRuntimeSettings(), params.contextSize))
        guard.loaded(file, params, memory)
        memory = memory.copy(availableBytes = memory.availableBytes - cost.workingBytes,
            processPrivateDirtyBytes = memory.processPrivateDirtyBytes + cost.workingBytes)
        assertEquals(before.maximumContext, guard.assess(file, params).maximumContext)
        guard.unloaded()
        assertTrue(guard.assess(file, params).maximumContext < before.maximumContext)
    }

    @Test fun `response ratio and short temporary answer do not change memory ceiling`() {
        val file = GgufTestFile().architecture().context(32768).memory().write(directory.newFile())
        val guard = DeviceModelMemoryGuard { DeviceMemorySnapshot(8192 * mib, 6144 * mib, 256 * mib, false) }
        val params = ModelProfile.CHAT.defaults.copy(contextSize = 4096, maxTokens = 2048)
        val limit = guard.assess(file, params)
        assertEquals(limit, guard.assess(file, params.withContextResponseRatio(ContextResponseRatio.FOUR_TO_ONE)))
        assertEquals(limit, guard.assess(file, params.copy(maxTokens = 128)))
    }

    @Test fun `missing unreadable metadata and lost Android snapshot are explicit blocks`() {
        val file = GgufTestFile().architecture().context(32768).write(directory.newFile())
        var memory: DeviceMemorySnapshot? = DeviceMemorySnapshot(8192 * mib, 6144 * mib, 256 * mib, false)
        val guard = DeviceModelMemoryGuard { memory }
        assertEquals(MemoryLimitStatus.METADATA_UNAVAILABLE, guard.assess(file, GenerationParams()).status)
        memory = null
        assertEquals(MemoryLimitStatus.MEMORY_UNAVAILABLE, guard.assess(file, GenerationParams()).status)
    }
}
