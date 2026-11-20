package com.example.aiassistent1.data.model

import com.example.aiassistent1.domain.model.ModelMemoryCalculator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GgufGpuWeightsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `partial offload uses the last layer tensor extents and output uses shared tensors`() {
        val fixture = GgufTestFile().architecture().context(8192).memory(layers = 2)
        val metadata = fixture.bytes()
        ByteBuffer.wrap(metadata).order(ByteOrder.LITTLE_ENDIAN).putLong(8, 3)
        fun tensor(name: String, offset: Long) = fixture.string(name) + fixture.int(1) +
            fixture.long(8) + fixture.int(0) + fixture.long(offset)
        val header = metadata + tensor("blk.0.weight", 0) + tensor("blk.1.weight", 64) + tensor("output.weight", 96)
        val padding = ByteArray((32 - header.size % 32) % 32)
        val file = folder.newFile().also { it.writeBytes(header + padding + ByteArray(128)) }
        val memory = requireNotNull(GgufMetadataReader.readMetadata(file)?.memory)
        assertEquals(listOf(64L, 32L), memory.gpuLayerBytes)
        assertEquals(32L, memory.gpuSharedBytes)
        assertEquals(32L, ModelMemoryCalculator.gpuWeights(memory, 1))
        assertEquals(64L, ModelMemoryCalculator.gpuWeights(memory, 2))
        assertEquals(32L, ModelMemoryCalculator.largestGpuTensor(memory, 2))
        assertEquals(64L, ModelMemoryCalculator.largestGpuTensor(memory, 3))
        assertEquals(128L, ModelMemoryCalculator.gpuWeights(memory, 3))
        assertEquals(128L, ModelMemoryCalculator.gpuWeights(memory, -1))
        assertEquals(0L, ModelMemoryCalculator.gpuWeights(memory, 0))
    }

    @Test fun `invalid tensor descriptors keep the conservative estimate and valid metadata`() {
        val fixture = GgufTestFile().architecture().context(8192).memory(layers = 2)
        val bytes = fixture.bytes()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(8, 1)
        val descriptor = fixture.string("blk.0.weight") + fixture.int(1) + fixture.long(8) +
            fixture.int(0) + fixture.long(Long.MAX_VALUE)
        val file = folder.newFile().also { it.writeBytes(bytes + descriptor) }
        val model = requireNotNull(GgufMetadataReader.readMetadata(file))
        assertEquals(8192, model.contextLength)
        val memory = requireNotNull(model.memory)
        assertNull(memory.gpuLayerBytes)
        assertEquals(file.length(), ModelMemoryCalculator.gpuWeights(memory, 1))
    }
}
