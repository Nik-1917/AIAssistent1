package com.example.aiassistent1.domain.model

import kotlin.math.sqrt

object VoiceEmbedding {
    fun isValid(value: FloatArray): Boolean =
        value.size in 16..4096 && value.all { it.isFinite() } && value.any { it != 0f }

    fun matches(a: FloatArray, b: FloatArray, threshold: Float): Boolean {
        if (!isValid(a) || !isValid(b) || a.size != b.size || threshold !in 0f..1f) return false
        var dot = 0.0; var aa = 0.0; var bb = 0.0
        a.indices.forEach { i -> dot += a[i].toDouble() * b[i]; aa += a[i].toDouble() * a[i]; bb += b[i].toDouble() * b[i] }
        return dot / sqrt(aa * bb) >= threshold
    }
}
