package com.example.aiassistent1.domain.model

enum class ComputeMode { AUTO, CPU }

enum class InferenceBackend { CPU, VULKAN }

data class GpuDevice(
    val name: String,
    val identity: String,
    val freeBytes: Long,
    val totalBytes: Long,
)

/** Measurements belong to one native allocation and its latest generation. */
data class InferenceExecution(
    val requestedMode: ComputeMode = ComputeMode.AUTO,
    val backend: InferenceBackend? = null,
    val deviceName: String? = null,
    val offloadedLayers: Int = 0,
    val totalLayers: Int = 0,
    val modelName: String? = null,
    val fallbackReason: String? = null,
    val initializationMillis: Long? = null,
    val timeToFirstTokenMillis: Long? = null,
    val prefillTokensPerSecond: Double? = null,
    val decodeTokensPerSecond: Double? = null,
    val generatedTokens: Int = 0,
    val checkingGpu: Boolean = false,
)

/** A restarted attempt replaces the unfinished response of the same request. */
sealed interface GenerationEvent {
    data class Delta(val text: String) : GenerationEvent
    data class Restart(val reason: String) : GenerationEvent
}
