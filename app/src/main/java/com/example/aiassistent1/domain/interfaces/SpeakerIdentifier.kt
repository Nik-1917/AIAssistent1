package com.example.aiassistent1.domain.interfaces

interface SpeakerIdentifier : AutoCloseable {
    suspend fun prepare()
    suspend fun computeEmbedding(samples: FloatArray): FloatArray?
    fun verify(embedding1: FloatArray, embedding2: FloatArray, threshold: Float = 0.5f): Boolean
}
