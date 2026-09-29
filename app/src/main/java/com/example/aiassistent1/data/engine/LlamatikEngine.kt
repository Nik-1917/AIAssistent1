package com.example.aiassistent1.data.engine

import com.example.aiassistent1.domain.context.ContextBudget
import com.example.aiassistent1.domain.context.ContextWindowPolicy
import com.example.aiassistent1.domain.interfaces.LLMEngine
import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.ModelState
import com.llamatik.library.platform.GenStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
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
    private val mutableContextBudget = MutableStateFlow<ContextBudget?>(null)
    private val cancellationEpoch = AtomicLong()
    private val operationSequence = AtomicLong()
    @Volatile private var params = initialParams.normalized()
    @Volatile private var closed = false
    private var operationActive = false
    private var activeOperationId = 0L
    private var releaseRequested = false
    private var loadedPath: String? = null
    private var loadedParams: GenerationParams? = null

    override val state: StateFlow<ModelState> = mutableState.asStateFlow()
    override val contextBudget: StateFlow<ContextBudget?> = mutableContextBudget.asStateFlow()

    private data class Request(
        val path: String,
        val prompt: String,
        val params: GenerationParams,
        val budget: ContextBudget,
        val epoch: Long,
    )

    override suspend fun ensureLoaded(): Result<Unit> = engineResult {
        val snapshot = params
        val epoch = cancellationEpoch.get()
        val path = modelProvider.getModelPath().getOrThrow()
        checkRequestActive(epoch)
        loadFor(path, snapshot)
    }

    override suspend fun prepareGeneration(
        messages: List<ChatMessage>,
        userMessageForSizing: String?,
    ): Result<Flow<String>> {
        val snapshot = params
        val prompt = buildPrompt(messages)
        val epoch = cancellationEpoch.get()
        return engineResult { prepareRequest(prompt, snapshot, epoch, userMessageForSizing) }.map { generationFlow(it) }
    }

    override fun generate(messages: List<ChatMessage>): Flow<String> {
        check(!closed) { "Движок закрыт" }
        // Форматирование полей и выжимки используют ту же проверку вместимости.
        return generationFlow(null, buildPrompt(messages), params, cancellationEpoch.get())
    }

    private suspend fun prepareRequest(
        prompt: String,
        snapshot: GenerationParams,
        epoch: Long,
        userMessageForSizing: String? = null,
    ): Request {
        val path = modelProvider.getModelPath().getOrThrow()
        checkRequestActive(epoch)
        val inspection = runtime.inspectPrompt(path, prompt, userMessageForSizing)
        checkRequestActive(epoch)
        val budget = ContextWindowPolicy.plan(inspection.tokenCount, snapshot, inspection.modelContextLimit, inspection.userMessageTokens)
        val request = Request(path, prompt, snapshot.copy(contextSize = budget.contextSize), budget, epoch)
        loadFor(request.path, request.params)
        checkRequestActive(epoch)
        mutableContextBudget.value = budget
        return request
    }

    /** Выполняется под modelMutex: отмена не освобождает используемые нативные объекты. */
    private fun loadFor(path: String, workingParams: GenerationParams) {
        val reload = loadedPath != path || loadedParams?.contextSize != workingParams.contextSize ||
            loadedParams?.gpuLayers != workingParams.gpuLayers
        if (reload) {
            mutableState.value = ModelState.Loading
            mutableContextBudget.value = null
            try {
                if (loadedPath != null) runtime.shutdown()
                loadedPath = null
                loadedParams = null
                runtime.updateParams(workingParams, threadCount, NATIVE_BATCH_SIZE)
                check(runtime.load(path)) { "Не удалось загрузить модель с контекстом ${workingParams.contextSize} токенов" }
                loadedPath = path
                loadedParams = workingParams
                mutableState.value = ModelState.Ready
            } catch (error: Throwable) {
                try { runtime.shutdown() } finally {
                    loadedPath = null
                    loadedParams = null
                    mutableState.value = ModelState.Error(error.toUserMessage())
                }
                throw error
            }
        } else if (loadedParams != workingParams) {
            runtime.updateParams(workingParams, threadCount, NATIVE_BATCH_SIZE)
            loadedParams = workingParams
        }
    }

    private fun generationFlow(
        prepared: Request?,
        prompt: String = "",
        snapshot: GenerationParams = params,
        epoch: Long = cancellationEpoch.get(),
    ): Flow<String> = callbackFlow {
        val operationId = operationSequence.incrementAndGet()
        val terminalEvent = AtomicBoolean(false)
        val worker = launch(engineDispatcher) {
            try {
                engineOperation(operationId) {
                    val request = prepared ?: prepareRequest(prompt, snapshot, epoch)
                    checkRequestActive(request.epoch)
                    // Изменения настроек после проверки применяются только к следующему запросу.
                    loadFor(request.path, request.params)
                    mutableContextBudget.value = request.budget
                    checkRequestActive(request.epoch)
                    runtime.generateStream(request.prompt, object : GenStream {
                        override fun onDelta(text: String) { trySend(text) }
                        override fun onComplete() { terminalEvent.set(true); close() }
                        override fun onError(message: String) {
                            terminalEvent.set(true)
                            close(IllegalStateException(message))
                        }
                    })
                }
                terminalEvent.set(true)
                close()
            } catch (error: Throwable) {
                terminalEvent.set(true)
                close(error)
            }
        }
        // Обработчик отмены доступен, пока нативная генерация блокирует отдельный рабочий поток.
        awaitClose {
            if (!terminalEvent.get()) cancelOperation(operationId)
            worker.cancel()
        }
    }

    private suspend fun <T> engineResult(block: suspend () -> T): Result<T> {
        if (closed) return Result.failure(IllegalStateException("Движок закрыт"))
        return try { Result.success(engineOperation(block = block)) }
        catch (error: CancellationException) { throw error }
        catch (error: OutOfMemoryError) { Result.failure(IllegalStateException(error.toUserMessage(), error)) }
        catch (error: Throwable) { Result.failure(error) }
    }

    private suspend fun <T> engineOperation(operationId: Long = 0L, block: suspend () -> T): T = modelMutex.withLock {
        withContext(engineDispatcher) {
            synchronized(lifecycleLock) {
                check(!closed) { "Движок закрыт" }
                operationActive = true
                activeOperationId = operationId
            }
            try { block() } finally {
                synchronized(lifecycleLock) {
                    operationActive = false
                    activeOperationId = 0L
                    if (releaseRequested) releaseModel()
                }
            }
        }
    }

    private suspend fun checkRequestActive(epoch: Long) {
        currentCoroutineContext().ensureActive()
        check(!closed) { "Движок закрыт" }
        if (cancellationEpoch.get() != epoch) throw CancellationException("Запрос отменён")
    }

    private fun cancelOperation(operationId: Long) = synchronized(lifecycleLock) {
        // Запоздалая очистка завершённого потока не должна отменять следующий запрос.
        if (!closed && operationActive && activeOperationId == operationId) {
            cancellationEpoch.incrementAndGet()
            runtime.cancel()
        }
    }

    override fun cancelGeneration() = synchronized(lifecycleLock) {
        if (!closed) {
            cancellationEpoch.incrementAndGet()
            runtime.cancel()
        }
    }

    override fun updateParams(params: GenerationParams) {
        // Поток UI не изменяет параметры уже выполняющейся нативной генерации.
        if (!closed) this.params = params.normalized()
    }

    override fun unload() = synchronized(lifecycleLock) {
        if (!closed) requestRelease()
    }

    override fun close() = synchronized(lifecycleLock) {
        if (closed) return
        closed = true
        try { requestRelease() } finally { engineDispatcher.close() }
    }

    private fun requestRelease() {
        cancellationEpoch.incrementAndGet()
        if (operationActive) {
            releaseRequested = true
            mutableState.value = ModelState.Unloaded
            mutableContextBudget.value = null
            runtime.cancel()
        } else releaseModel()
    }

    private fun releaseModel() {
        try { runtime.cancel() } finally {
            try { runtime.shutdown() } finally {
                loadedPath = null
                loadedParams = null
                releaseRequested = false
                mutableContextBudget.value = null
                mutableState.value = ModelState.Unloaded
            }
        }
    }

    internal fun buildPrompt(messages: List<ChatMessage>): String = buildString {
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

    private fun Throwable.toUserMessage(): String = when (this) {
        is OutOfMemoryError -> "Недостаточно памяти для выбранного контекста. Уменьшите его максимум в настройках. Текст сохранён."
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
    }
}
