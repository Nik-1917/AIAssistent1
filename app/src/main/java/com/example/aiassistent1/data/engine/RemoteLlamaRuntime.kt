package com.example.aiassistent1.data.engine

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SharedMemory
import android.system.OsConstants
import com.example.aiassistent1.domain.model.ComputeMode
import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.GenerationResult
import com.example.aiassistent1.domain.model.GenerationStopReason
import com.example.aiassistent1.domain.model.GpuDevice
import com.example.aiassistent1.domain.model.InferenceBackend
import com.example.aiassistent1.domain.model.InferenceExecution
import com.llamatik.library.platform.GenStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex

internal class GpuExecutionException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
private class NativeWorkerException(message: String, cause: Throwable) : IllegalStateException(message, cause)

/** Native calls run on the engine executor. Synchronous callbacks bound the output queue. */
internal class RemoteLlamaRuntime(context: Context) : LlamaRuntime {
    private val context = context.applicationContext
    private val owner = UUID.randomUUID().toString()
    @Suppress("DEPRECATION")
    private val appRevision = this.context.packageManager.getPackageInfo(this.context.packageName, 0).lastUpdateTime
    private val preferences = this.context.getSharedPreferences("llm_gpu_compatibility", Context.MODE_PRIVATE)
    private val mutableExecution = MutableStateFlow(InferenceExecution())
    override val executionState = mutableExecution
    override val requestMutex = processMutex
    @Volatile private var worker: ILlamaWorker? = null
    @Volatile private var connection: ServiceConnection? = null
    var onWorkerLost: () -> Unit = {}
    @Volatile private var closed = false
    @Volatile private var cancelledAt = 0L
    private var cleanup: Future<*>? = null
    private var params = GenerationParams()
    private var threads = 4
    private var batch = 64
    private var path = ""
    private val driverIdentities = mutableMapOf<InferenceBackend, String>()
    private var workerPid = 0
    @Volatile private var loaded = false
    override var lastGeneration: GenerationResult? = null
        private set
    override val allocationValid: Boolean
        get() = loaded && activeOwner.get() == owner && worker?.asBinder()?.isBinderAlive == true

    override fun updateParams(params: GenerationParams, threads: Int, batchSize: Int) {
        this.params = params
        this.threads = threads
        batch = batchSize
        mutableExecution.value = mutableExecution.value.copy(requestedMode = params.computeMode,
            fallbackReason = if (params.computeMode == ComputeMode.CPU) null else mutableExecution.value.fallbackReason)
    }

    override fun beginRequest() {
        cancelledAt = 0L
        execute("begin")
    }

    override fun gpuDevice(path: String, backend: InferenceBackend): GpuDevice? {
        this.path = path
        require(backend != InferenceBackend.CPU)
        val args = Bundle().apply { putString("inferenceBackend", backend.name) }
        mutableExecution.value = mutableExecution.value.copy(checkingGpu = true)
        try {
            val info = execute("gpuInfo", args)
            driverIdentities[backend] = info.getString("identity").orEmpty()
            // Missing libraries and known-incompatible drivers require no GGML registration.
            info.getString("unsupported")?.takeIf(String::isNotBlank)?.let { throw GpuExecutionException(it) }
            preferences.getString(compatibilityKey(path, backend), null)?.let { throw GpuExecutionException(it) }
            val reply = execute("gpu", args)
            val name = reply.getString("device") ?: return null
            return GpuDevice(name, reply.getString("identity").orEmpty(),
                reply.getLong("freeBytes").takeIf { reply.containsKey("freeBytes") }, reply.getLong("totalBytes"),
                reply.getLong("maxAllocationBytes").takeIf { reply.containsKey("maxAllocationBytes") })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: GpuExecutionException) {
            throw rejected
        } catch (failure: IllegalStateException) {
            discardGpuWorker()
            throw GpuExecutionException(failure.message ?: "Ошибка проверки устройства", failure)
        } finally {
            mutableExecution.value = mutableExecution.value.copy(checkingGpu = false)
        }
    }

    override fun prepareTokenizer(path: String) {
        this.path = path
        val reply = execute("prepare", Bundle().apply { putString("path", path) })
        loaded = loaded && reply.getBoolean("allocationValid")
    }

    override fun countTokens(prompt: String): Int = withInput(prompt) { execute("count", it).getInt("count") }

    override fun load(path: String): Boolean {
        this.path = path
        loaded = false
        lastGeneration = null
        mutableExecution.value = InferenceExecution(requestedMode = params.computeMode,
            modelName = File(path).name, checkingGpu = params.gpuLayers != 0,
            fallbackReason = mutableExecution.value.fallbackReason)
        val reply = executeInference("load", arguments().apply { putString("path", path) })
        loaded = reply.getBoolean("loaded")
        if (loaded && (mutableExecution.value.backend != params.inferenceBackend ||
                (params.gpuLayers != 0 && mutableExecution.value.offloadedLayers == 0))) {
            discardGpuWorker()
            throw GpuExecutionException("Движок не подтвердил выбранный вычислитель")
        }
        return loaded
    }

    override fun resizeContext(context: Int, batch: Int, threads: Int): Boolean {
        val args = arguments().apply {
            putInt("context", context)
            putInt("batch", batch)
            putInt("threads", threads)
        }
        return executeInference("resize", args).getBoolean("loaded").also { loaded = it }
    }

    override fun generateStream(prompt: String, stream: GenStream) = withInput(prompt) {
        runGeneration("generate", arguments().apply { putAll(it) }, stream)
    }

    override fun continueStream(stream: GenStream) = runGeneration("continue", arguments(), stream)

    private fun runGeneration(operation: String, args: Bundle, stream: GenStream) {
        lastGeneration = null
        val reply = executeInference(operation, args, stream)
        val result = requireNotNull(reply.getLongArray("generation")) { "Нет статистики генерации" }
        check(result.size == 4) { "Некорректная статистика генерации" }
        lastGeneration = GenerationResult(GenerationStopReason.entries[result[0].toInt()],
            Math.toIntExact(result[1]), Math.toIntExact(result[2]), Math.toIntExact(result[3]))
        stream.onComplete()
    }

    private fun executeInference(operation: String, args: Bundle, stream: GenStream? = null): Bundle {
        try {
            return execute(operation, args, stream).also(::publish)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IllegalStateException) {
            if (params.gpuLayers == 0) throw failure
            val reason = failure.message ?: "Ошибка GPU"
            // A lost process or allocation failure alone does not establish model incompatibility.
            val message = reason.lowercase()
            if (failure !is NativeWorkerException &&
                listOf("unsupported", "not supported", "incompatible", "not implemented", "cl_build_program_failure")
                    .any(message::contains)) {
                preferences.edit().putString(compatibilityKey(path, params.inferenceBackend), reason).apply()
            }
            discardGpuWorker()
            throw GpuExecutionException(reason, failure)
        }
    }

    /** A failed driver can leave backend globals half initialized; model_free is insufficient. */
    private fun discardGpuWorker() {
        val remote = worker
        worker = null
        loaded = false
        residentPrivateBytes.set(0)
        activeOwner.compareAndSet(owner, null)
        if (remote != null) {
            val died = CountDownLatch(1)
            val death = IBinder.DeathRecipient { died.countDown() }
            try {
                remote.asBinder().linkToDeath(death, 0)
                remote.abort(owner)
                died.await(5, TimeUnit.SECONDS)
            } catch (_: RemoteException) { }
            finally {
                try { remote.asBinder().unlinkToDeath(death, 0) } catch (_: NoSuchElementException) { }
            }
        }
        connection?.let { try { context.unbindService(it) } catch (_: IllegalArgumentException) { } }
        connection = null
    }

    private fun publish(reply: Bundle) {
        val info = reply.getStringArray("backend") ?: return
        if (info.size < 6 || info[5] != "1") return
        val timing = reply.getLongArray("timings")
        val generation = reply.getLongArray("generation")
        val offloaded = info[2].toIntOrNull() ?: 0
        val prefillNanos = timing?.getOrNull(0) ?: 0L
        val decodeNanos = timing?.getOrNull(1) ?: 0L
        val inputTokens = timing?.getOrNull(4) ?: generation?.getOrNull(1) ?: 0L
        val outputTokens = timing?.getOrNull(3) ?: 0L
        mutableExecution.value = mutableExecution.value.copy(
            requestedMode = params.computeMode,
            backend = InferenceBackend.valueOf(info[0]),
            deviceName = info[1], offloadedLayers = offloaded,
            totalLayers = info[3].toIntOrNull() ?: 0, modelName = File(path).name,
            initializationMillis = info[4].toLongOrNull(),
            timeToFirstTokenMillis = timing?.getOrNull(2)?.takeIf { it >= 0 }?.div(1_000_000),
            prefillTokensPerSecond = if (prefillNanos > 0) inputTokens * 1e9 / prefillNanos else null,
            decodeTokensPerSecond = if (decodeNanos > 0) outputTokens * 1e9 / decodeNanos else null,
            generatedTokens = generation?.getOrNull(2)?.toInt() ?: 0, checkingGpu = false,
        )
    }

    override fun recordFallback(reason: String?) {
        mutableExecution.value = mutableExecution.value.copy(fallbackReason = reason, checkingGpu = false)
    }

    override fun cancel() {
        if (cancelledAt == 0L) cancelledAt = System.nanoTime()
        try { worker?.cancel(owner) } catch (_: RemoteException) { }
    }

    override fun shutdown() {
        cancel()
        loaded = false
        lastGeneration = null
        activeOwner.compareAndSet(owner, null)
        val remote = worker ?: return
        cleanup = cleanupExecutor.submit {
            try { remote.execute(owner, "shutdown", Bundle(), null) } catch (_: RemoteException) { }
        }
    }

    override fun close() {
        closed = true
        try {
            awaitCleanup()
        } finally {
            connection?.let { try { context.unbindService(it) } catch (_: IllegalArgumentException) { } }
            connection = null
            worker = null
        }
    }

    private fun awaitCleanup() {
        try {
            cleanup?.get(15, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            try { worker?.abort(owner) } catch (_: RemoteException) { }
            worker = null
        } finally {
            cleanup = null
        }
    }

    fun awaitRelease() = awaitCleanup()

    private fun execute(operation: String, arguments: Bundle = Bundle(), stream: GenStream? = null): Bundle {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Native inference must run off the main thread" }
        check(!closed) { "Движок закрыт" }
        awaitCleanup()
        val remote = connect()
        if (activeOwner.getAndSet(owner) != owner) loaded = false
        val heartbeat = AtomicLong(System.nanoTime())
        val watchdog = watchdogExecutor.scheduleAtFixedRate({
            val now = System.nanoTime()
            val cancelled = cancelledAt
            val gpuOperation = operation == "gpu" || operation == "gpuInfo" || params.gpuLayers != 0
            val expired = if (cancelled != 0L) now - cancelled > TimeUnit.SECONDS.toNanos(10)
                else gpuOperation && now - heartbeat.get() > TimeUnit.MINUTES.toNanos(5)
            if (expired) try { remote.abort(owner) } catch (_: RemoteException) { }
        }, 1, 1, TimeUnit.SECONDS)
        val callback = stream?.let {
            object : ILlamaStream.Stub() {
                override fun onDelta(text: String) {
                    heartbeat.set(System.nanoTime())
                    it.onDelta(text)
                }
            }
        }
        try {
            val reply = remote.execute(owner, operation, arguments, callback)
            workerPid = reply.getInt("pid")
            residentPrivateBytes.set(reply.getLong("privateDirtyBytes"))
            if (cancelledAt != 0L) throw CancellationException("Генерация остановлена")
            reply.getString("error")?.let { throw IllegalStateException(it) }
            return reply
        } catch (failure: RemoteException) {
            worker = null
            loaded = false
            residentPrivateBytes.set(0)
            onWorkerLost()
            if (cancelledAt != 0L) throw CancellationException("Генерация остановлена")
            val exit = try {
                if (workerPid > 0) context.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(context.packageName, workerPid, 1).firstOrNull() else null
            } catch (_: RuntimeException) { null }
            val message = when {
                exit?.reason == ApplicationExitInfo.REASON_LOW_MEMORY -> "Android завершил рабочий процесс из-за нехватки памяти"
                exit?.description?.contains("MiuiMemoryService") == true -> "Служба памяти MIUI завершила процесс модели"
                else -> "Рабочий процесс генерации завершился или перестал отвечать"
            }
            throw NativeWorkerException(message, failure)
        } finally {
            watchdog.cancel(false)
        }
    }

    private fun connect(): ILlamaWorker {
        worker?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        val ready = CountDownLatch(1)
        connection?.let { try { context.unbindService(it) } catch (_: IllegalArgumentException) { } }
        val newConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (!closed && connection === this) worker = ILlamaWorker.Stub.asInterface(service)
                ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (connection === this) {
                    worker = null
                    loaded = false
                    residentPrivateBytes.set(0)
                    onWorkerLost()
                }
            }
            override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
            override fun onNullBinding(name: ComponentName) { ready.countDown() }
        }
        connection = newConnection
        val intent = Intent(context, LlamaWorkerService::class.java)
        var bound = false
        repeat(3) { attempt ->
            if (!bound) {
                bound = context.bindService(intent, newConnection, Context.BIND_AUTO_CREATE)
                if (!bound && attempt < 2) Thread.sleep(250L * (attempt + 1))
            }
        }
        check(bound) { "Android не запустил рабочий процесс модели. Повторите запуск чуть позже." }
        check(ready.await(15, TimeUnit.SECONDS)) { "Рабочий процесс модели не подключился" }
        return checkNotNull(worker) { "Рабочий процесс модели недоступен" }
    }

    private fun arguments() = Bundle().apply {
        putInt("context", params.contextSize)
        putInt("maxTokens", params.maxTokens)
        putFloat("temperature", params.temperature)
        putFloat("topP", params.topP)
        putInt("topK", params.topK)
        putFloat("penalty", params.repeatPenalty)
        putInt("gpuLayers", params.gpuLayers)
        putString("computeMode", params.computeMode.name)
        putString("inferenceBackend", params.inferenceBackend.name)
        putBoolean("automaticMemory", params.automaticMemory)
        putInt("threads", threads)
        putInt("batch", batch)
    }

    private fun <T> withInput(input: String, operation: (Bundle) -> T): T {
        val bytes = input.toByteArray(Charsets.UTF_8)
        return SharedMemory.create("llama-input", bytes.size.coerceAtLeast(1)).use { memory ->
            val buffer = memory.mapReadWrite()
            try { buffer.put(bytes) } finally { SharedMemory.unmap(buffer) }
            check(memory.setProtect(OsConstants.PROT_READ))
            operation(Bundle().apply { putParcelable("input", memory); putInt("inputBytes", bytes.size) })
        }
    }

    private fun compatibilityKey(path: String, backend: InferenceBackend): String {
        val file = File(path)
        val identity = "961e9a3e-2|$appRevision|${Build.FINGERPRINT}|$backend|${driverIdentities[backend]}|${file.absolutePath}|${file.length()}|${file.lastModified()}"
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val processMutex = Mutex()
        private val activeOwner = AtomicReference<String?>(null)
        val residentPrivateBytes = AtomicLong(0)
        private val cleanupExecutor = Executors.newCachedThreadPool { Thread(it, "llm-cleanup").apply { isDaemon = true } }
        private val watchdogExecutor = Executors.newSingleThreadScheduledExecutor { Thread(it, "llm-watchdog").apply { isDaemon = true } }
    }
}
