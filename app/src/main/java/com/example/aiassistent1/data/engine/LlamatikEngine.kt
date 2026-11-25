package com.example.aiassistent1.data.engine

import com.example.aiassistent1.domain.interfaces.LLMEngine
import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.data.model.GgufMetadataReader
import com.example.aiassistent1.data.model.ModelFileStamp
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
import com.example.aiassistent1.domain.model.ModelAllocationException
import com.example.aiassistent1.domain.model.ModelAllocationFailure
import com.example.aiassistent1.domain.model.MemoryLimitStatus
import com.example.aiassistent1.domain.model.ContextResponseRatio
import com.example.aiassistent1.domain.model.ComputeMode
import com.example.aiassistent1.domain.model.GenerationEvent
import com.example.aiassistent1.domain.model.InferenceExecution
import com.example.aiassistent1.domain.model.InferenceBackend
import com.example.aiassistent1.domain.model.GpuDevice
import com.example.aiassistent1.domain.model.ModelMemoryMetadata
import com.example.aiassistent1.domain.model.ModelMemoryCalculator
import com.example.aiassistent1.domain.model.MemoryRuntimeSettings
import com.llamatik.library.platform.GenStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.io.File
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LlamatikEngine internal constructor(
    private val modelProvider: ModelProvider,
    initialParams: GenerationParams,
    private val executor: ExecutorService,
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

    constructor(modelProvider: ModelProvider, memoryGuard: ModelMemoryGuard, context: android.content.Context,
        initialParams: GenerationParams = GenerationParams()) : this(modelProvider, initialParams,
        Executors.newSingleThreadExecutor(), RemoteLlamaRuntime(context), memoryGuard = memoryGuard)

    private val modelMutex = runtime.requestMutex ?: Mutex()
    private val lifecycleLock = Any()
    private val disposalLock = Any()
    private val engineDispatcher = executor.asCoroutineDispatcher()
    private val mutableState = MutableStateFlow<ModelState>(ModelState.Unloaded)
    @Volatile private var params = initialParams
    private var loadedConfiguration: NativeConfiguration? = null
    private var appliedParams: GenerationParams? = null
    private val cancellationEpoch = AtomicLong()
    private var loadedContextLength: Int? = null
    private var loadedMemoryLimit: DeviceContextLimit? = null
    private var loadedModel: ModelFileStamp? = null
    private val runtimeParams: GenerationParams get() = params.copy(gpuLayers = appliedParams?.gpuLayers ?: 0,
        inferenceBackend = appliedParams?.inferenceBackend ?: InferenceBackend.CPU)
        .boundedForRuntime(loadedContextLength, loadedMemoryLimit)
    private var generationActive = false
    private var automaticSession = false
    private val taskContexts = mutableMapOf<GenerationTask, Int>()
    private val mutableAutomaticState = MutableStateFlow<AutomaticGenerationState?>(null)
    override val automaticState = mutableAutomaticState.asStateFlow()
    @Volatile private var closed = false

    override val state: StateFlow<ModelState> = mutableState.asStateFlow()
    override val isModelReady: Boolean get() = allocationReady()
    override val executionState: StateFlow<InferenceExecution> = runtime.executionState ?: MutableStateFlow(InferenceExecution())

    init {
        if (runtime is RemoteLlamaRuntime) runtime.onWorkerLost = {
            if (!runtime.allocationValid) mutableState.compareAndSet(ModelState.Ready, ModelState.Unloaded)
        }
    }

    override suspend fun ensureLoaded(): Result<Unit> = modelMutex.withLock {
        if (closed) return Result.failure(IllegalStateException("Движок закрыт"))
        withContext(engineDispatcher) {
            runCatching {
                val file = File(modelProvider.getModelPath().getOrThrow())
                val trained = GgufMetadataReader.readContextLength(file)
                val request = currentCoroutineContext()
                val cancellation = cancellationEpoch.get()
                synchronized(lifecycleLock) {
                    check(!closed) { "Движок закрыт" }
                    if (allocationReady() && loadedModel == ModelFileStamp(file) &&
                        ((automaticSession && appliedParams?.computeMode == params.computeMode) ||
                            loadedConfiguration == NativeConfiguration(runtimeParams, processorCount))) {
                        if (!automaticSession) applyRuntimeParams()
                        return@runCatching
                    }
                    val requested = params
                    val attempts = ComputeAttempts(requested.computeMode)
                    while (true) {
                        request.ensureActive()
                        checkCancellation(cancellation)
                        if (runtime is RemoteLlamaRuntime) runtime.beginRequest()
                        val selected = selectCompute(file, requested, minOf(requested.contextSize,
                            trained ?: ModelContextLimits.FALLBACK_CONTEXT_SIZE), attempts, cancellation)
                        try {
                            if (loadedConfiguration != null) releaseModel()
                            if (runtime is RemoteLlamaRuntime) runtime.awaitRelease()
                            request.ensureActive()
                            checkCancellation(cancellation)
                            loadedContextLength = trained
                            loadedMemoryLimit = memoryGuard?.assess(file, selected)
                            checkAllocationAssessment(loadedMemoryLimit, selected.contextSize)
                            check(requested.contextSize >= 2) { "Контекст недоступен. Дождитесь обновления настроек памяти и повторите запрос." }
                            mutableState.value = ModelState.Loading
                            val before = memoryGuard?.beforeLoad()
                            runtime.beginRequest()
                            checkCancellation(cancellation)
                            val effective = selected.boundedForRuntime(trained, loadedMemoryLimit)
                            applyRuntimeParams(effective)
                            check(runtime.load(file.path)) { "Не удалось загрузить модель" }
                            check(!closed) { "Движок закрыт" }
                            loadedConfiguration = NativeConfiguration(effective, processorCount)
                            loadedModel = ModelFileStamp(file)
                            memoryGuard?.loaded(file, effective, before)
                            mutableState.value = ModelState.Ready
                            break
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            if (closed || selected.inferenceBackend == InferenceBackend.CPU) throw failure
                            request.ensureActive()
                            checkCancellation(cancellation)
                            attempts.reject(selected.inferenceBackend, failure.toUserMessage())
                            releaseModel()
                        }
                    }
                }
            }.onFailure { error ->
                synchronized(lifecycleLock) {
                    if (!closed) {
                        releaseModel()
                        mutableState.value = ModelState.Error(error.toUserMessage())
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
                if (loadedModel != null && loadedModel != ModelFileStamp(File(path))) releaseModel()
                runtime.beginRequest()
                runtime.prepareTokenizer(path)
                runtime.countTokens(buildPrompt(messages))
            }
        }
    }

    private fun paramsForTask(task: GenerationTask): GenerationParams =
        if (task == GenerationTask.CALENDAR && !params.automaticMemory) params.withAutomaticMemory(true) else params

    override suspend fun promptTokenBudget(task: GenerationTask): Int = modelMutex.withLock {
        withContext(engineDispatcher) {
            val file = File(modelProvider.getModelPath().getOrThrow())
            synchronized(lifecycleLock) {
                check(!closed) { "Движок закрыт" }
                val settings = paramsForTask(task).copy(gpuLayers = 0)
                if (!settings.automaticMemory) {
                    val selected = manualConfiguration(file, settings)
                    return@withContext (selected.contextSize - selected.maxTokens -
                        AutomaticContextPolicy.TECHNICAL_RESERVE).coerceAtLeast(0)
                }
                val candidate = automaticConfiguration(file, settings, 0, task, 4096)
                val current = appliedParams
                val usableExisting = task != GenerationTask.CALENDAR && allocationReady() &&
                    loadedModel == ModelFileStamp(file) && current != null && current.gpuLayers == settings.gpuLayers &&
                    current.effectiveCpuThreads(processorCount) == settings.effectiveCpuThreads(processorCount)
                val useExisting = usableExisting && (candidate.deviceContextLimit?.canLoad == false ||
                    current!!.contextSize >= candidate.contextSize)
                val context = if (useExisting) minOf(4096, current!!.contextSize)
                    else candidate.contextSize
                if (!useExisting) checkAllocationAssessment(candidate.deviceContextLimit,
                    AutomaticContextPolicy.minimumContext(0, task))
                if (context < AutomaticContextPolicy.minimumContext(0, task)) throw capacityFailure(
                    0, task, context, candidate.trainedContextLength ?: ModelContextLimits.FALLBACK_CONTEXT_SIZE)
                AutomaticContextPolicy.promptBudget(context, task)
            }
        }
    }

    override suspend fun checkModelAvailability(task: GenerationTask) = modelMutex.withLock {
        withContext(engineDispatcher) {
            val file = File(modelProvider.getModelPath().getOrThrow())
            val requestContext = currentCoroutineContext()
            synchronized(lifecycleLock) {
                check(!closed) { "Движок закрыт" }
                val settings = paramsForTask(task).copy(gpuLayers = 0)
                requestContext.ensureActive()
                if (settings.automaticMemory) rejectUnavailableAllocation(file, settings)
                else manualConfiguration(file, settings)
                Unit
            }
        }
    }

    /** This gate neither initializes the native library nor reads/materializes tokenizer arrays. */
    private fun rejectUnavailableAllocation(file: File, settings: GenerationParams) {
        val current = appliedParams
        if (allocationReady() && loadedModel == ModelFileStamp(file) && current != null &&
            (settings.computeMode == ComputeMode.AUTO || current.gpuLayers == 0) &&
            current.effectiveCpuThreads(processorCount) == settings.effectiveCpuThreads(processorCount)) return
        val assessment = memoryGuard?.assess(file, settings.copy(batchSizeAuto = false, batchSize = 64))
        // No input was tokenized, so its required context is deliberately unknown.
        checkAllocationAssessment(assessment, null)
    }

    override fun generateForTask(messages: List<ChatMessage>, task: GenerationTask): Flow<String> =
        generateForTask(messages, task) {}

    /** One request owns its tokenizer, allocation and sampler until natural completion or cancellation. */
    override fun generateForTask(messages: List<ChatMessage>, task: GenerationTask,
        onReady: suspend () -> Unit): Flow<String> = kotlinx.coroutines.flow.flow {
        var emitted = false
        generateEventsForTask(messages, task, onReady).collect { event ->
            when (event) {
                is GenerationEvent.Delta -> { emitted = true; emit(event.text) }
                is GenerationEvent.Restart -> check(!emitted) {
                    "Ответ прерван при переключении вычислителя. Повторите запрос."
                }
            }
        }
    }

    override fun generateEventsForTask(messages: List<ChatMessage>, task: GenerationTask,
        onReady: suspend () -> Unit): Flow<GenerationEvent> = kotlinx.coroutines.flow.flow {
        val cancellation = cancellationEpoch.get()
        val settings = withContext(engineDispatcher) { synchronized(lifecycleLock) { paramsForTask(task) } }
        val path = modelProvider.getModelPath().getOrThrow()
        val attempts = ComputeAttempts(settings.computeMode)
        var readyDelivered = false
        var emitted = false
        val ready: suspend () -> Unit = { if (!readyDelivered) { onReady(); readyDelivered = true } }
        modelMutex.withLock {
            while (true) {
                currentCoroutineContext().ensureActive()
                checkCancellation(cancellation)
                try {
                    generationAttempt(messages, task, ready, settings, path, attempts, cancellation)
                        .collect { emitted = true; emit(GenerationEvent.Delta(it)) }
                    break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    val retry = attempts.selected != InferenceBackend.CPU && (failure is GpuExecutionException ||
                        failure is ModelAllocationException || (failure is PromptCapacityException &&
                        failure.reason == ContextCapacityReason.MEMORY))
                    if (!retry) throw failure
                    currentCoroutineContext().ensureActive()
                    checkCancellation(cancellation)
                    val reason = failure.message ?: "Ошибка GPU"
                    attempts.reject(attempts.selected, reason, emitted)
                    withContext(engineDispatcher) { synchronized(lifecycleLock) { releaseModel() } }
                    if (emitted) emit(GenerationEvent.Restart(reason))
                }
            }
        }
    }

    private fun generationAttempt(messages: List<ChatMessage>, task: GenerationTask,
        onReady: suspend () -> Unit, requested: GenerationParams, path: String, attempts: ComputeAttempts,
        cancellation: Long): Flow<String> = channelFlow<AutomaticEmission> {
        check(!closed) { "Движок закрыт" }
        if (runtime is RemoteLlamaRuntime) runtime.awaitRelease()
        val requestContext = currentCoroutineContext()
        var requestSettings = requested.copy(gpuLayers = 0)
        if (!requestSettings.automaticMemory) {
            var manualCancelHook: Job? = null
            try {
                val file = File(path)
                var selected = synchronized(lifecycleLock) {
                    if (loadedModel != null && loadedModel != ModelFileStamp(file)) releaseModel()
                    requestContext.ensureActive()
                    val checked = manualConfiguration(file, requestSettings)
                    runtime.beginRequest()
                    checkCancellation(cancellation)
                    checked
                }
                manualCancelHook = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { runtime.cancel() }
                }
                val prompt = buildPrompt(messages)
                val inputTokens = synchronized(lifecycleLock) {
                    runtime.prepareTokenizer(path)
                    runtime.countTokens(prompt)
                }
                val promptBudget = selected.contextSize - selected.maxTokens -
                    AutomaticContextPolicy.TECHNICAL_RESERVE
                if (inputTokens > promptBudget) {
                    synchronized(lifecycleLock) {
                        mutableAutomaticState.value = AutomaticGenerationState(task, selected.contextSize,
                            selected.batchSize, inputTokens, selected.maxTokens,
                            AutomaticContextPolicy.availableAnswer(selected.contextSize, inputTokens),
                            automaticMemory = false, requestRejected = true)
                    }
                    throw PromptCapacityException(promptBudget, ContextCapacityReason.MANUAL_LIMIT,
                        inputTokens + selected.maxTokens + AutomaticContextPolicy.TECHNICAL_RESERVE,
                        selected.contextSize, selected.maxTokens)
                }
                synchronized(lifecycleLock) {
                    requestSettings = selectCompute(file, requested, selected.contextSize, attempts, cancellation)
                    selected = manualConfiguration(File(path), requestSettings)
                    check(!closed) { "Движок закрыт" }
                    requestContext.ensureActive()
                    val nativeConfig = NativeConfiguration(selected, processorCount)
                    val reuseAllocation = allocationReady() && loadedModel == ModelFileStamp(file) &&
                        loadedConfiguration == nativeConfig
                    generationActive = true
                    if (!reuseAllocation) {
                        mutableState.value = ModelState.Loading
                        val before = memoryGuard?.beforeLoad()
                        runtime.updateParams(selected, selected.effectiveCpuThreads(processorCount), selected.batchSize)
                        val loaded = runtime.load(path)
                        if (!loaded) throw ModelAllocationException(ModelAllocationFailure.NATIVE_ALLOCATION_FAILED,
                            selected.contextSize, selected.deviceContextLimit)
                        check(!closed) { "Движок закрыт" }
                        loadedModel = ModelFileStamp(file)
                        loadedConfiguration = nativeConfig
                        loadedContextLength = selected.trainedContextLength
                        loadedMemoryLimit = selected.deviceContextLimit
                        memoryGuard?.loaded(file, selected, before)
                        mutableState.value = ModelState.Ready
                    }
                    runtime.updateParams(selected, selected.effectiveCpuThreads(processorCount), selected.batchSize)
                    appliedParams = selected
                    automaticSession = false
                    taskContexts.clear()
                    mutableAutomaticState.value = AutomaticGenerationState(task, selected.contextSize,
                        selected.batchSize, inputTokens, selected.maxTokens,
                        AutomaticContextPolicy.availableAnswer(selected.contextSize, inputTokens),
                        automaticMemory = false)
                }
                requestContext.ensureActive()
                onReady()
                requestContext.ensureActive()
                var callbackError: String? = null
                val stream = object : GenStream {
                    override fun onDelta(text: String) {
                        if (trySendBlocking(AutomaticEmission.Text(text)).isFailure) runtime.cancel()
                    }
                    override fun onComplete() = Unit
                    override fun onError(message: String) { callbackError = message }
                }
                runtime.generateStream(prompt, stream)
                callbackError?.let { error(it) }
                val result = requireNotNull(runtime.lastGeneration) { "Нет статистики генерации" }
                mutableAutomaticState.value = mutableAutomaticState.value?.copy(
                    generatedTokens = result.generatedTokens, stopReason = result.reason)
                when (result.reason) {
                    GenerationStopReason.EOS -> Unit
                    GenerationStopReason.CANCELLED -> throw CancellationException("Генерация остановлена")
                    GenerationStopReason.CONTEXT_LIMIT ->
                        error("Ответ достиг выбранного вручную контекста. Сохранённая часть ответа не завершена.")
                    GenerationStopReason.OUTPUT_LIMIT ->
                        error("Ответ достиг выбранного лимита ${tokenCount(selected.maxTokens)}. Сохранённая часть ответа не завершена.")
                }
            } catch (cancelled: CancellationException) {
                synchronized(lifecycleLock) {
                    if (state.value is ModelState.Loading) releaseModel()
                    else mutableAutomaticState.value = mutableAutomaticState.value?.copy(stopReason = GenerationStopReason.CANCELLED)
                }
                throw cancelled
            } catch (failure: Exception) {
                synchronized(lifecycleLock) {
                    if (!closed && state.value is ModelState.Loading) {
                        releaseModel()
                        mutableState.value = ModelState.Error(failure.toUserMessage())
                    }
                }
                send(AutomaticEmission.Failed(failure))
            } finally {
                manualCancelHook?.cancel()
                synchronized(lifecycleLock) { generationActive = false }
            }
            return@channelFlow
        }
        var cancelHook: Job? = null
        try {
            val file = File(path)
            synchronized(lifecycleLock) {
                if (loadedModel != null && loadedModel != ModelFileStamp(file)) releaseModel()
                requestContext.ensureActive()
                rejectUnavailableAllocation(file, requestSettings)
                runtime.beginRequest()
                checkCancellation(cancellation)
            }
            cancelHook = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { runtime.cancel() }
            }
            val prompt = buildPrompt(messages)
            val inputTokens: Int
            var effective: GenerationParams
            synchronized(lifecycleLock) {
                check(!closed) { "Движок закрыт" }
                runtime.prepareTokenizer(path)
                inputTokens = runtime.countTokens(prompt)
                val minimum = AutomaticContextPolicy.minimumContext(inputTokens, task)
                requestSettings = selectCompute(file, requested, minimum, attempts, cancellation)
                val trained = GgufMetadataReader.readContextLength(file) ?: ModelContextLimits.FALLBACK_CONTEXT_SIZE
                val desired = maxOf(AutomaticContextPolicy.desiredContext(inputTokens, task), taskContexts[task] ?: 0)
                val current = appliedParams?.takeIf {
                    task != GenerationTask.CALENDAR || it.batchSize == AutomaticContextPolicy.MINIMUM_BATCH
                }
                val sameTask = mutableAutomaticState.value?.task == task
                val canReuse = allocationReady() && loadedModel == ModelFileStamp(file) &&
                    current != null && sameTask && current.contextSize >= desired &&
                    current.gpuLayers == requestSettings.gpuLayers && current.inferenceBackend == requestSettings.inferenceBackend &&
                    current.effectiveCpuThreads(processorCount) == requestSettings.effectiveCpuThreads(processorCount)
                effective = if (canReuse) {
                    requestSettings.copy(contextSize = current!!.contextSize,
                        batchSizeAuto = false, batchSize = current.batchSize,
                        trainedContextLength = loadedContextLength, deviceContextLimit = loadedMemoryLimit)
                } else {
                    val planned = automaticConfiguration(file, requestSettings, inputTokens, task, desired, attempts.budget)
                    // If growth is currently unavailable, the already allocated context can still serve the input.
                    if (allocationReady() && loadedModel == ModelFileStamp(file) && current != null &&
                        (sameTask || planned.deviceContextLimit?.canLoad == false ||
                            AutomaticContextPolicy.availableAnswer(planned.contextSize, inputTokens) < AutomaticContextPolicy.minimumAnswer(task)) &&
                        (current.contextSize >= planned.contextSize || planned.deviceContextLimit?.canLoad == false) &&
                        current.gpuLayers == requestSettings.gpuLayers && current.inferenceBackend == requestSettings.inferenceBackend &&
                        current.effectiveCpuThreads(processorCount) == requestSettings.effectiveCpuThreads(processorCount)) {
                        requestSettings.copy(contextSize = current.contextSize, batchSizeAuto = false,
                            batchSize = current.batchSize, trainedContextLength = loadedContextLength,
                            deviceContextLimit = loadedMemoryLimit)
                    } else planned
                }
                val nativeConfig = NativeConfiguration(effective, processorCount)
                val reuseAllocation = allocationReady() && loadedModel == ModelFileStamp(file) &&
                    loadedConfiguration == nativeConfig
                if (!reuseAllocation) checkAllocationAssessment(effective.deviceContextLimit, minimum)
                val available = AutomaticContextPolicy.availableAnswer(effective.contextSize, inputTokens)
                if (effective.contextSize < minimum || available < AutomaticContextPolicy.minimumAnswer(task)) {
                    throw capacityFailure(inputTokens, task, effective.contextSize, trained)
                }
                effective = effective.copy(maxTokens = available)
                automaticSession = true
                generationActive = true
                if (!reuseAllocation) {
                    mutableState.value = ModelState.Loading
                    var before = memoryGuard?.beforeLoad()
                    runtime.updateParams(effective, effective.effectiveCpuThreads(processorCount), effective.batchSize)
                    var nativeFailure: Exception? = null
                    fun load(): Boolean = try {
                        runtime.load(path)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: GpuExecutionException) {
                        throw failure
                    } catch (error: IllegalStateException) {
                        nativeFailure = error
                        false
                    }
                    var loaded = load()
                    // A failed larger allocation is retried with a small batch and the smallest usable context.
                    if (!loaded && (effective.contextSize != minimum || effective.batchSize != 64)) {
                        requestContext.ensureActive()
                        // Native loading can release the old context even when it fails.
                        releaseModel()
                        effective = effective.copy(contextSize = minimum, batchSize = 64,
                            maxTokens = AutomaticContextPolicy.availableAnswer(minimum, inputTokens))
                        val retryAssessment = memoryGuard?.assess(file, effective)
                        checkAllocationAssessment(retryAssessment, minimum)
                        if (retryAssessment != null && retryAssessment.maximumContext < minimum) {
                            throw capacityFailure(inputTokens, task, retryAssessment.maximumContext, trained)
                        }
                        effective = effective.copy(deviceContextLimit = retryAssessment)
                        before = memoryGuard?.beforeLoad()
                        automaticSession = true
                        generationActive = true
                        mutableState.value = ModelState.Loading
                        runtime.updateParams(effective, effective.effectiveCpuThreads(processorCount), 64)
                        runtime.beginRequest()
                        checkCancellation(cancellation)
                        requestContext.ensureActive()
                        loaded = load()
                    }
                    if (!loaded) {
                        requestContext.ensureActive()
                        releaseModel()
                        throw ModelAllocationException(ModelAllocationFailure.NATIVE_ALLOCATION_FAILED,
                            minimum, effective.deviceContextLimit, nativeFailure)
                    }
                    check(!closed) { "Движок закрыт" }
                    loadedModel = ModelFileStamp(file)
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
            onReady()
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
                    automaticConfiguration(file, requestSettings, inputTokens, task, nextDesired, attempts.budget)
                }
                if (next.contextSize <= effective.contextSize && attempts.budget != null &&
                    next.contextSize < minOf(nextDesired, next.trainedContextLength ?: nextDesired)) {
                    throw GpuExecutionException("Недостаточно памяти для расширения GPU-контекста")
                }
                check(next.contextSize > effective.contextSize) {
                    "Ответ достиг доступного предела контекста. Сохранённая часть ответа не завершена."
                }
                currentCoroutineContext().ensureActive()
                mutableState.value = ModelState.Loading
                val resized = runtime.resizeContext(next.contextSize, next.batchSize, next.effectiveCpuThreads(processorCount))
                if (!resized && effective.gpuLayers != 0) throw GpuExecutionException("Не удалось расширить GPU-контекст")
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
            cancelHook?.cancel()
            synchronized(lifecycleLock) { generationActive = false }
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

    private fun allocationReady(): Boolean = runtime.allocationValid && state.value is ModelState.Ready

    private class ComputeAttempts(mode: ComputeMode) {
        var next = if (mode == ComputeMode.CPU) InferenceBackend.CPU else InferenceBackend.VULKAN
            private set
        var selected = InferenceBackend.CPU
        var budget: GpuBudget? = null
        private val reasons = linkedMapOf<InferenceBackend, String>()
        private var residentReason: String? = null
        val reason: String? get() = (listOfNotNull(residentReason) + reasons.map { "${it.key.displayName}: ${it.value}" })
            .joinToString("\n").ifEmpty { null }
        fun preferResident(backend: InferenceBackend, previousReason: String?) {
            if (reasons.isEmpty()) {
                next = backend
                residentReason = previousReason
            }
        }
        fun reject(backend: InferenceBackend, reason: String, partialOutput: Boolean = false) {
            reasons[backend] = when {
                reason.startsWith("GPU Vulkan ") && reason.contains("backend requires Vulkan ") ->
                    "Драйвер предоставляет Vulkan ${reason.substringAfter("GPU Vulkan ").substringBefore(';')}, требуется Vulkan ${reason.substringAfter("backend requires Vulkan ")}"
                reason.contains("library \"libOpenCL.so\" not found") -> "Системный драйвер OpenCL недоступен приложению"
                reason.contains("library \"libggml-opencl.so\" not found") -> "Плагин OpenCL недоступен для этой архитектуры"
                reason == "No compatible Adreno OpenCL GPU" -> "Совместимый с плагином GPU Adreno не найден"
                reason == "No compatible Vulkan GPU" -> "Совместимый GPU Vulkan не найден"
                else -> reason.lineSequence().first().take(200)
            }
            next = if (backend == InferenceBackend.VULKAN && !partialOutput) InferenceBackend.OPENCL else InferenceBackend.CPU
            selected = InferenceBackend.CPU
            budget = null
        }
    }

    private fun selectCompute(file: File, requested: GenerationParams, minimum: Int, attempts: ComputeAttempts,
        cancellation: Long): GenerationParams {
        val resident = appliedParams
        if (allocationReady() && loadedModel == ModelFileStamp(file) && resident?.computeMode == requested.computeMode &&
            (requested.gpuLayers <= 0 || requested.gpuLayers == resident.gpuLayers)) {
            attempts.preferResident(resident.inferenceBackend, executionState.value.fallbackReason)
        }
        while (attempts.next != InferenceBackend.CPU) {
            checkCancellation(cancellation)
            val backend = attempts.next
            val device = try {
                runtime.gpuDevice(file.path, backend)
            } catch (failure: GpuExecutionException) {
                attempts.reject(backend, failure.toUserMessage())
                continue
            }
            if (device == null) {
                attempts.reject(backend, "Совместимое устройство недоступно")
                continue
            }
            val metadata = GgufMetadataReader.readMetadata(file)?.memory
            val residentLayers = loadedConfiguration?.takeIf {
                allocationReady() && loadedModel == ModelFileStamp(file) && it.backend == backend
            }?.gpuLayers ?: 0
            val budget = metadata?.let { GpuBudget(device, it, residentLayers) }
            val layers = budget?.let { selectGpuLayers(file, requested, minimum, backend, it) } ?: 0
            if (layers > 0) {
                checkCancellation(cancellation)
                attempts.selected = backend
                attempts.budget = budget
                runtime.recordFallback(attempts.reason)
                return requested.copy(gpuLayers = layers, inferenceBackend = backend)
            }
            attempts.reject(backend, if (metadata?.blockCount == null) "Недостаточно сведений о слоях GGUF"
                else "Недостаточно памяти для слоёв и контекста модели")
        }
        checkCancellation(cancellation)
        runtime.recordFallback(attempts.reason)
        return requested.copy(gpuLayers = 0, inferenceBackend = InferenceBackend.CPU)
    }

    private class GpuBudget(private val device: GpuDevice, val metadata: ModelMemoryMetadata,
        private val residentLayers: Int) {
        fun fits(layers: Int, batch: Int, context: Int): Boolean {
            val cost = ModelMemoryCalculator.cost(metadata, MemoryRuntimeSettings(false, batch, layers), context) ?: return false
            val limit = device.maxAllocationBytes
            if (limit != null && (ModelMemoryCalculator.largestGpuTensor(metadata, layers) > limit ||
                    cost.largestWorkingTensorBytes > limit)) return false
            val free = device.freeBytes ?: return true
            // With the same placement llama.cpp keeps the weight buffers when it rebuilds a context.
            val newWeights = if (layers == residentLayers) 0L else ModelMemoryCalculator.gpuWeights(metadata, layers)
            return newWeights + cost.workingBytes <= free - free / 8
        }
    }

    private fun selectGpuLayers(file: File, requested: GenerationParams, minimum: Int, backend: InferenceBackend,
        budget: GpuBudget): Int {
        val blocks = budget.metadata.blockCount ?: return 0
        val maximum = if (requested.gpuLayers > 0) minOf(requested.gpuLayers, blocks + 1) else blocks + 1
        val current = appliedParams
        val batch = if (requested.automaticMemory) 64 else requested.effectiveBatchSize
        if (allocationReady() && loadedModel == ModelFileStamp(file) && current?.inferenceBackend == backend &&
            current.gpuLayers in 1..maximum && (current.contextSize >= minimum ||
                budget.fits(current.gpuLayers, batch, minimum))) return current.gpuLayers
        var lower = 1
        var upper = maximum
        var selected = 0
        while (lower <= upper) {
            val layers = lower + (upper - lower) / 2
            val candidate = requested.copy(gpuLayers = layers, contextSize = minimum,
                batchSizeAuto = false, batchSize = batch)
            val assessment = memoryGuard?.assess(file, candidate)
            val fits = assessment?.canLoad != false && (assessment == null || assessment.maximumContext >= minimum) &&
                budget.fits(layers, batch, minimum)
            if (fits) {
                selected = layers
                lower = layers + 1
            } else upper = layers - 1
        }
        return selected
    }

    private fun automaticConfiguration(file: File, settings: GenerationParams, promptTokens: Int,
        task: GenerationTask, desired: Int, gpuBudget: GpuBudget? = null): GenerationParams {
        val trained = GgufMetadataReader.readContextLength(file) ?: ModelContextLimits.FALLBACK_CONTEXT_SIZE
        val minimum = AutomaticContextPolicy.minimumContext(promptTokens, task)
        val preferred = AutomaticContextPolicy.preferredBatch(promptTokens, task)
        var best: GenerationParams? = null
        for (batch in listOf(512, 256, 128, 64).filter { it <= preferred }) {
            val request = settings.copy(batchSizeAuto = false, batchSize = batch,
                trainedContextLength = trained, contextResponseRatio = ContextResponseRatio.FOUR_TO_ONE)
            val assessment = memoryGuard?.assess(file, request)
            // A rejected/unknown estimate carries its own cause; never invent a 512-token capacity.
            val cap = if (assessment == null) trained else
                if (assessment.canLoad) minOf(trained, assessment.maximumContext) else minOf(trained, minimum)
            var context = minOf(maxOf(desired, minimum), cap).coerceAtLeast(1)
            if (gpuBudget != null && !gpuBudget.fits(settings.gpuLayers, batch, context)) {
                var low = minOf(minimum, context)
                if (!gpuBudget.fits(settings.gpuLayers, batch, low)) {
                    if (batch != 64) continue
                    throw GpuExecutionException("Недостаточно памяти GPU для контекста запроса")
                }
                var high = context
                while (low < high) {
                    val mid = low + (high - low + 1) / 2
                    if (gpuBudget.fits(settings.gpuLayers, batch, mid)) low = mid else high = mid - 1
                }
                context = low
            }
            val candidate = request.copy(contextSize = context, deviceContextLimit = assessment,
                maxTokens = AutomaticContextPolicy.availableAnswer(context, promptTokens))
            val usable = assessment?.canLoad != false
            if (usable && context >= desired) return candidate
            val bestUsable = best?.deviceContextLimit?.canLoad != false
            if (best == null || (usable && !bestUsable) ||
                (usable == bestUsable && context > best.contextSize)) best = candidate
        }
        return requireNotNull(best)
    }

    /** Manual settings are fixed for the request; RAM and GGUF checks may reject but never resize them. */
    private fun manualConfiguration(file: File, settings: GenerationParams): GenerationParams {
        val trained = GgufMetadataReader.readContextLength(file) ?: ModelContextLimits.FALLBACK_CONTEXT_SIZE
        check(settings.contextSize >= 2 && settings.maxTokens > 0 &&
            settings.contextSize > settings.maxTokens + AutomaticContextPolicy.TECHNICAL_RESERVE) {
            "Выбранные вручную контекст и лимит ответа несовместимы."
        }
        check(settings.contextSize <= trained) {
            "Выбранный вручную контекст ${settings.contextSize} превышает предел модели $trained токенов."
        }
        val selected = settings.copy(batchSizeAuto = false, trainedContextLength = trained)
        val assessment = memoryGuard?.assess(file, selected)
        checkAllocationAssessment(assessment, selected.contextSize)
        if (assessment != null && assessment.maximumContext < selected.contextSize) {
            error("По оценке памяти доступно до ${assessment.maximumContext} токенов контекста; " +
                "вручную выбрано ${selected.contextSize}. Уменьшите контекст или освободите память.")
        }
        return selected.copy(batchSize = selected.effectiveBatchSize, deviceContextLimit = assessment)
    }

    private fun capacityFailure(promptTokens: Int, task: GenerationTask, context: Int,
        trained: Int): PromptCapacityException = PromptCapacityException(
        AutomaticContextPolicy.promptBudget(context, task),
        if (AutomaticContextPolicy.minimumContext(promptTokens, task) > trained)
            ContextCapacityReason.MODEL_LIMIT else ContextCapacityReason.MEMORY,
        AutomaticContextPolicy.minimumContext(promptTokens, task), context,
        AutomaticContextPolicy.minimumAnswer(task),
    )

    private fun checkAllocationAssessment(assessment: DeviceContextLimit?, minimum: Int?) {
        if (assessment == null || assessment.canLoad) return
        val reason = when (assessment.status) {
            MemoryLimitStatus.LOW_MEMORY -> ModelAllocationFailure.LOW_MEMORY
            MemoryLimitStatus.INSUFFICIENT_MEMORY, MemoryLimitStatus.ESTIMATED -> ModelAllocationFailure.INSUFFICIENT_MEMORY
            MemoryLimitStatus.MEMORY_UNAVAILABLE -> ModelAllocationFailure.MEMORY_UNAVAILABLE
            MemoryLimitStatus.METADATA_UNAVAILABLE -> ModelAllocationFailure.METADATA_UNAVAILABLE
        }
        throw ModelAllocationException(reason, minimum, assessment)
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
                check(allocationReady()) { "Модель не загружена" }
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

                runtime.beginRequest()
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
        if (!closed) {
            cancellationEpoch.incrementAndGet()
            runtime.cancel()
        }
    }

    private fun checkCancellation(start: Long) {
        if (closed || start != cancellationEpoch.get()) throw CancellationException("Генерация остановлена")
    }

    override fun updateParams(params: GenerationParams) {
        if (runtime is RemoteLlamaRuntime) {
            if (!closed) this.params = params
            return
        }
        synchronized(lifecycleLock) {
            // Cancelled work can still restore its parameters in finally after onCleared.
            if (closed) return
            this.params = params
            // Settings may be saved during a request. Its native parameters remain unchanged.
            if (!automaticSession && !generationActive && allocationReady() && loadedConfiguration == NativeConfiguration(runtimeParams, processorCount)) {
                applyRuntimeParams()
            }
        }
    }

    /** Call only with lifecycleLock held. Reloads and repeated identical updates are avoided. */
    private fun applyRuntimeParams(effective: GenerationParams = runtimeParams) {
        if (appliedParams != effective) {
            runtime.updateParams(effective, effective.effectiveCpuThreads(processorCount), effective.effectiveBatchSize)
            appliedParams = effective
        }
    }

    override fun unload() {
        if (runtime is RemoteLlamaRuntime) {
            runtime.cancel()
            synchronized(disposalLock) {
                if (!closed) executor.execute { synchronized(lifecycleLock) { if (!closed) releaseModel() } }
            }
        } else synchronized(lifecycleLock) { if (!closed) releaseModel() }
    }

    override fun close() {
        if (runtime is RemoteLlamaRuntime) {
            synchronized(disposalLock) {
                if (closed) return
                closed = true
                runtime.cancel()
                executor.execute {
                    try { synchronized(lifecycleLock) { releaseModel() } }
                    finally { runtime.close(); engineDispatcher.close() }
                }
            }
            return
        }
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            try {
                releaseModel()
            } finally {
                engineDispatcher.close()
            }
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
                loadedModel = null
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

    private fun tokenCount(value: Int): String {
        val word = if (value % 100 in 11..14) "токенов" else when (value % 10) {
            1 -> "токен"
            2, 3, 4 -> "токена"
            else -> "токенов"
        }
        return "$value $word"
    }

    private data class NativeConfiguration(
        val contextSize: Int,
        val batchSize: Int,
        val gpuLayers: Int,
        val cpuThreads: Int,
        val computeMode: ComputeMode,
        val backend: InferenceBackend,
    ) {
        constructor(params: GenerationParams, processorCount: Int) : this(
            params.contextSize, params.effectiveBatchSize, params.gpuLayers,
            params.effectiveCpuThreads(processorCount), params.computeMode, params.inferenceBackend,
        )
    }

    private companion object {
        const val STREAM_FLUSH_TOKEN_COUNT = 1
        const val MAX_BATCH_DELAY_MILLIS = 50L
    }
}
