package com.example.aiassistent1.data.engine

/** Тот же libllama.so и кодировка JNI, что у Llamatik 1.10.1; веса и KV-кеш не загружаются. */
internal object NativePromptTokenizer {
    init {
        System.loadLibrary("llama_jni")
        System.loadLibrary("assistant_llm_context")
    }

    external fun inspect(modelPath: String, renderedPrompt: String, userMessageForSizing: String?): IntArray
    external fun close()
}
