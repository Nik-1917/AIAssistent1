package com.example.aiassistent1.data.model

import com.example.aiassistent1.domain.model.ModelMemoryMetadata
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Reads only GGUF metadata. Tensor weights and tokenizer arrays are never materialized. */
object GgufMetadataReader {
    data class Metadata(val contextLength: Int?, val memory: ModelMemoryMetadata?)
    private data class Stamp(val path: String, val size: Long, val modified: Long)
    private data class Entry(val stamp: Stamp, val metadata: Metadata?)
    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > 16
    }

    /** The caller dispatches file I/O off the main thread. */
    fun readContextLength(file: File): Int? = readMetadata(file)?.contextLength

    fun readMetadata(file: File): Metadata? = try {
        val canonical = file.canonicalFile
        val stamp = Stamp(canonical.path, canonical.length(), canonical.lastModified())
        if (!canonical.isFile || stamp.size < 24) {
            null
        } else {
            val cached = synchronized(cache) { cache[stamp.path] }
            if (cached?.stamp == stamp) cached.metadata else {
                val metadata = readUncached(canonical)
                // Do not use metadata from a file that changed while it was being read.
                if (canonical.length() == stamp.size && canonical.lastModified() == stamp.modified) {
                    synchronized(cache) { cache[stamp.path] = Entry(stamp, metadata) }
                    metadata
                } else null
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }

    fun readInDirectory(directory: File, modelName: String): Int? =
        resolveInDirectory(directory, modelName)?.let(::readContextLength)

    fun resolveInDirectory(directory: File, modelName: String): File? = try {
        require(modelName.isNotBlank() && modelName.length <= 128)
        require(!modelName.contains('/') && !modelName.contains('\\'))
        require(modelName.endsWith(".gguf", ignoreCase = true))
        val parent = directory.canonicalFile
        val model = File(parent, modelName).canonicalFile
        require(model.parentFile == parent)
        model
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private val memoryKeys = setOf("block_count", "embedding_length", "feed_forward_length", "attention.head_count",
        "attention.head_count_kv", "attention.key_length", "attention.value_length", "vocab_size")

    private fun readUncached(file: File): Metadata? = RandomAccessFile(file, "r").use { input ->
        require(input.readInt() == 0x47475546) // ASCII GGUF
        val encodedVersion = input.readInt()
        val littleEndian = when {
            Integer.reverseBytes(encodedVersion) in 2..3 -> true
            encodedVersion in 2..3 -> false
            else -> return@use null
        }
        val reader = MetadataInput(input, littleEndian)
        reader.unsignedLong() // tensor count, not tensor data
        val count = reader.unsignedLong()
        require(count <= 100_000 && count <= reader.remaining / 12)
        var architecture: String? = null
        val contexts = mutableMapOf<String, Int?>()
        val dimensions = mutableMapOf<String, Int?>()
        var vocabularySize: Int? = null
        repeat(count.toInt()) {
            val key = reader.string(65_535)
            val type = reader.unsignedInt().toInt()
            when {
                key == "general.architecture" -> {
                    require(type == 8 && architecture == null)
                    architecture = reader.string(128)
                    require(architecture!!.matches(Regex("[a-z0-9_-]+")))
                }
                key.endsWith(".context_length") -> {
                    require(contexts.size < 128 && key !in contexts)
                    val value = when (type) {
                        4 -> reader.unsignedInt()
                        10 -> reader.unsignedLong()
                        else -> { reader.skipValue(type); null }
                    }
                    contexts[key] = value?.takeIf { it in 2..Int.MAX_VALUE.toLong() }?.toInt()
                }
                key.substringAfter('.') in memoryKeys -> {
                    require(dimensions.size < 1024 && key !in dimensions)
                    val value = when (type) {
                        4 -> reader.unsignedInt()
                        10 -> reader.unsignedLong()
                        else -> { reader.skipValue(type); null }
                    }
                    dimensions[key] = value?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
                }
                key == "tokenizer.ggml.tokens" && type == 9 -> {
                    require(vocabularySize == null)
                    vocabularySize = reader.skipArray()?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
                }
                else -> reader.skipValue(type)
            }
        }
        val arch = architecture ?: return@use Metadata(null, null)
        fun dimension(name: String) = dimensions["$arch.$name"]
        // A present but invalid/array-valued dimension must not silently use an optional default.
        val validDimensions = dimensions.filterKeys { it.startsWith("$arch.") }.values.all { it != null }
        val memory = if (!validDimensions) null else ModelMemoryMetadata(
            architecture = arch, fileBytes = file.length(), blockCount = dimension("block_count"),
            embeddingLength = dimension("embedding_length"), feedForwardLength = dimension("feed_forward_length"),
            headCount = dimension("attention.head_count"), kvHeadCount = dimension("attention.head_count_kv"),
            keyLength = dimension("attention.key_length"), valueLength = dimension("attention.value_length"),
            vocabularySize = listOfNotNull(dimension("vocab_size"), vocabularySize).maxOrNull(),
        )
        Metadata(contexts["$arch.context_length"], memory)
    }

    private class MetadataInput(private val input: RandomAccessFile, private val littleEndian: Boolean) {
        // Bounds work on malformed files without allocating from untrusted lengths.
        private val end = minOf(input.length(), 256L * 1024 * 1024)
        val remaining: Long get() = (end - input.filePointer).coerceAtLeast(0)
        private var stringElements = 0L

        fun unsignedInt(): Long {
            require(remaining >= 4)
            val value = input.readInt()
            return (if (littleEndian) Integer.reverseBytes(value) else value).toLong() and 0xffffffffL
        }

        fun unsignedLong(): Long {
            require(remaining >= 8)
            val value = input.readLong().let { if (littleEndian) java.lang.Long.reverseBytes(it) else it }
            require(value >= 0)
            return value
        }

        fun string(maxBytes: Int): String {
            val length = unsignedLong()
            require(length <= maxBytes && length <= remaining)
            val bytes = ByteArray(length.toInt())
            input.readFully(bytes)
            return bytes.toString(Charsets.UTF_8)
        }

        private fun skip(bytes: Long) {
            require(bytes >= 0 && bytes <= remaining)
            input.seek(input.filePointer + bytes)
        }

        fun skipValue(type: Int) {
            when (type) {
                8 -> skip(unsignedLong())
                9 -> { skipArray() }
                else -> skip(scalarSize(type))
            }
        }

        fun skipArray(): Long? {
            val elementType = unsignedInt().toInt()
            val count = unsignedLong()
            if (elementType == 8) {
                require(count <= remaining / 8 && count <= 2_000_000 - stringElements)
                stringElements += count
                repeat(count.toInt()) { skip(unsignedLong()) }
            } else {
                val size = scalarSize(elementType)
                require(count <= remaining / size)
                skip(count * size)
            }
            return count.takeIf { elementType == 8 }
        }

        private fun scalarSize(type: Int): Long = when (type) {
            0, 1, 7 -> 1
            2, 3 -> 2
            4, 5, 6 -> 4
            10, 11, 12 -> 8
            else -> throw IllegalArgumentException("Unsupported GGUF metadata type")
        }
    }
}
