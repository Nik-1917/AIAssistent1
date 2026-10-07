package com.example.aiassistent1.data.model

import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import com.example.aiassistent1.domain.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GgufMetadataReaderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `delivered qwen35 metadata supports RAM estimation in either byte order`() {
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val fixture = GgufTestFile(order).qwen35Memory().context(262144, "qwen35").architecture("qwen35")
            fixture.entry("qwen35.full_attention_interval", 10, fixture.long(4))
                .entry("qwen35.nextn_predict_layers", 4, fixture.int(0))
            val metadata = requireNotNull(GgufMetadataReader.readMetadata(fixture.write(temporaryFolder.newFile())))
            assertEquals(262144, metadata.contextLength)
            val memory = requireNotNull(metadata.memory)
            assertEquals(HybridMemoryMetadata(4, 2048, 128, 16, 16, 4), memory.hybrid)
            val estimate = requireNotNull(ModelMemoryCalculator.cost(memory.copy(fileBytes = 1_274_388_480), MemoryRuntimeSettings(), 512))
            assertEquals(MemoryEstimateProfile.QWEN35, estimate.profile)
        }
    }

    @Test fun `recurrent layout accepts native boolean arrays and scalar overrides`() {
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val fixture = GgufTestFile(order).architecture("qwen35").qwen35Memory()
            val flags = List(24) { (it + 1) % 4 != 0 }
            fixture.entry("qwen35.attention.recurrent_layers", 9,
                fixture.int(7) + fixture.long(24) + flags.map { if (it) 1.toByte() else 0.toByte() }.toByteArray())
            val metadata = requireNotNull(GgufMetadataReader.readMetadata(fixture.write(temporaryFolder.newFile()))?.memory)
            assertEquals(flags, metadata.hybrid?.recurrentLayers)
            assertNotNull(ModelMemoryCalculator.cost(metadata, MemoryRuntimeSettings(), 512))
        }
        val scalar = GgufTestFile().architecture("qwen35").qwen35Memory()
            .entry("qwen35.attention.recurrent_layers", 7, byteArrayOf(0))
        assertEquals(false, GgufMetadataReader.readMetadata(scalar.write(temporaryFolder.newFile()))?.memory?.hybrid?.uniformRecurrentLayers)
    }

    @Test fun `hybrid invalid fields masks hostile counts and duplicate keys remain blocked`() {
        val f = GgufTestFile()
        val cases = listOf(
            Triple("full_attention_interval", 4, f.int(0)),
            Triple("full_attention_interval", 8, f.string("4")),
            Triple("nextn_predict_layers", 10, f.long(Long.MAX_VALUE)),
            Triple("attention.recurrent_layers", 7, byteArrayOf(2)),
            Triple("attention.recurrent_layers", 8, f.string("true")),
            Triple("attention.recurrent_layers", 9, f.int(7) + f.long(Long.MAX_VALUE)),
            Triple("attention.recurrent_layers", 9, f.int(7) + f.long(4097) + ByteArray(4097)),
            Triple("attention.recurrent_layers", 9, f.int(7) + f.long(1) + byteArrayOf(1)),
            Triple("attention.recurrent_layers", 9, f.int(4) + f.long(24) + ByteArray(96)),
            Triple("ssm.inner_size", 4, f.int(2048)), // Duplicate, not a replacement.
        )
        for ((key, type, bytes) in cases) {
            val file = GgufTestFile().architecture("qwen35").qwen35Memory()
                .entry("qwen35.$key", type, bytes).write(temporaryFolder.newFile())
            val metadata = GgufMetadataReader.readMetadata(file)?.memory
            assertEquals(key, MemoryLimitStatus.METADATA_UNAVAILABLE, ModelMemoryCalculator.calculate(metadata,
                MemoryRuntimeSettings(), DeviceMemorySnapshot(8L shl 30, 6L shl 30, 256L shl 20, false)).status)
        }
    }

    @Test fun `unknown architecture with valid dimensions reaches conservative fallback`() {
        val file = GgufTestFile().architecture("future-v2").memory("future-v2").context(32768, "future-v2")
            .write(temporaryFolder.newFile())
        val metadata = requireNotNull(GgufMetadataReader.readMetadata(file)?.memory)
        val estimate = requireNotNull(ModelMemoryCalculator.cost(metadata, MemoryRuntimeSettings(), 512))
        assertEquals(MemoryEstimateProfile.CONSERVATIVE_FALLBACK, estimate.profile)
        assertNull(metadata.hybrid)
    }

    @Test fun `memory dimensions after context are read in either byte order`() {
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val fixture = GgufTestFile(order).architecture().context(32768).memory()
            fixture.entry("qwen3.attention.key_length", 10, fixture.long(128))
                .entry("qwen3.attention.value_length", 4, fixture.int(96))
                .memory("llama", layers = 80, kvHeads = 16)
            val file = fixture.write(temporaryFolder.newFile())
            val metadata = requireNotNull(GgufMetadataReader.readMetadata(file))
            val memory = requireNotNull(metadata.memory)
            assertEquals(32768, metadata.contextLength)
            assertEquals(file.length(), memory.fileBytes)
            assertEquals(32, memory.blockCount)
            assertEquals(2048, memory.embeddingLength)
            assertEquals(5632, memory.feedForwardLength)
            assertEquals(16, memory.headCount)
            assertEquals(8, memory.kvHeadCount)
            assertEquals(128, memory.keyLength)
            assertEquals(96, memory.valueLength)
            assertEquals(32000, memory.vocabularySize)
        }
    }

    @Test fun `vocabulary uses tokenizer count when size is missing or smaller`() {
        for (explicit in listOf<Int?>(null, 2, 10)) {
            val fixture = GgufTestFile().architecture().context(8192)
            if (explicit != null) fixture.entry("qwen3.vocab_size", 4, fixture.int(explicit))
            fixture.entry("tokenizer.ggml.tokens", 9, fixture.int(8) + fixture.long(3) +
                fixture.string("Привет") + fixture.string("") + fixture.string("😀"))
            val memory = requireNotNull(GgufMetadataReader.readMetadata(fixture.write(temporaryFolder.newFile()))?.memory)
            assertEquals(maxOf(3, explicit ?: 0), memory.vocabularySize)
            assertNull(memory.keyLength)
            assertNull(memory.kvHeadCount)
        }
    }

    @Test fun `invalid optional memory dimensions cannot silently fall back to defaults`() {
        for (type in listOf(4, 8, 9)) {
            val fixture = GgufTestFile().architecture().context(8192).memory()
            val encoded = when (type) {
                4 -> fixture.int(0)
                8 -> fixture.string("128")
                else -> fixture.int(4) + fixture.long(1) + fixture.int(128)
            }
            fixture.entry("qwen3.attention.key_length", type, encoded)
            val metadata = requireNotNull(GgufMetadataReader.readMetadata(fixture.write(temporaryFolder.newFile())))
            assertEquals(8192, metadata.contextLength)
            assertNull(metadata.memory)
        }
    }

    @Test fun `reads v2 and v3 uint32 and uint64 in either byte order and key order`() {
        for (version in 2..3) for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            for (wide in listOf(false, true)) for (contextFirst in listOf(false, true)) {
                val fixture = GgufTestFile(order, version)
                if (contextFirst) fixture.context(32768, wide = wide).architecture()
                else fixture.architecture().context(32768, wide = wide)
                val file = fixture.write(temporaryFolder.newFile())
                assertEquals(32768, GgufMetadataReader.readContextLength(file))
            }
        }
    }

    @Test fun `matches the declared architecture instead of another context or rope window`() {
        val fixture = GgufTestFile().context(4096, "llama")
        fixture.entry("qwen3.rope.scaling.original_context_length", 4, fixture.int(8192))
            .architecture().context(131072)
        assertEquals(131072, GgufMetadataReader.readContextLength(fixture.write(temporaryFolder.newFile())))
        val unrelated = GgufTestFile().architecture().context(4096, "llama")
        assertNull(GgufMetadataReader.readContextLength(unrelated.write(temporaryFolder.newFile())))
    }

    @Test fun `architecture names can contain hyphens and underscores used by llama cpp`() {
        for (architecture in listOf("command-r", "gpt-oss", "nemotron_h", "ernie4_5-moe")) {
            val file = GgufTestFile().architecture(architecture).context(32768, architecture)
                .write(temporaryFolder.newFile())
            assertEquals(32768, GgufMetadataReader.readContextLength(file))
        }
    }

    @Test fun `skips tokenizer strings numeric arrays and all scalar widths before metadata`() {
        val fixture = GgufTestFile()
        for ((type, size) in mapOf(0 to 1, 1 to 1, 2 to 2, 3 to 2, 4 to 4, 5 to 4,
            6 to 4, 7 to 1, 10 to 8, 11 to 8, 12 to 8)) {
            fixture.entry("scalar.$type", type, ByteArray(size))
            fixture.entry("array.$type", 9, fixture.int(type) + fixture.long(3) + ByteArray(size * 3))
        }
        fixture.entry("tokenizer.ggml.tokens", 9, fixture.int(8) + fixture.long(3) +
            fixture.string("Привет") + fixture.string("") + fixture.string("😀"))
        fixture.architecture("llama").context(4096, "llama")
        assertEquals(4096, GgufMetadataReader.readContextLength(fixture.write(temporaryFolder.newFile())))
    }

    @Test fun `buffer boundaries and long skipped strings preserve metadata in either byte order`() {
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val fixture = GgufTestFile(order)
            val strings = ByteArrayOutputStream()
            repeat(5_000) { index ->
                strings.write(fixture.string(when {
                    index == 1_234 -> "длинная строка".repeat(10_000)
                    index % 3 == 0 -> ""
                    else -> "Токен $index 😀 граница буфера"
                }))
            }
            fixture.entry("tokenizer.ggml.tokens", 9, fixture.int(8) + fixture.long(5_000) + strings.toByteArray())
                .entry("unused.large", 9, fixture.int(4) + fixture.long(40_000) + ByteArray(160_000))
                .architecture().context(32768)
            val metadata = requireNotNull(GgufMetadataReader.readMetadata(fixture.write(temporaryFolder.newFile())))
            assertEquals(32768, metadata.contextLength)
            assertEquals(5_000, metadata.memory?.vocabularySize)
        }
    }

    @Test fun `truncation after a buffer boundary remains rejected`() {
        val fixture = GgufTestFile().entry("unused.padding", 8,
            GgufTestFile().long(70_000) + ByteArray(70_000)).architecture().context(32768)
        val bytes = fixture.bytes()
        for (cut in listOf(1, 4, 8, 12)) {
            assertNull(GgufMetadataReader.readMetadata(temporaryFolder.newFile().also {
                it.writeBytes(bytes.copyOf(bytes.size - cut))
            }))
        }
    }

    @Test fun `missing metadata unsupported versions invalid lengths and wrong types remain unknown`() {
        val cases = listOf(
            GgufTestFile().architecture(), GgufTestFile().context(8192),
            GgufTestFile(version = 1).architecture().context(8192),
            GgufTestFile(version = 4).architecture().context(8192),
            GgufTestFile().architecture().context(0),
            GgufTestFile().architecture().context(1),
            GgufTestFile().architecture().context(0xffffffffL),
            GgufTestFile().architecture().context(Long.MAX_VALUE, wide = true),
            GgufTestFile().architecture().context(-1, wide = true),
            GgufTestFile().architecture("../qwen3").context(8192),
        )
        cases.forEach { assertNull(GgufMetadataReader.readContextLength(it.write(temporaryFolder.newFile()))) }
        val wrongType = GgufTestFile().architecture()
        wrongType.entry("qwen3.context_length", 8, wrongType.string("8192"))
        assertNull(GgufMetadataReader.readContextLength(wrongType.write(temporaryFolder.newFile())))
    }

    @Test fun `truncation and hostile counts cannot allocate arrays or seek beyond the file`() {
        val fixture = GgufTestFile().architecture().context(32768)
        val bytes = fixture.bytes()
        for (size in bytes.indices) {
            val file = temporaryFolder.newFile().also { it.writeBytes(bytes.copyOf(size)) }
            assertNull("truncated at $size", GgufMetadataReader.readContextLength(file))
        }
        val attacks = listOf(
            GgufTestFile().apply { entry("tokenizer", 9, int(8) + long(Long.MAX_VALUE)) },
            GgufTestFile().apply { entry("tokenizer", 9, int(10) + long(Long.MAX_VALUE)) },
            GgufTestFile().apply { entry("tokenizer", 8, long(Long.MAX_VALUE)) },
            GgufTestFile().apply { entry("nested", 9, int(9) + long(1)) },
            GgufTestFile().apply { entry("invalid", 99, ByteArray(0)) },
        )
        attacks.forEach {
            it.architecture().context(32768)
            assertNull(GgufMetadataReader.readContextLength(it.write(temporaryFolder.newFile())))
        }
        val badCount = bytes.copyOf().also { fixture.long(Long.MAX_VALUE).copyInto(it, 16) }
        assertNull(GgufMetadataReader.readContextLength(temporaryFolder.newFile().also { it.writeBytes(badCount) }))
    }

    @Test fun `replacement removal and late import invalidate cached metadata`() {
        val file = File(temporaryFolder.root, "assistant.gguf")
        assertNull(GgufMetadataReader.readContextLength(file))
        GgufTestFile().architecture().context(32768).write(file)
        assertEquals(32768, GgufMetadataReader.readContextLength(file))
        assertEquals(32768, GgufMetadataReader.readContextLength(file))
        GgufTestFile().architecture().context(4096, wide = true).write(file)
        assertEquals(4096, GgufMetadataReader.readContextLength(file))
        assertTrue(file.delete())
        assertNull(GgufMetadataReader.readContextLength(file))
    }

    @Test fun `model directory lookup rejects escaping paths`() {
        GgufTestFile().architecture().context(16384).write(File(temporaryFolder.root, "assistant.gguf"))
        assertEquals(16384, GgufMetadataReader.readInDirectory(temporaryFolder.root, "assistant.gguf"))
        for (name in listOf("../assistant.gguf", "..\\assistant.gguf", "", "assistant.bin")) {
            assertNull(GgufMetadataReader.readInDirectory(temporaryFolder.root, name))
        }
    }
}
