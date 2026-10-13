package com.example.aiassistent1.domain.model

data class StoredModel(
    val fileName: String,
    val sizeBytes: Long,
) {
    val displayName: String get() = fileName.dropLast(GGUF_EXTENSION_LENGTH)

    companion object {
        const val MAX_COUNT = 5
        private const val GGUF_EXTENSION_LENGTH = 5
    }
}
