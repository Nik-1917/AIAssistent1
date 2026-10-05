package com.example.aiassistent1.domain.model

enum class GenerationTask { CALENDAR, CHAT, SUMMARY }

enum class GenerationStopReason { EOS, CONTEXT_LIMIT, CANCELLED }

enum class ContextCapacityReason { MEMORY, MODEL_LIMIT }

class PromptCapacityException(
    val promptBudget: Int,
    val reason: ContextCapacityReason = ContextCapacityReason.MEMORY,
) :
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
    const val CHAT_SUMMARY_ANSWER = 1024
    const val MINIMUM_BATCH = 64

    fun minimumAnswer(task: GenerationTask): Int =
        if (task == GenerationTask.CALENDAR) MINIMUM_ANSWER else CHAT_SUMMARY_ANSWER

    fun promptBudget(context: Int, task: GenerationTask = GenerationTask.CALENDAR): Int =
        (context - (if (task == GenerationTask.CALENDAR) minOf(512, context / 4)
            else CHAT_SUMMARY_ANSWER) - TECHNICAL_RESERVE).coerceAtLeast(0)

    fun answerReserve(promptTokens: Int): Int {
        require(promptTokens >= 0)
        return roundUp(maxOf(STEP.toLong(), promptTokens.toLong()))
    }

    fun desiredContext(promptTokens: Int, task: GenerationTask): Int {
        require(promptTokens >= 0)
        return when (task) {
            GenerationTask.CHAT -> Math.multiplyExact(answerReserve(promptTokens), 4)
            GenerationTask.CALENDAR -> roundUp(promptTokens.toLong() + 256 + TECHNICAL_RESERVE)
            GenerationTask.SUMMARY -> roundUp(maxOf(2048L, promptTokens.toLong() + CHAT_SUMMARY_ANSWER + TECHNICAL_RESERVE))
        }
    }

    fun minimumContext(promptTokens: Int, task: GenerationTask = GenerationTask.CALENDAR): Int =
        roundUp(promptTokens.toLong() + minimumAnswer(task) + TECHNICAL_RESERVE)

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
