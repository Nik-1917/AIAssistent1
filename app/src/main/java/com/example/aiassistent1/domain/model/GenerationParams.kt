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
) {
    /** Settings use a shared 16-position grid; temporary engine parameters remain independent. */
    fun withContextSize(value: Int): GenerationParams {
        val bounded = value.coerceIn(MIN_CONTEXT_SIZE, MAX_CONTEXT_SIZE)
        val context = ((bounded + CONTEXT_STEP / 2) / CONTEXT_STEP) * CONTEXT_STEP
        return copy(contextSize = context, maxTokens = context / 2)
    }

    fun withMaxTokens(value: Int): GenerationParams =
        withContextSize(value.coerceIn(MIN_MAX_TOKENS, MAX_MAX_TOKENS) * 2)

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

    // The stored context is authoritative for legacy, independently saved slider values.
    fun normalizedForSettings(): GenerationParams {
        val paired = withContextSize(contextSize)
        return paired.copy(
            topK = paired.topK.coerceIn(MIN_TOP_K, MAX_TOP_K),
            batchSize = paired.normalizedManualBatchSize(paired.contextSize),
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
        const val MAX_CONTEXT_SIZE = 8192
        const val CONTEXT_STEP = 512
        const val MIN_MAX_TOKENS = MIN_CONTEXT_SIZE / 2
        const val MAX_MAX_TOKENS = MAX_CONTEXT_SIZE / 2
        const val SLIDER_STEPS = (MAX_CONTEXT_SIZE - MIN_CONTEXT_SIZE) / CONTEXT_STEP - 1
        const val MIN_TOP_K = 1
        const val MAX_TOP_K = 100
        const val AUTO_BATCH_LIMIT = 512
        val MANUAL_BATCH_SIZES: List<Int> = listOf(64, 128, 256, 512, 1024)
    }
}
