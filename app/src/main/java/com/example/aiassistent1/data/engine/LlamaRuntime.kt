package com.example.aiassistent1.data.engine

import com.example.aiassistent1.domain.model.GenerationParams
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge

/** Native boundary; lifecycle tests use a fake runtime and a real executor. */
internal interface LlamaRuntime {
    fun updateParams(params: GenerationParams, threads: Int, batchSize: Int)
    fun load(path: String): Boolean
    fun generateStream(prompt: String, stream: GenStream)
    fun cancel()
    fun shutdown()
}

internal object NativeLlamaRuntime : LlamaRuntime {
    override fun updateParams(params: GenerationParams, threads: Int, batchSize: Int) {
        LlamaBridge.updateGenerateParams(
            temperature = params.temperature,
            maxTokens = params.maxTokens,
            topP = params.topP,
            topK = params.topK,
            repeatPenalty = params.repeatPenalty,
            contextLength = params.contextSize,
            numThreads = threads,
            useMmap = true,
            flashAttention = true,
            batchSize = batchSize,
            gpuLayers = params.gpuLayers,
        )
    }

    override fun load(path: String): Boolean = LlamaBridge.initGenerateModel(path)
    override fun generateStream(prompt: String, stream: GenStream) {
        LlamaBridge.generateStream(prompt, stream)
    }
    override fun cancel() { LlamaBridge.nativeCancelGenerate() }
    override fun shutdown() { LlamaBridge.shutdown() }
}
