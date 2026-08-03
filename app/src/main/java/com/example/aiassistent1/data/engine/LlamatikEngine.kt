package com.example.aiassistent1.data.engine

import com.example.aiassistent1.domain.interfaces.LLMEngine
import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.ModelState
import com.llamatik.library.platform.LlamaBridge
import com.llamatik.library.platform.GenStream
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LlamatikEngine(
    private val modelProvider: ModelProvider,
    private val params: GenerationParams = GenerationParams(),
) : LLMEngine {
    private val modelMutex = Mutex()
    private val executor = Executors.newFixedThreadPool(threadCount)
    private val engineDispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val mutableState = MutableStateFlow<ModelState>(ModelState.Unloaded)

    override val state: StateFlow<ModelState> = mutableState.asStateFlow()

    override suspend fun ensureLoaded(): Result<Unit> = modelMutex.withLock {
        if (state.value is ModelState.Ready) {
            return Result.success(Unit)
        }

        mutableState.value = ModelState.Loading
        withContext(engineDispatcher) {
            runCatching {
                val modelPath = modelProvider.getModelPath().getOrThrow()
                LlamaBridge.updateGenerateParams(
                    temperature = params.temperature,
                    maxTokens = params.maxTokens,
                    topP = params.topP,
                    topK = params.topK,
                    repeatPenalty = params.repeatPenalty,
                    contextLength = params.contextSize,
                    numThreads = threadCount,
                    useMmap = true,
                    flashAttention = true,
                    batchSize = NATIVE_BATCH_SIZE,
                    gpuLayers = params.gpuLayers,
                )
                check(LlamaBridge.initGenerateModel(modelPath)) { "Не удалось загрузить модель" }
                mutableState.value = ModelState.Ready
            }.onFailure { error ->
                LlamaBridge.shutdown()
                mutableState.value = ModelState.Error(error.toUserMessage())
            }
        }
    }

    override fun generate(messages: List<ChatMessage>): Flow<String> = callbackFlow {
        check(state.value is ModelState.Ready) { "Модель не загружена" }
        val buffer = StringBuilder()
        var bufferedTokens = 0
        var lastEmissionAtMillis = System.currentTimeMillis()

        fun emitBuffer() {
            if (buffer.isNotEmpty()) {
                trySend(buffer.toString())
                buffer.clear()
                bufferedTokens = 0
                lastEmissionAtMillis = System.currentTimeMillis()
            }
        }

        LlamaBridge.generateStream(buildPrompt(messages), object : GenStream {
            override fun onDelta(text: String) {
                buffer.append(text)
                bufferedTokens += 1
                val elapsedMillis = System.currentTimeMillis() - lastEmissionAtMillis
                if (bufferedTokens == 1 || bufferedTokens >= STREAM_FLUSH_TOKEN_COUNT || elapsedMillis >= MAX_BATCH_DELAY_MILLIS) {
                    emitBuffer()
                }
            }

            override fun onComplete() {
                emitBuffer()
                close()
            }

            override fun onError(message: String) {
                close(IllegalStateException(message))
            }
        })

        awaitClose { LlamaBridge.nativeCancelGenerate() }
    }.flowOn(engineDispatcher)

    override fun cancelGeneration() {
        LlamaBridge.nativeCancelGenerate()
    }

    override fun close() {
        cancelGeneration()
        LlamaBridge.shutdown()
        executor.shutdown()
        mutableState.value = ModelState.Unloaded
    }

    private fun buildPrompt(messages: List<ChatMessage>): String {
        return buildString {
            append("<|im_start|>system\n")
            append("""
Ты — AI-ассистент с доступом к календарю и контактам. 
Отвечай ТОЛЬКО JSON. 

СТРУКТУРА ОТВЕТА:
{
  "intent": "<тип_запроса>",
  "reply": "<текст_для_пользователя>",
  "params": { ... }
}

ДОСТУПНЫЕ INTENTS И ИХ PARAMS:

1. calendar_search — найти события в календаре
   params: { "query": "ключевое слово", "days": число }
   Пример: пользователь спросил "Когда встреча с Михаилом?"
   Ответ: {"intent":"calendar_search","reply":"Ищу встречи...","params":{"query":"Михаил","days":7}}

2. calendar_add — добавить событие
   params: { "title": "название", "date": "2026-08-05T15:00", "duration_min": 60 }
   
3. send_email — отправить письмо
   params: { "to": "email", "subject": "тема", "body": "текст" }

4. get_contact — найти контакт
   params: { "name": "имя" }

5. chat — обычный разговор
   params: {}

ЕСЛИ НЕ ХВАТАЕТ ДАННЫХ — спроси в reply, intent = "chat".
НЕ ВЫДУМЫВАЙ даты и email. Если их нет в запросе — уточни.
""".trimIndent())
            append("<|im_end|>\n")
            messages.forEach { message ->
                val role = when (message.role) {
                    com.example.aiassistent1.domain.model.MessageRole.USER -> "user"
                    com.example.aiassistent1.domain.model.MessageRole.ASSISTANT -> "assistant"
                    com.example.aiassistent1.domain.model.MessageRole.SYSTEM -> "system"
                }
                append("<|im_start|>$role\n")
                append(message.content)
                append("\n<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
        }
    }

    private fun Throwable.toUserMessage(): String = when (this) {
        is OutOfMemoryError -> "Недостаточно памяти для модели"
        else -> message ?: "Ошибка загрузки модели"
    }

    private companion object {
        val threadCount = Runtime.getRuntime().availableProcessors().let { cores ->
            when {
                cores <= 4 -> cores
                cores <= 8 -> 4
                else -> 6
            }
        }
        const val NATIVE_BATCH_SIZE = 1024
        const val STREAM_FLUSH_TOKEN_COUNT = 1
        const val MAX_BATCH_DELAY_MILLIS = 50L
    }
}