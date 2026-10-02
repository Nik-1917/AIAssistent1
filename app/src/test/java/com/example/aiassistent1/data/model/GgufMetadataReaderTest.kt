package com.example.aiassistent1.data.model

import java.io.File
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GgufMetadataReaderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

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
