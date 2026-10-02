package com.example.aiassistent1.data.model

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Small binary fixtures with actual GGUF headers; no model weights needed. */
internal class GgufTestFile(private val order: ByteOrder = ByteOrder.LITTLE_ENDIAN, private val version: Int = 3) {
    private val entries = mutableListOf<ByteArray>()
    fun int(value: Int): ByteArray = ByteBuffer.allocate(4).order(order).putInt(value).array()
    fun long(value: Long): ByteArray = ByteBuffer.allocate(8).order(order).putLong(value).array()
    fun string(value: String): ByteArray = value.toByteArray(Charsets.UTF_8).let { long(it.size.toLong()) + it }
    fun entry(key: String, type: Int, value: ByteArray): GgufTestFile = apply {
        entries += string(key) + int(type) + value
    }
    fun architecture(value: String = "qwen3") = entry("general.architecture", 8, string(value))
    fun context(value: Long, architecture: String = "qwen3", wide: Boolean = false) =
        entry("$architecture.context_length", if (wide) 10 else 4, if (wide) long(value) else int(value.toInt()))

    fun bytes(): ByteArray = ByteArrayOutputStream().use { stream ->
        stream.write("GGUF".toByteArray(Charsets.US_ASCII))
        stream.write(int(version))
        stream.write(long(0))
        stream.write(long(entries.size.toLong()))
        entries.forEach(stream::write)
        stream.toByteArray()
    }

    fun write(file: File): File = file.also { it.writeBytes(bytes()) }
}
