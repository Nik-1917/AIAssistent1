package com.example.aiassistent1.domain.usecase

import com.example.aiassistent1.domain.context.ModelContextBuilder
import com.example.aiassistent1.domain.interfaces.ChatContextRepository
import com.example.aiassistent1.domain.interfaces.LLMEngine
import com.example.aiassistent1.domain.model.*
import com.example.aiassistent1.domain.provider.SystemPromptProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/** One immutable request. Waiting for the user happens after the engine has released its mutex. */
class ChatContextSession(
    private val engine: LLMEngine,
    private val repository: ChatContextRepository,
    private val builder: ModelContextBuilder = ModelContextBuilder(),
    private val systemPrompt: SystemPromptProvider = SystemPromptProvider(),
) {
    fun respond(
        chatId: String,
        history: List<ChatMessage>,
        onReady: suspend () -> Unit = {},
        onPressure: suspend (ChatContextPressure) -> ChatContextChoice,
    ) = flow {
        require(chatId != "calendar")
        val messages = history.filter { it.chatId == chatId }.toList()
        val turns = ChatContextHistory.turns(messages)
        val requestId = requireNotNull(turns.lastOrNull()?.id) { "Нет запроса пользователя" }
        val settings = repository.observe(chatId).first()
        var policy = settings.policy
        var excluded = settings.excludedTurnIds - requestId
        // Freeze the date/system instruction across counting, selection and retries.
        val system = ChatMessage(role = MessageRole.SYSTEM, content = systemPrompt.getSystemPrompt(false), chatId = chatId)
        fun prompt(ids: Set<String>) = listOf(system) + builder.build(messages,
            appendChatStyleInstruction = true, excludedTurnIds = ids)

        while (true) {
            currentCoroutineContext().ensureActive()
            var emitted = false
            try {
                engine.generateForTask(prompt(excluded), GenerationTask.CHAT, onReady).collect {
                    emitted = true
                    emit(it)
                }
                break
            } catch (capacity: PromptCapacityException) {
                if (emitted) throw capacity // Never restart an answer that has already been displayed.
                val candidates = turns.dropLast(1).filter { it.id !in excluded }
                // Excluding history is useful only if the protected request can use this real budget.
                val currentTokens = engine.countTokens(prompt(turns.dropLast(1).map { it.id }.toSet()))
                if (currentTokens > capacity.promptBudget || candidates.isEmpty()) {
                    val explanation = when (capacity.reason) {
                        ContextCapacityReason.MODEL_LIMIT ->
                            "Даже текущий запрос с системной инструкцией и резервом ответа превышает предел контекста модели. Сократите запрос."
                        ContextCapacityReason.MEMORY ->
                            "По оценке оперативной памяти даже текущий запрос с системной инструкцией и резервом ответа не помещается. Освободите память или выберите модель меньшего размера."
                    }
                    throw IllegalStateException("$explanation Сообщения сохранены.", capacity)
                }
                val choice = if (policy == ChatHistoryPolicy.AUTOMATIC) ChatContextChoice.Automatic()
                    else onPressure(ChatContextPressure(requestId, candidates, capacity.reason))
                currentCoroutineContext().ensureActive()
                val selected = when (choice) {
                    ChatContextChoice.Cancel -> throw CancellationException("Выбор контекста отменён")
                    is ChatContextChoice.Manual -> {
                        require(choice.turnIds.isNotEmpty() && candidates.map { it.id }.containsAll(choice.turnIds))
                        excluded + choice.turnIds
                    }
                    is ChatContextChoice.Automatic -> {
                        // This choice authorizes all reductions of this request. Persistence is opt-in below.
                        policy = ChatHistoryPolicy.AUTOMATIC
                        var reduced = excluded
                        for (turn in candidates) {
                            currentCoroutineContext().ensureActive()
                            reduced = reduced + turn.id
                            // Count the complete real prompt after EACH excluded turn.
                            if (engine.countTokens(prompt(reduced)) <= capacity.promptBudget) break
                        }
                        reduced
                    }
                }
                // Manual selections are also tokenized before the next allocation attempt.
                if (choice is ChatContextChoice.Manual) engine.countTokens(prompt(selected))
                excluded = selected
                val remember = when (choice) {
                    is ChatContextChoice.Automatic -> choice.remember
                    is ChatContextChoice.Manual -> choice.remember
                    ChatContextChoice.Cancel -> false
                }
                repository.update(chatId, excludedTurnIds = excluded,
                    policy = if (remember) ChatHistoryPolicy.AUTOMATIC else null)
                // Re-enter the engine: fresh RAM, exact tokens and the 1024 guard are checked again.
            }
        }
    }
}
