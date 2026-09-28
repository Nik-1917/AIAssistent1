package com.example.aiassistent1.data.engine

import com.example.aiassistent1.domain.interfaces.LLMEngine
import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.ModelState
import com.llamatik.library.platform.GenStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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

class LlamatikEngine internal constructor(
    private val modelProvider: ModelProvider,
    initialParams: GenerationParams,
    executor: ExecutorService,
    private val runtime: LlamaRuntime,
) : LLMEngine {
    constructor(
        modelProvider: ModelProvider,
        initialParams: GenerationParams = GenerationParams(),
    ) : this(modelProvider, initialParams, Executors.newFixedThreadPool(threadCount), NativeLlamaRuntime)

    private val modelMutex = Mutex()
    private val lifecycleLock = Any()
    private val engineDispatcher = executor.asCoroutineDispatcher()
    private val mutableState = MutableStateFlow<ModelState>(ModelState.Unloaded)
    private var params = initialParams
    @Volatile private var closed = false

    override val state: StateFlow<ModelState> = mutableState.asStateFlow()

    override suspend fun ensureLoaded(): Result<Unit> = modelMutex.withLock {
        synchronized(lifecycleLock) {
            if (closed) return Result.failure(IllegalStateException("Движок закрыт"))
            if (state.value is ModelState.Ready) return Result.success(Unit)
            mutableState.value = ModelState.Loading
        }

        withContext(engineDispatcher) {
            runCatching {
                val modelPath = modelProvider.getModelPath().getOrThrow()
                synchronized(lifecycleLock) {
                    check(!closed) { "Движок закрыт" }
                    runtime.updateParams(params, threadCount, NATIVE_BATCH_SIZE)
                    check(runtime.load(modelPath)) { "Не удалось загрузить модель" }
                    mutableState.value = ModelState.Ready
                }
            }.onFailure { error ->
                synchronized(lifecycleLock) {
                    if (!closed) {
                        runtime.shutdown()
                        mutableState.value = ModelState.Error(error.toUserMessage())
                    }
                }
            }
        }
    }

    override fun generate(messages: List<ChatMessage>): Flow<String> {
        check(!closed) { "Движок закрыт" }
        return generationFlow(messages)
    }

    private fun generationFlow(messages: List<ChatMessage>): Flow<String> = callbackFlow {
        check(!closed) { "Движок закрыт" }
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

        runtime.generateStream(buildPrompt(messages), object : GenStream {
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

        awaitClose { cancelGeneration() }
    }.flowOn(engineDispatcher)

    override fun cancelGeneration() = synchronized(lifecycleLock) {
        if (!closed) runtime.cancel()
    }

    override fun updateParams(params: GenerationParams) = synchronized(lifecycleLock) {
        // Cancelled work can still restore its parameters in finally after onCleared.
        if (closed) return
        this.params = params
        if (state.value is ModelState.Ready) {
            runtime.updateParams(params, threadCount, NATIVE_BATCH_SIZE)
        }
    }

    override fun unload() = synchronized(lifecycleLock) {
        if (!closed) releaseModel()
    }

    override fun close() = synchronized(lifecycleLock) {
        if (closed) return
        closed = true
        try {
            releaseModel()
        } finally {
            engineDispatcher.close()
        }
    }

    private fun releaseModel() {
        try {
            runtime.cancel()
        } finally {
            try {
                runtime.shutdown()
            } finally {
                mutableState.value = ModelState.Unloaded
            }
        }
    }

    internal fun buildPrompt(messages: List<ChatMessage>): String {
        return buildString {
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
