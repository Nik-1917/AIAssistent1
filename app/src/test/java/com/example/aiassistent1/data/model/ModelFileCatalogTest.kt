package com.example.aiassistent1.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelFileCatalogTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun listsModelsByActualSizeWithTheLightestLast() {
        val models = temporaryFolder.newFolder("models")
        File(models, "small.gguf").writeBytes(ByteArray(1))
        File(models, "large.GGUF").writeBytes(ByteArray(5))
        File(models, "alpha.gguf").writeBytes(ByteArray(3))
        File(models, "beta.gguf").writeBytes(ByteArray(3))
        File(models, "empty.gguf").createNewFile()
        File(models, ".gguf").writeBytes(ByteArray(2))
        File(models, "unfinished.part").writeBytes(ByteArray(20))
        File(models, "folder.gguf").mkdir()

        val result = ModelFileCatalog(models).list()

        assertEquals(listOf("large.GGUF", "alpha.gguf", "beta.gguf", "small.gguf"), result.map { it.fileName })
        assertEquals(listOf(5L, 3L, 3L, 1L), result.map { it.sizeBytes })
        assertEquals("large", result.first().displayName)
    }

    @Test fun fifthModelBlocksImportUntilAModelIsRemoved() {
        val models = temporaryFolder.newFolder("models")
        repeat(5) { index -> File(models, "model$index.gguf").writeBytes(byteArrayOf(1)) }
        val catalog = ModelFileCatalog(models)

        assertThrows(IllegalArgumentException::class.java) { catalog.requireSpaceForAnother() }
        catalog.delete("model0.gguf")
        assertFalse(File(models, "model0.gguf").exists())
        assertEquals(4, catalog.list().size)
        catalog.requireSpaceForAnother()
    }

    @Test fun deletionCannotReachOutsideTheModelDirectory() {
        val models = temporaryFolder.newFolder("models")
        val outside = temporaryFolder.newFile("outside.gguf")
        outside.writeBytes(byteArrayOf(1))
        val catalog = ModelFileCatalog(models)

        assertThrows(IllegalArgumentException::class.java) { catalog.delete("../outside.gguf") }
        assertTrue(outside.exists())
    }
}
