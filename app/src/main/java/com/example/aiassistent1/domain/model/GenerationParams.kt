package com.example.aiassistent1.domain.model

data class GenerationParams(
    val contextSize: Int = 2_048,
    val maxTokens: Int = 256,
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val gpuLayers: Int = 0,
)