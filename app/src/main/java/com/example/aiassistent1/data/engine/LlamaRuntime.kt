package com.example.aiassistent1.data.engine

import com.example.aiassistent1.domain.model.GenerationParams
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge

/** Native boundary; lifecycle tests use a fake runtime and a real executor. */
internal interface LlamaRuntime {
    fun updateParams(params: GenerationParams, threads: Int, batchSize: Int)
    fun load(path: String): Boolean
    fun inspectPrompt(path: String, prompt: String, userMessageForSizing: String? = null): PromptInspection
    fun generateStream(prompt: String, stream: GenStream)
    fun cancel()
    fun shutdown()
}

internal data class PromptInspection(val tokenCount: Int, val modelContextLimit: Int, val userMessageTokens: Int? = null)

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
    override fun inspectPrompt(path: String, prompt: String, userMessageForSizing: String?): PromptInspection {
        val result = NativePromptTokenizer.inspect(path, prompt, userMessageForSizing)
        check(result.size == 3 && result[2] >= -1) { "Не удалось подсчитать токены запроса" }
        check(userMessageForSizing == null || result[2] >= 0) { "Не удалось подсчитать токены сообщения" }
        return PromptInspection(result[0], result[1], result[2].takeIf { it >= 0 })
    }
    override fun generateStream(prompt: String, stream: GenStream) {
        LlamaBridge.generateStream(prompt, stream)
    }
    override fun cancel() { LlamaBridge.nativeCancelGenerate() }
    override fun shutdown() {
        try { NativePromptTokenizer.close() } finally { LlamaBridge.shutdown() }
    }
}
