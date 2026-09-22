package com.example.aiassistent1.domain.interfaces

interface KeywordSpotter : AutoCloseable {
    fun isAvailable(): Boolean = false
    suspend fun prepare()
    suspend fun accept(samples: FloatArray): String?
    fun reset()
}
