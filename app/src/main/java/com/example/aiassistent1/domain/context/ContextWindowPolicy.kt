package com.example.aiassistent1.domain.context

import com.example.aiassistent1.domain.model.GenerationParams

/** Считается весь сформированный запрос: шаблон, системный текст и выбранная история. */
data class ContextBudget(
    val promptTokens: Int,
    val responseTokens: Int,
    val reserveTokens: Int,
    val contextSize: Int,
    val userMessageTokens: Int? = null,
) {
    val requiredTokens: Int get() = promptTokens + responseTokens + reserveTokens
}

class ContextCapacityException(val required: Long, val limit: Int) : IllegalArgumentException(
    "Запрос вместе с историей и запасом для ответа требует $required токенов, " +
        "доступно $limit. Увеличьте максимальный контекст в настройках или сократите сообщение. Текст сохранён.",
)

object ContextWindowPolicy {
    // Llamatik 1.10.1 оставляет 16 токенов при генерации; дополнительно сохраняем ещё 112.
    const val SAFETY_TOKENS = 128
    const val SMALL_MESSAGE_MAX_TOKENS = 322
    // Включает внутренние 16 токенов Llamatik и ещё 16 для защиты границы.
    const val SMALL_MESSAGE_SAFETY_TOKENS = 32

    fun plan(
        promptTokens: Int,
        params: GenerationParams,
        modelContextLimit: Int,
        userMessageTokens: Int? = null,
    ): ContextBudget {
        require(promptTokens >= 0) { "Не удалось подсчитать токены запроса" }
        require(modelContextLimit > 0) { "Не удалось определить размер контекста модели" }
        require(userMessageTokens == null || userMessageTokens >= 0) { "Не удалось подсчитать токены сообщения" }
        val reserve = if (userMessageTokens != null && userMessageTokens <= SMALL_MESSAGE_MAX_TOKENS)
            SMALL_MESSAGE_SAFETY_TOKENS else SAFETY_TOKENS
        val settings = params.normalized()
        val configuredLimit = if (settings.autoContextEnabled) settings.maxContextSize else settings.contextSize
        val limit = GenerationParams.CONTEXT_SIZES.lastOrNull { it <= configuredLimit && it <= modelContextLimit } ?: 0
        // Ответ растёт вместе с контекстом: проверяем полный бюджет заново на каждой ступени.
        for (target in GenerationParams.CONTEXT_SIZES) {
            if (target < settings.contextSize || target > limit) continue
            val responseTokens = settings.responseTokensFor(target)
            val required = promptTokens.toLong() + responseTokens + reserve
            if (required <= target) return ContextBudget(promptTokens, responseTokens, reserve, target, userMessageTokens)
        }
        val required = promptTokens.toLong() + settings.responseTokensFor(maxOf(limit, settings.contextSize)) + reserve
        throw ContextCapacityException(maxOf(required, settings.contextSize.toLong()), limit)
    }
}
