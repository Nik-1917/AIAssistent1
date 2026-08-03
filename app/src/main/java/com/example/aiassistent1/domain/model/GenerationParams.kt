package com.example.aiassistent1.domain.model

data class GenerationParams(
    val contextSize: Int = 2_048,
    val maxTokens: Int = 512,
    val temperature: Float = 0.1f,
    val topP: Float = 0.8f,
    val topK: Int = 20,
    val repeatPenalty: Float = 1.15f,
    val gpuLayers: Int = 0,
)