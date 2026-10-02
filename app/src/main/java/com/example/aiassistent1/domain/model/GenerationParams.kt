package com.example.aiassistent1.domain.model

import kotlin.math.abs

data class GenerationParams(
    val contextSize: Int = DEFAULT_CONTEXT_SIZE,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val temperature: Float = 0.36f,
    val topP: Float = 0.9f,
    val topK: Int = 20,
    val repeatPenalty: Float = 1.15f,
    val gpuLayers: Int = 0,
    val batchSizeAuto: Boolean = true,
    val batchSize: Int = 256,
    val cpuThreadsAuto: Boolean = true,
    val cpuThreads: Int = CpuThreadSettings.automaticThreadCount(),
    /** Read from the selected GGUF, never trusted from saved preferences or a UI callback. */
    val trainedContextLength: Int? = null,
) {
    val contextLimits: ModelContextLimits get() = ModelContextLimits(trainedContextLength)

    /** Both settings sliders share the selected model's bounded grid. */
    fun withContextSize(value: Int): GenerationParams {
        val context = contextLimits.normalize(value)
        return copy(contextSize = context, maxTokens = context / 2)
    }

    fun withMaxTokens(value: Int): GenerationParams =
        withContextSize(value.coerceIn(contextLimits.minimum / 2, contextLimits.maximum / 2) * 2)

    /** Keep smaller temporary response budgets, including conference summaries. */
    fun boundedForRuntime(actualContextLength: Int?): GenerationParams {
        val maximum = ModelContextLimits(actualContextLength).maximum
        val context = contextSize.coerceIn(2, maximum)
        return copy(
            trainedContextLength = actualContextLength,
            contextSize = context,
            maxTokens = maxTokens.coerceIn(1, context / 2),
        )
    }

    val batchSizeOptions: List<Int>
        get() = MANUAL_BATCH_SIZES.filter { it <= contextSize }

    /** A bounded prefill chunk; this does not truncate the prompt or reserve its answer space. */
    val effectiveBatchSize: Int
        get() {
            val contextLimit = contextSize.coerceAtLeast(1)
            return if (batchSizeAuto) {
                (contextSize.toLong() - maxTokens.toLong())
                    .coerceIn(1L, minOf(AUTO_BATCH_LIMIT, contextLimit).toLong()).toInt()
            } else {
                normalizedManualBatchSize(contextLimit)
            }
        }

    fun effectiveCpuThreads(processorCount: Int = CpuThreadSettings.availableProcessors): Int =
        if (cpuThreadsAuto) CpuThreadSettings.automaticThreadCount(processorCount)
        else CpuThreadSettings.boundedThreadCount(cpuThreads, processorCount)

    // The stored context is authoritative for legacy, independently saved slider values.
    fun normalizedForSettings(): GenerationParams {
        val paired = withContextSize(contextSize)
        return paired.copy(
            topK = paired.topK.coerceIn(MIN_TOP_K, MAX_TOP_K),
            batchSize = paired.normalizedManualBatchSize(paired.contextSize),
            cpuThreads = CpuThreadSettings.boundedThreadCount(paired.cpuThreads),
        )
    }

    private fun normalizedManualBatchSize(contextLimit: Int): Int =
        MANUAL_BATCH_SIZES.filter { it <= contextLimit }
            .minByOrNull { abs(it.toLong() - batchSize.toLong()) }
            ?: contextLimit.coerceAtLeast(1)

    companion object {
        const val DEFAULT_CONTEXT_SIZE = 512
        const val DEFAULT_MAX_TOKENS = 256
        const val MIN_CONTEXT_SIZE = 512
        const val CONTEXT_STEP = 512
        const val MIN_TOP_K = 1
        const val MAX_TOP_K = 100
        const val AUTO_BATCH_LIMIT = 512
        val MANUAL_BATCH_SIZES: List<Int> = listOf(64, 128, 256, 512, 1024)
    }
}
