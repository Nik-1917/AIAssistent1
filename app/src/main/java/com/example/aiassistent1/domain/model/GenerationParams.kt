package com.example.aiassistent1.domain.model

data class GenerationParams(
    /** Постоянный минимум: временное увеличение контекста запроса сюда не записывается. */
    val contextSize: Int = 1024,
    val temperature: Float = 0.35f,
    val topP: Float = 0.9f,
    val topK: Int = 20,
    val repeatPenalty: Float = 1.15f,
    val gpuLayers: Int = 0,
    val autoContextEnabled: Boolean = true,
    val maxContextSize: Int = 8192,
    val maxMessageLength: Int = DEFAULT_MESSAGE_LENGTH,
    /** Только для внутренних сценариев с фиксированным лимитом, например выжимки конференции. */
    val fixedResponseTokens: Int? = null,
) {
    val maxTokens: Int get() = responseTokensFor(contextSize)

    fun responseTokensFor(workingContext: Int): Int = fixedResponseTokens ?: workingContext / 2

    fun normalized(): GenerationParams {
        val minimum = normalizeContextSize(contextSize)
        return copy(
            contextSize = minimum,
            maxContextSize = normalizeContextSize(maxContextSize).coerceAtLeast(minimum),
            fixedResponseTokens = fixedResponseTokens?.coerceIn(64, MAX_CONTEXT_SIZE / 2),
            maxMessageLength = maxMessageLength.coerceIn(500, 12000),
        )
    }

    companion object {
        const val DEFAULT_MESSAGE_LENGTH = 3000
        const val MAX_CONTEXT_SIZE = 8192
        val CONTEXT_SIZES = listOf(1024, 2048, 4096, 8192)

        fun normalizeContextSize(value: Int): Int =
            CONTEXT_SIZES.first { it >= value.coerceIn(CONTEXT_SIZES.first(), MAX_CONTEXT_SIZE) }
    }
}
