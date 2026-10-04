package com.example.aiassistent1.domain.model

enum class GenerationTask { CALENDAR, CHAT, SUMMARY }

enum class GenerationStopReason { EOS, CONTEXT_LIMIT, CANCELLED }

class PromptCapacityException(val promptBudget: Int) :
    IllegalStateException("Полный запрос не помещается в доступный контекст; требуется обработка по частям.")

data class GenerationResult(
    val reason: GenerationStopReason,
    val promptTokens: Int,
    val generatedTokens: Int,
    val contextSize: Int,
)

data class AutomaticGenerationState(
    val task: GenerationTask,
    val contextSize: Int,
    val batchSize: Int,
    val promptTokens: Int,
    val initialAnswerReserve: Int,
    val availableAnswerTokens: Int,
    val generatedTokens: Int = 0,
    val stopReason: GenerationStopReason? = null,
)

/** The reserve chooses an allocation; it is never a limit on generated output. */
object AutomaticContextPolicy {
    const val STEP = 512
    const val TECHNICAL_RESERVE = 16
    const val MINIMUM_ANSWER = 64
    const val MINIMUM_BATCH = 64

    fun promptBudget(context: Int): Int =
        (context - minOf(512, context / 4) - TECHNICAL_RESERVE).coerceAtLeast(0)

    fun answerReserve(promptTokens: Int): Int {
        require(promptTokens >= 0)
        return roundUp(maxOf(STEP.toLong(), promptTokens.toLong()))
    }

    fun desiredContext(promptTokens: Int, task: GenerationTask): Int {
        require(promptTokens >= 0)
        return when (task) {
            GenerationTask.CHAT -> Math.multiplyExact(answerReserve(promptTokens), 4)
            GenerationTask.CALENDAR -> roundUp(promptTokens.toLong() + 256 + TECHNICAL_RESERVE)
            GenerationTask.SUMMARY -> roundUp(maxOf(2048L, promptTokens.toLong() + STEP + TECHNICAL_RESERVE))
        }
    }

    fun minimumContext(promptTokens: Int): Int =
        roundUp(promptTokens.toLong() + MINIMUM_ANSWER + TECHNICAL_RESERVE)

    fun availableAnswer(context: Int, promptTokens: Int): Int =
        (context.toLong() - promptTokens - TECHNICAL_RESERVE).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    fun preferredBatch(promptTokens: Int, task: GenerationTask): Int = when {
        task == GenerationTask.CALENDAR || promptTokens <= STEP -> 64
        promptTokens <= 1024 -> 128
        promptTokens <= 4096 -> 256
        else -> 512
    }

    private fun roundUp(tokens: Long): Int =
        Math.toIntExact(Math.multiplyExact((tokens + STEP - 1) / STEP, STEP.toLong()))
}
