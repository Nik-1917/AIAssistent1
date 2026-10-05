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
import com.example.aiassistent1.domain.model.AutomaticContextPolicy
import com.example.aiassistent1.domain.model.AutomaticGenerationState
import com.example.aiassistent1.domain.model.GenerationTask
import com.example.aiassistent1.domain.model.GenerationStopReason
import com.example.aiassistent1.domain.model.ModelContextLimits
import com.example.aiassistent1.domain.model.PromptCapacityException
import com.example.aiassistent1.domain.model.ContextCapacityReason
import com.example.aiassistent1.domain.model.ContextResponseRatio
import com.llamatik.library.platform.GenStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.io.File
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
    ) : this(modelProvider, initialParams, Executors.newSingleThreadExecutor(),
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
    private var automaticSession = false
    private val taskContexts = mutableMapOf<GenerationTask, Int>()
    private val mutableAutomaticState = MutableStateFlow<AutomaticGenerationState?>(null)
    override val automaticState = mutableAutomaticState.asStateFlow()
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
                    if (automaticSession && state.value is ModelState.Ready && loadedModelPath == modelPath) {
                        return@runCatching
                    }
                    // A working allocation is not a new allocation. Preserve its proven configuration.
                    if (state.value is ModelState.Ready && loadedModelPath == modelPath &&
                        loadedConfiguration == NativeConfiguration(runtimeParams, processorCount)) {
                        applyRuntimeParams()
                        return@runCatching
                    }
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
                    runtime.beginRequest()
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

    override suspend fun countTokens(messages: List<ChatMessage>): Int = modelMutex.withLock {
        withContext(engineDispatcher) {
            val path = modelProvider.getModelPath().getOrThrow()
            synchronized(lifecycleLock) {
                check(!closed) { "Движок закрыт" }
                if (loadedModelPath != null && loadedModelPath != path) releaseModel()
                runtime.beginRequest()
                runtime.prepareTokenizer(path)
                runtime.countTokens(buildPrompt(messages))
            }
        }
    }

    override suspend fun promptTokenBudget(task: GenerationTask): Int = modelMutex.withLock {
        withContext(engineDispatcher) {
            val file = File(modelProvider.getModelPath().getOrThrow())
            synchronized(lifecycleLock) {
                check(!closed) { "Движок закрыт" }
                val candidate = automaticConfiguration(file, params, 0, task, 4096)
                val current = appliedParams
                val usableExisting = task != GenerationTask.CALENDAR && state.value is ModelState.Ready &&
                    loadedModelPath == file.path && current != null && current.gpuLayers == params.gpuLayers &&
                    current.effectiveCpuThreads(processorCount) == params.effectiveCpuThreads(processorCount)
                val context = if (usableExisting) maxOf(candidate.contextSize, minOf(4096, current!!.contextSize))
                    else candidate.contextSize
                AutomaticContextPolicy.promptBudget(context, task)
            }
        }
    }

    /** One request owns its tokenizer, allocation and sampler until natural completion or cancellation. */
    override fun generateForTask(messages: List<ChatMessage>, task: GenerationTask): Flow<String> = channelFlow<AutomaticEmission> {
        modelMutex.withLock {
            check(!closed) { "Движок закрыт" }
            val requestContext = currentCoroutineContext()
            val requestSettings = synchronized(lifecycleLock) { params }
            val path = modelProvider.getModelPath().getOrThrow()
            synchronized(lifecycleLock) {
                if (loadedModelPath != null && loadedModelPath != path) releaseModel()
                requestContext.ensureActive()
                runtime.beginRequest()
            }
            val cancelHook = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { runtime.cancel() }
            }
            try {
                val file = File(path)
                val prompt = buildPrompt(messages)
                val inputTokens: Int
                var effective: GenerationParams
                synchronized(lifecycleLock) {
                    check(!closed) { "Движок закрыт" }
                    runtime.prepareTokenizer(path)
                    inputTokens = runtime.countTokens(prompt)
                    val desired = maxOf(AutomaticContextPolicy.desiredContext(inputTokens, task), taskContexts[task] ?: 0)
                    val current = appliedParams
                    val sameTask = mutableAutomaticState.value?.task == task
                    val canReuse = state.value is ModelState.Ready && loadedModelPath == path &&
                        current != null && sameTask && current.contextSize >= desired &&
                        current.gpuLayers == requestSettings.gpuLayers &&
                        current.effectiveCpuThreads(processorCount) == requestSettings.effectiveCpuThreads(processorCount)
                    effective = if (canReuse) {
                        requestSettings.copy(contextSize = current!!.contextSize,
                            batchSizeAuto = false, batchSize = current.batchSize,
                            trainedContextLength = loadedContextLength, deviceContextLimit = loadedMemoryLimit)
                    } else {
                        val planned = automaticConfiguration(file, requestSettings, inputTokens, task, desired)
                        // If growth is currently unavailable, the already allocated context can still serve the input.
                        if (state.value is ModelState.Ready && loadedModelPath == path && current != null &&
                            (sameTask || planned.deviceContextLimit?.canLoad == false ||
                                AutomaticContextPolicy.availableAnswer(planned.contextSize, inputTokens) < AutomaticContextPolicy.minimumAnswer(task)) &&
                            current.contextSize >= planned.contextSize &&
                            (task != GenerationTask.CALENDAR ||
                                AutomaticContextPolicy.availableAnswer(current.contextSize, inputTokens) >= AutomaticContextPolicy.MINIMUM_ANSWER) &&
                            current.gpuLayers == requestSettings.gpuLayers &&
                            current.effectiveCpuThreads(processorCount) == requestSettings.effectiveCpuThreads(processorCount)) {
                            requestSettings.copy(contextSize = current.contextSize, batchSizeAuto = false,
                                batchSize = current.batchSize, trainedContextLength = loadedContextLength,
                                deviceContextLimit = loadedMemoryLimit)
                        } else planned
                    }
                    val available = AutomaticContextPolicy.availableAnswer(effective.contextSize, inputTokens)
                    if (available < AutomaticContextPolicy.minimumAnswer(task)) {
                        val trained = effective.trainedContextLength ?: ModelContextLimits.FALLBACK_CONTEXT_SIZE
                        throw PromptCapacityException(AutomaticContextPolicy.promptBudget(effective.contextSize, task),
                            if (AutomaticContextPolicy.availableAnswer(trained, inputTokens) < AutomaticContextPolicy.minimumAnswer(task))
                                ContextCapacityReason.MODEL_LIMIT else ContextCapacityReason.MEMORY)
                    }
                    effective = effective.copy(maxTokens = available)
                    automaticSession = true
                    generationActive = true
                    val nativeConfig = NativeConfiguration(effective, processorCount)
                    if (state.value !is ModelState.Ready || loadedModelPath != path || loadedConfiguration != nativeConfig) {
                        val previousContext = loadedConfiguration?.contextSize
                        mutableState.value = ModelState.Loading
                        val before = memoryGuard?.beforeLoad()
                        runtime.updateParams(effective, effective.effectiveCpuThreads(processorCount), effective.batchSize)
                        var loaded = runtime.load(path)
                        // A failed larger allocation is retried with a small batch and the smallest usable context.
                        if (!loaded) {
                            requestContext.ensureActive()
                            val minimum = AutomaticContextPolicy.minimumContext(inputTokens, task)
                            val metadataCap = GgufMetadataReader.readContextLength(file) ?: ModelContextLimits.FALLBACK_CONTEXT_SIZE
                            check(minimum <= metadataCap) { "Запрос превышает предел контекста модели" }
                            effective = effective.copy(contextSize = minimum, batchSize = 64,
                                maxTokens = AutomaticContextPolicy.availableAnswer(minimum, inputTokens))
                            runtime.updateParams(effective, effective.effectiveCpuThreads(processorCount), 64)
                            loaded = runtime.load(path)
                        }
                        if (!loaded && task != GenerationTask.CALENDAR) {
                            // The failed load may have freed the old native context. Release its RAM credit.
                            // A smaller prompt can retry a smaller allocation, never the same failing budget.
                            val retryContext = minOf(previousContext ?: effective.contextSize,
                                effective.contextSize - AutomaticContextPolicy.STEP).coerceAtLeast(0)
                            releaseModel()
                            throw PromptCapacityException(AutomaticContextPolicy.promptBudget(retryContext, task))
                        }
                        check(loaded) { "Не удалось выделить память даже для минимальной конфигурации модели" }
                        loadedModelPath = path
                        loadedConfiguration = NativeConfiguration(effective, processorCount)
                        loadedContextLength = GgufMetadataReader.readContextLength(file)
                        loadedMemoryLimit = effective.deviceContextLimit
                        memoryGuard?.loaded(file, effective, before)
                        mutableState.value = ModelState.Ready
                    }
                    runtime.updateParams(effective, effective.effectiveCpuThreads(processorCount), effective.batchSize)
                    appliedParams = effective
                    taskContexts[task] = effective.contextSize
                    mutableAutomaticState.value = AutomaticGenerationState(task, effective.contextSize,
                        effective.batchSize, inputTokens,
                        when (task) {
                            GenerationTask.CALENDAR -> 256
                            GenerationTask.CHAT -> AutomaticContextPolicy.answerReserve(inputTokens)
                            GenerationTask.SUMMARY -> AutomaticContextPolicy.CHAT_SUMMARY_ANSWER
                        },
                        AutomaticContextPolicy.availableAnswer(effective.contextSize, inputTokens))
                }
                currentCoroutineContext().ensureActive()
                var callbackError: String? = null
                val stream = object : GenStream {
                    override fun onDelta(text: String) {
                        if (trySendBlocking(AutomaticEmission.Text(text)).isFailure) runtime.cancel()
                    }
                    override fun onComplete() = Unit
                    override fun onError(message: String) { callbackError = message }
                }
                runtime.generateStream(prompt, stream)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    callbackError?.let { error(it) }
                    val result = runtime.lastGeneration
                    mutableAutomaticState.value = mutableAutomaticState.value?.copy(
                        generatedTokens = result?.generatedTokens ?: 0, stopReason = result?.reason)
                    if (result == null || result.reason == GenerationStopReason.EOS) break
                    if (result.reason == GenerationStopReason.CANCELLED) throw CancellationException("Генерация остановлена")
                    val nextDesired = Math.addExact(effective.contextSize,
                        if (task == GenerationTask.CHAT) 2048 else 512)
                    val next = synchronized(lifecycleLock) {
                        automaticConfiguration(file, requestSettings, inputTokens, task, nextDesired)
                    }
                    check(next.contextSize > effective.contextSize) {
                        "Ответ достиг доступного предела контекста. Сохранённая часть ответа не завершена."
                    }
                    currentCoroutineContext().ensureActive()
                    mutableState.value = ModelState.Loading
                    val resized = runtime.resizeContext(next.contextSize, next.batchSize, next.effectiveCpuThreads(processorCount))
                    check(resized) { "Не удалось расширить контекст. Сохранённая часть ответа не завершена." }
                    effective = next.copy(maxTokens = AutomaticContextPolicy.availableAnswer(next.contextSize,
                        inputTokens + result.generatedTokens))
                    synchronized(lifecycleLock) {
                        check(!closed) { "Движок закрыт" }
                        loadedConfiguration = NativeConfiguration(effective, processorCount)
                        loadedMemoryLimit = next.deviceContextLimit
                        appliedParams = effective
                        taskContexts[task] = effective.contextSize
                        runtime.updateParams(effective, effective.effectiveCpuThreads(processorCount), effective.batchSize)
                        memoryGuard?.loaded(file, effective, null)
                        mutableState.value = ModelState.Ready
                        mutableAutomaticState.value = mutableAutomaticState.value?.copy(
                            contextSize = effective.contextSize, batchSize = effective.batchSize,
                            availableAnswerTokens = AutomaticContextPolicy.availableAnswer(effective.contextSize, inputTokens),
                            stopReason = null)
                    }
                    runtime.continueStream(stream)
                }
            } catch (cancelled: CancellationException) {
                synchronized(lifecycleLock) {
                    if (state.value is ModelState.Loading) releaseModel()
                    else mutableAutomaticState.value = mutableAutomaticState.value?.copy(stopReason = GenerationStopReason.CANCELLED)
                }
                throw cancelled
            } catch (error: Exception) {
                if (error !is PromptCapacityException) synchronized(lifecycleLock) {
                    // A failed native allocation cannot remain advertised as a ready context.
                    if (!closed && state.value is ModelState.Loading) {
                        releaseModel()
                        mutableState.value = ModelState.Error(error.toUserMessage())
                    }
                }
                // Deliver the failure after all prior deltas, so channel cancellation cannot discard the tail.
                send(AutomaticEmission.Failed(error))
            } finally {
                cancelHook.cancel()
                synchronized(lifecycleLock) { generationActive = false }
            }
        }
    }.flowOn(engineDispatcher).map { event ->
        when (event) {
            is AutomaticEmission.Text -> event.value
            is AutomaticEmission.Failed -> throw event.error
        }
    }

    private sealed interface AutomaticEmission {
        data class Text(val value: String) : AutomaticEmission
        data class Failed(val error: Exception) : AutomaticEmission
    }

    private fun automaticConfiguration(file: File, settings: GenerationParams, promptTokens: Int,
        task: GenerationTask, desired: Int): GenerationParams {
        val trained = GgufMetadataReader.readContextLength(file) ?: ModelContextLimits.FALLBACK_CONTEXT_SIZE
        val preferred = AutomaticContextPolicy.preferredBatch(promptTokens, task)
        var best: GenerationParams? = null
        for (batch in listOf(512, 256, 128, 64).filter { it <= preferred }) {
            val request = settings.copy(batchSizeAuto = false, batchSize = batch,
                trainedContextLength = trained, contextResponseRatio = ContextResponseRatio.FOUR_TO_ONE)
            val assessment = memoryGuard?.assess(file, request)
            // Estimates guide sizing. An uncertain estimate still gets one minimal native allocation attempt.
            val cap = if (assessment == null) trained else
                if (assessment.canLoad) minOf(trained, assessment.maximumContext) else minOf(trained, 512)
            val context = minOf(desired, cap).coerceAtLeast(1)
            val candidate = request.copy(contextSize = context, deviceContextLimit = assessment,
                maxTokens = AutomaticContextPolicy.availableAnswer(context, promptTokens))
            if (context >= desired) return candidate
            if (best == null || context > best.contextSize) best = candidate
        }
        return requireNotNull(best)
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

    // Cancellation is an atomic native signal; it must not wait behind model loading's lifecycle lock.
    override fun cancelGeneration() {
        if (!closed) runtime.cancel()
    }

    override fun updateParams(params: GenerationParams) = synchronized(lifecycleLock) {
        // Cancelled work can still restore its parameters in finally after onCleared.
        if (closed) return
        this.params = params
        // Settings may be saved during a request. Its native parameters remain unchanged.
        if (!automaticSession && !generationActive && state.value is ModelState.Ready && loadedConfiguration == NativeConfiguration(runtimeParams, processorCount)) {
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
                automaticSession = false
                taskContexts.clear()
                mutableAutomaticState.value = null
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
