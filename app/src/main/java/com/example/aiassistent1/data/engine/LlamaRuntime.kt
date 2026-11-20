package com.example.aiassistent1.data.engine

import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.GenerationResult
import com.example.aiassistent1.domain.model.GenerationStopReason
import com.llamatik.library.platform.GenStream
import com.example.aiassistent1.domain.model.GpuDevice
import com.example.aiassistent1.domain.model.InferenceExecution
import com.example.aiassistent1.domain.model.InferenceBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex

/** Native boundary; lifecycle tests use a fake runtime and a real executor. */
internal interface LlamaRuntime {
    fun updateParams(params: GenerationParams, threads: Int, batchSize: Int)
    fun load(path: String): Boolean
    fun generateStream(prompt: String, stream: GenStream)
    fun cancel()
    fun shutdown()
    fun beginRequest() = Unit
    fun prepareTokenizer(path: String) = Unit
    fun countTokens(prompt: String): Int = error("Exact tokenizer is unavailable")
    fun resizeContext(context: Int, batch: Int, threads: Int): Boolean = false
    fun continueStream(stream: GenStream): Unit = error("Native continuation is unavailable")
    val lastGeneration: GenerationResult? get() = null
    val requestMutex: Mutex? get() = null
    val allocationValid: Boolean get() = true
    val executionState: StateFlow<InferenceExecution>? get() = null
    fun gpuDevice(path: String, backend: InferenceBackend): GpuDevice? = null
    fun recordFallback(reason: String?) = Unit
    fun close() = Unit
}

internal object NativeLlamaRuntime : LlamaRuntime {
    private var params = GenerationParams()
    private var threads = 4
    private var batch = 64
    var lastTimings: LongArray? = null
        private set
    override var lastGeneration: GenerationResult? = null
        private set

    override fun updateParams(params: GenerationParams, threads: Int, batchSize: Int) {
        this.params = params
        this.threads = threads
        batch = batchSize
    }

    override fun beginRequest() { AutomaticLlamaBridge.begin() }
    override fun prepareTokenizer(path: String) { AutomaticLlamaBridge.prepare(path.toByteArray(Charsets.UTF_8)) }
    override fun countTokens(prompt: String): Int = AutomaticLlamaBridge.count(prompt.toByteArray(Charsets.UTF_8))
    override fun load(path: String): Boolean {
        lastTimings = null
        return nativeInference {
            AutomaticLlamaBridge.load(path.toByteArray(Charsets.UTF_8), params.contextSize, batch, threads,
                params.gpuLayers, params.inferenceBackend.nativeId) >= params.contextSize
        }
    }
    override fun gpuDevice(path: String, backend: InferenceBackend): GpuDevice? {
        val info = try { AutomaticLlamaBridge.gpuDevice(backend.nativeId) }
        catch (failure: IllegalStateException) { throw GpuExecutionException(failure.message ?: "Устройство недоступно", failure) }
        return info.takeIf { it.size == 5 }?.let {
            val total = it[3].toLong()
            val free = it[2].toLong().takeIf { bytes -> bytes > 0 || backend == InferenceBackend.OPENCL || total > 0 }
            GpuDevice(it[0], it[1], free, total, it[4].toLongOrNull())
        }
    }
    override fun resizeContext(context: Int, batch: Int, threads: Int): Boolean =
        nativeInference { AutomaticLlamaBridge.resize(context, batch, threads) >= context }
    override fun generateStream(prompt: String, stream: GenStream) {
        run(prompt, false, stream)
    }
    override fun continueStream(stream: GenStream) { run("", true, stream) }
    private fun run(prompt: String, continuation: Boolean, stream: GenStream) {
        lastGeneration = null
        val result = nativeInference {
            AutomaticLlamaBridge.generate(prompt.toByteArray(Charsets.UTF_8), continuation,
                params.temperature, params.topP, params.topK, params.repeatPenalty,
                if (params.automaticMemory) 0 else params.maxTokens,
                object : AutomaticLlamaBridge.BytesCallback {
                    override fun onBytes(bytes: ByteArray) { stream.onDelta(bytes.toString(Charsets.UTF_8)) }
                })
        }
        check(result.size >= 4) { "Некорректная статистика генерации" }
        lastGeneration = GenerationResult(GenerationStopReason.entries[result[0].toInt()],
            Math.toIntExact(result[1]), Math.toIntExact(result[2]), Math.toIntExact(result[3]))
        lastTimings = result.takeIf { it.size >= 9 }?.copyOfRange(4, 9)
        stream.onComplete()
    }
    override fun cancel() { AutomaticLlamaBridge.cancel() }
    override fun shutdown() { AutomaticLlamaBridge.shutdown(); lastGeneration = null }

    private inline fun <T> nativeInference(call: () -> T): T = try {
        call()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IllegalStateException) {
        if (params.inferenceBackend == InferenceBackend.CPU) throw failure
        throw GpuExecutionException(failure.message ?: "Ошибка вычислений GPU", failure)
    }
}
