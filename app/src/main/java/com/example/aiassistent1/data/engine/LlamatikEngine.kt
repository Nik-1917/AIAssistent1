package com.example.aiassistent1.data.engine

import com.example.aiassistent1.domain.interfaces.LLMEngine
import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.data.model.GgufMetadataReader
import com.example.aiassistent1.data.model.ModelMemoryGuard
import com.example.aiassistent1.domain.model.DeviceContextLimit
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.CpuThreadSettings
import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.ModelState
import com.llamatik.library.platform.GenStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.io.File
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
    private val processorCount: Int = CpuThreadSettings.availableProcessors,
    private val memoryGuard: ModelMemoryGuard? = null,
) : LLMEngine {
    constructor(
        modelProvider: ModelProvider,
        memoryGuard: ModelMemoryGuard,
        initialParams: GenerationParams = GenerationParams(),
    ) : this(modelProvider, initialParams, Executors.newFixedThreadPool(CpuThreadSettings.automaticThreadCount()),
        NativeLlamaRuntime, memoryGuard = memoryGuard)

    private val modelMutex = Mutex()
    private val lifecycleLock = Any()
    private val engineDispatcher = executor.asCoroutineDispatcher()
    private val mutableState = MutableStateFlow<ModelState>(ModelState.Unloaded)
    private var params = initialParams
    private var loadedConfiguration: NativeConfiguration? = null
    private var appliedParams: GenerationParams? = null
    private var loadedContextLength: Int? = null
    private var loadedMemoryLimit: DeviceContextLimit? = null
    private var loadedModelPath: String? = null
    private val runtimeParams: GenerationParams get() = params.boundedForRuntime(loadedContextLength, loadedMemoryLimit)
    private var generationActive = false
    @Volatile private var closed = false

    override val state: StateFlow<ModelState> = mutableState.asStateFlow()

    override suspend fun ensureLoaded(): Result<Unit> = modelMutex.withLock {
        synchronized(lifecycleLock) {
            if (closed) return Result.failure(IllegalStateException("Движок закрыт"))
        }

        withContext(engineDispatcher) {
            runCatching {
                val modelPath = modelProvider.getModelPath().getOrThrow()
                val file = File(modelPath)
                val contextLength = GgufMetadataReader.readContextLength(file)
                synchronized(lifecycleLock) {
                    check(!closed) { "Движок закрыт" }
                    // Recheck RAM even when reusing a loaded context; caller-provided limits are untrusted.
                    val assessment = memoryGuard?.assess(file, params)
                    check(assessment?.canLoad != false) { assessment?.explanation.orEmpty() }
                    check(params.contextSize >= 2) { "Контекст недоступен. Дождитесь обновления настроек памяти и повторите запрос." }
                    loadedContextLength = contextLength
                    loadedMemoryLimit = assessment
                    if (state.value is ModelState.Ready && loadedModelPath == modelPath &&
                        loadedConfiguration == NativeConfiguration(runtimeParams, processorCount)) {
                        applyRuntimeParams()
                        return@runCatching
                    }
                    // Batch/context/CPU settings take effect at native context creation, not in the setter.
                    if (loadedConfiguration != null) releaseModel()
                    loadedContextLength = contextLength
                    // Assess after unloading, without counting the old allocation as free twice.
                    loadedMemoryLimit = memoryGuard?.assess(file, params)
                    check(loadedMemoryLimit?.canLoad != false) { loadedMemoryLimit?.explanation.orEmpty() }
                    mutableState.value = ModelState.Loading
                    val memoryBefore = memoryGuard?.beforeLoad()
                    applyRuntimeParams()
                    check(runtime.load(modelPath)) { "Не удалось загрузить модель" }
                    loadedConfiguration = NativeConfiguration(runtimeParams, processorCount)
                    loadedModelPath = modelPath
                    memoryGuard?.loaded(file, runtimeParams, memoryBefore)
                    mutableState.value = ModelState.Ready
                }
            }.onFailure { error ->
                synchronized(lifecycleLock) {
                    if (!closed) {
                        try {
                            runtime.shutdown()
                        } finally {
                            loadedConfiguration = null
                            appliedParams = null
                            loadedContextLength = null
                            loadedMemoryLimit = null
                            loadedModelPath = null
                            memoryGuard?.unloaded()
                            mutableState.value = ModelState.Error(error.toUserMessage())
                        }
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
        // Keep the native context alive through streaming and its cancellation cleanup.
        modelMutex.withLock {
            synchronized(lifecycleLock) {
                check(!closed) { "Движок закрыт" }
                check(state.value is ModelState.Ready) { "Модель не загружена" }
                generationActive = true
            }
            try {
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
            } finally {
                synchronized(lifecycleLock) { generationActive = false }
            }
        }
    }.flowOn(engineDispatcher)

    override fun cancelGeneration() = synchronized(lifecycleLock) {
        if (!closed) runtime.cancel()
    }

    override fun updateParams(params: GenerationParams) = synchronized(lifecycleLock) {
        // Cancelled work can still restore its parameters in finally after onCleared.
        if (closed) return
        this.params = params
        // Settings may be saved during a request. Its native parameters remain unchanged.
        if (!generationActive && state.value is ModelState.Ready && loadedConfiguration == NativeConfiguration(runtimeParams, processorCount)) {
            applyRuntimeParams()
        }
    }

    /** Call only with lifecycleLock held. Reloads and repeated identical updates are avoided. */
    private fun applyRuntimeParams() {
        val effective = runtimeParams
        if (appliedParams != effective) {
            runtime.updateParams(effective, effective.effectiveCpuThreads(processorCount), effective.effectiveBatchSize)
            appliedParams = effective
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
                loadedConfiguration = null
                appliedParams = null
                loadedContextLength = null
                loadedMemoryLimit = null
                loadedModelPath = null
                memoryGuard?.unloaded()
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

    private data class NativeConfiguration(
        val contextSize: Int,
        val batchSize: Int,
        val gpuLayers: Int,
        val cpuThreads: Int,
    ) {
        constructor(params: GenerationParams, processorCount: Int) : this(
            params.contextSize, params.effectiveBatchSize, params.gpuLayers,
            params.effectiveCpuThreads(processorCount),
        )
    }

    private companion object {
        const val STREAM_FLUSH_TOKEN_COUNT = 1
        const val MAX_BATCH_DELAY_MILLIS = 50L
    }
}
