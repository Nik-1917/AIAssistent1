package com.example.aiassistent1.domain.interfaces

import com.example.aiassistent1.domain.model.MetricKwsConfig
import com.example.aiassistent1.domain.model.MetricKwsProfile

/** Offline utterance encoder. No microphone, ASR, feedback or settings ownership. */
interface MetricKwsEngine {
    val config: MetricKwsConfig?
    val unavailableReason: String?
    /** Must remain false until license, Russian, ONNX, Android and shadow evidence is approved. */
    val activationValidated: Boolean
    suspend fun embedding(samples: FloatArray): FloatArray
    suspend fun close()
}

interface MetricKwsProfileStore {
    suspend fun load(): MetricKwsProfile?
    suspend fun save(profile: MetricKwsProfile)
    suspend fun delete()
}
