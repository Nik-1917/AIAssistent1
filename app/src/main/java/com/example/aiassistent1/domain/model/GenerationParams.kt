package com.example.aiassistent1.domain.model

data class GenerationParams(
    val contextSize: Int = DEFAULT_CONTEXT_SIZE,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val temperature: Float = 0.36f,
    val topP: Float = 0.9f,
    val topK: Int = 20,
    val repeatPenalty: Float = 1.15f,
    val gpuLayers: Int = 0,
) {
    /** Settings use a shared 16-position grid; temporary engine parameters remain independent. */
    fun withContextSize(value: Int): GenerationParams {
        val bounded = value.coerceIn(MIN_CONTEXT_SIZE, MAX_CONTEXT_SIZE)
        val context = ((bounded + CONTEXT_STEP / 2) / CONTEXT_STEP) * CONTEXT_STEP
        return copy(contextSize = context, maxTokens = context / 2)
    }

    fun withMaxTokens(value: Int): GenerationParams =
        withContextSize(value.coerceIn(MIN_MAX_TOKENS, MAX_MAX_TOKENS) * 2)

    // The stored context is authoritative for legacy, independently saved slider values.
    fun normalizedForSettings(): GenerationParams = withContextSize(contextSize)

    companion object {
        const val DEFAULT_CONTEXT_SIZE = 512
        const val DEFAULT_MAX_TOKENS = 256
        const val MIN_CONTEXT_SIZE = 512
        const val MAX_CONTEXT_SIZE = 8192
        const val CONTEXT_STEP = 512
        const val MIN_MAX_TOKENS = MIN_CONTEXT_SIZE / 2
        const val MAX_MAX_TOKENS = MAX_CONTEXT_SIZE / 2
        const val SLIDER_STEPS = (MAX_CONTEXT_SIZE - MIN_CONTEXT_SIZE) / CONTEXT_STEP - 1
    }
}
