package com.example.aiassistent1.data.engine

import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.GenerationResult
import com.example.aiassistent1.domain.model.GenerationStopReason
import com.llamatik.library.platform.GenStream

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
}

internal object NativeLlamaRuntime : LlamaRuntime {
    private var params = GenerationParams()
    private var threads = 4
    private var batch = 64
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
    override fun load(path: String): Boolean =
        AutomaticLlamaBridge.load(path.toByteArray(Charsets.UTF_8), params.contextSize, batch, threads, params.gpuLayers) >= params.contextSize
    override fun resizeContext(context: Int, batch: Int, threads: Int): Boolean =
        AutomaticLlamaBridge.resize(context, batch, threads) >= context
    override fun generateStream(prompt: String, stream: GenStream) {
        run(prompt, false, stream)
    }
    override fun continueStream(stream: GenStream) { run("", true, stream) }
    private fun run(prompt: String, continuation: Boolean, stream: GenStream) {
        lastGeneration = null
        val result = AutomaticLlamaBridge.generate(prompt.toByteArray(Charsets.UTF_8), continuation,
            params.temperature, params.topP, params.topK, params.repeatPenalty,
            object : AutomaticLlamaBridge.BytesCallback {
                override fun onBytes(bytes: ByteArray) { stream.onDelta(bytes.toString(Charsets.UTF_8)) }
            })
        check(result.size == 4) { "Некорректная статистика генерации" }
        lastGeneration = GenerationResult(GenerationStopReason.entries[result[0].toInt()],
            Math.toIntExact(result[1]), Math.toIntExact(result[2]), Math.toIntExact(result[3]))
        stream.onComplete()
    }
    override fun cancel() { AutomaticLlamaBridge.cancel() }
    override fun shutdown() { AutomaticLlamaBridge.shutdown(); lastGeneration = null }
}
