package com.example.aiassistent1.domain.model

data class GenerationParams(
    val contextSize: Int = 1_000,
    val maxTokens: Int = 128,
    val temperature: Float = 0.35f,
    val topP: Float = 0.8f,
    val topK: Int = 20,
    val repeatPenalty: Float = 1.15f,
    val gpuLayers: Int = 0,
)