package com.example.aiassistent1.domain.model

data class SynthesizedSpeech(
    val samples: FloatArray,
    val sampleRate: Int,
)