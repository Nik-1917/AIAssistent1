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
                    flashAttention = false,
                    batchSize = TOKEN_BATCH_SIZE,
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
                if (bufferedTokens == 1 || bufferedTokens >= TOKEN_BATCH_SIZE || elapsedMillis >= MAX_BATCH_DELAY_MILLIS) {
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
        val templateMessages = messages.map { it.role.name.lowercase() to it.content }
        return LlamaBridge.applyChatTemplate(templateMessages, addAssistantPrefix = true)
            ?: templateMessages.joinToString(separator = "\n") { (role, content) -> "$role: $content" }
                .plus("\nassistant:")
    }

    private fun Throwable.toUserMessage(): String = when (this) {
        is OutOfMemoryError -> "Недостаточно памяти для модели"
        else -> message ?: "Ошибка загрузки модели"
    }

    private companion object {
        val threadCount = maxOf(1, Runtime.getRuntime().availableProcessors() - 1)
        const val TOKEN_BATCH_SIZE = 4
        const val MAX_BATCH_DELAY_MILLIS = 50L
    }
}